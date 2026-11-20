package org.hammer.audio.experiment.operations;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.hammer.audio.JavaSoundCaptureDeviceDiscovery;
import org.hammer.audio.JavaSoundMicrophoneArraySource;
import org.hammer.audio.RecordingPreflight;
import org.hammer.audio.RecordingTap;
import org.hammer.audio.SourceCaptureSession;
import org.hammer.audio.acquisition.CaptureDeviceDescriptor;
import org.hammer.audio.acquisition.DirectoryMicrophoneArrayProfileStore;
import org.hammer.audio.acquisition.LocalizationEngine;
import org.hammer.audio.acquisition.LocalizationExperiment;
import org.hammer.audio.acquisition.LocalizationExperimentCodec;
import org.hammer.audio.acquisition.LocalizationExperimentStage;
import org.hammer.audio.acquisition.LocalizationInputMode;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.MicrophoneArrayLayout;
import org.hammer.audio.acquisition.MicrophoneArrayProfile;
import org.hammer.audio.acquisition.MicrophoneArrayProfileCodec;
import org.hammer.audio.acquisition.MicrophoneArrayReadiness;
import org.hammer.audio.acquisition.MultiChannelAudioSource;
import org.hammer.audio.acquisition.health.ExperimentHealthAggregator;
import org.hammer.audio.acquisition.health.ExperimentHealthFinding;
import org.hammer.audio.acquisition.health.ExperimentHealthFinding.Severity;
import org.hammer.audio.acquisition.health.ExperimentHealthSnapshot;
import org.hammer.audio.acquisition.health.ExperimentHealthState;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.Catalogue;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.Configuration;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.Preflight;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.ProfileView;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.RecordingView;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.RunView;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.TrackPoint;
import org.hammer.audio.geometry.Vector2;
import org.hammer.audio.recording.AudioBlockRecordingReader;
import org.hammer.audio.recording.RecordingInspection;
import org.hammer.audio.recording.runtime.FileStoreRecordingStorageProbe;
import org.hammer.audio.recording.runtime.RecordingState;
import org.hammer.audio.recording.runtime.RecordingStatus;
import org.hammer.audio.recording.runtime.RecordingStorageLevel;
import org.hammer.audio.recording.runtime.RecordingStorageStatus;

/**
 * Shared application service for microphone profiles, readiness, source execution and evidence.
 * Browser and Swing use the same immutable snapshots and the existing acquisition/recording runner.
 */
// This host coordinates existing domain services. Its command monitor protects exact-profile
// starts;
// sorted catalogue maps are request-local, and runtime facts use ConcurrentHashMap explicitly.
@SuppressWarnings({
  "PMD.CouplingBetweenObjects",
  "PMD.AvoidSynchronizedAtMethodLevel",
  "PMD.UseConcurrentHashMap"
})
public final class MicrophoneExperimentService implements AutoCloseable {

  private static final int BLOCK_FRAMES = 1024;
  private static final String RUNTIME = "runtime";
  private static final String CAPTURE_STATE = "source.captureState";
  private static final String EDIT_PROFILE = "edit-profile";
  private static final String INSPECT_DIAGNOSTICS = "inspect-diagnostics";
  private static final String PREFLIGHT_COMPONENT = "preflight";
  private static final String PERFORMANCE_COMPONENT = "performance";
  private static final String PROCESSING_OVERRUNS = "processing.overruns";
  private final Path directory;
  private final Clock clock;
  private final Supplier<List<CaptureDeviceDescriptor>> deviceDiscovery;
  private final DirectoryMicrophoneArrayProfileStore profiles;
  private final ManagedExperimentRecordings recordings;
  private final LocalizationEngine engine;
  private final MicrophoneArrayProfileCodec profileCodec = new MicrophoneArrayProfileCodec();
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "MicrophoneExperiment");
            thread.setDaemon(true);
            return thread;
          });
  private volatile ActiveRun active;
  private volatile Preflight preview;
  private boolean closed;

  /** Use local JavaSound discovery and the system clock. */
  public MicrophoneExperimentService(Path directory) {
    this(directory, Clock.systemUTC(), new JavaSoundCaptureDeviceDiscovery()::discover);
  }

  /** Create a service with explicit external boundaries for deterministic tests. */
  public MicrophoneExperimentService(
      Path directory, Clock clock, Supplier<List<CaptureDeviceDescriptor>> deviceDiscovery) {
    this(
        directory,
        clock,
        deviceDiscovery,
        ServiceLoader.load(LocalizationEngine.class).findFirst().orElse(null));
  }

  MicrophoneExperimentService(
      Path directory,
      Clock clock,
      Supplier<List<CaptureDeviceDescriptor>> deviceDiscovery,
      LocalizationEngine engine) {
    this.directory = directory.toAbsolutePath().normalize();
    this.clock = clock;
    this.deviceDiscovery = deviceDiscovery;
    this.engine = engine;
    profiles = new DirectoryMicrophoneArrayProfileStore(this.directory.resolve("profiles"));
    recordings = new ManagedExperimentRecordings(this.directory);
  }

  /** Enumerate actual local inputs without opening capture lines. */
  public Catalogue catalogue() throws IOException {
    Map<String, MicrophoneArrayProfile> available = new TreeMap<>();
    MicrophoneArrayProfile starter = starterProfile();
    available.put(starter.profileId(), starter);
    for (MicrophoneArrayProfile profile : profiles.list()) {
      available.put(profile.profileId(), profile);
    }
    return new Catalogue(
        available.values().stream().map(this::profileView).toList(),
        devices(),
        engine == null ? List.of() : engine.scenarios(),
        recordings.list());
  }

  /** Real input-capable device descriptors, shared with document binding UI. */
  public List<CaptureDeviceDescriptor> devices() {
    return List.copyOf(deviceDiscovery.get());
  }

  /**
   * Validate and atomically persist a complete profile definition, including measured calibration.
   */
  public synchronized ProfileView saveProfile(String definition) throws IOException {
    MicrophoneArrayProfile profile = profileCodec.decode(definition);
    java.util.Optional<MicrophoneArrayProfile> previous = profiles.find(profile.profileId());
    if (previous.isPresent()
        && previous.get().calibration() != null
        && profile.calibration() != null
        && (!previous.get().array().microphones().equals(profile.array().microphones())
            || !java.util.Objects.equals(previous.get().liveCapture(), profile.liveCapture()))
        && previous.get().calibration().profileId().equals(profile.calibration().profileId())
        && previous
            .get()
            .calibration()
            .calibratedAt()
            .equals(profile.calibration().calibratedAt())) {
      throw new IllegalArgumentException(
          "Geometry or capture settings changed; remove the old calibration or supply a newly"
              + " measured calibration.");
    }
    profiles.save(profile);
    preview = null;
    return profileView(profile);
  }

  /** Inspect an imported recording before making it available as replay input. */
  public RecordingView importRecording(InputStream input) throws IOException {
    return recordings.importRecording(input);
  }

  /** Recover readable blocks into a new file while retaining the original integrity evidence. */
  public RecordingView recoverRecording(String id) throws IOException {
    return recordings.recover(id);
  }

  /** Perform blocking filesystem/device probes on the caller's background/request thread. */
  public Preflight preflight(Configuration configuration) throws IOException {
    return preflight(configuration, profile(configuration.profileId()));
  }

  private Preflight preflight(Configuration configuration, MicrophoneArrayProfile profile)
      throws IOException {
    ExperimentHealthAggregator health = new ExperimentHealthAggregator();
    Map<String, String> facts = initialFacts(configuration, profile);
    List<ExperimentHealthFinding> findings = new ArrayList<>();
    LocalizationInputMode mode = LocalizationInputMode.valueOf(configuration.sourceMode());
    if (engine == null) {
      findings.add(
          finding(
              "plugin.localization",
              Severity.BLOCKING,
              "Localization implementation is unavailable",
              "Install the acoustic localization plugin before running.",
              PREFLIGHT_COMPONENT,
              "plugin-settings"));
    }
    MicrophoneArrayReadiness readiness =
        MicrophoneArrayReadiness.assess(profile, mode, clock.instant(), 1.0);
    if (!readiness.ready()) {
      findings.add(
          finding(
              "source.profile",
              Severity.BLOCKING,
              "Profile is not ready for " + mode,
              String.join(" ", readiness.diagnostics()),
              PREFLIGHT_COMPONENT,
              EDIT_PROFILE));
    }
    if (profile.array().channels() < 2) {
      findings.add(
          finding(
              "source.geometry",
              Severity.BLOCKING,
              "Localization needs at least two microphones",
              "Define geometry and a contiguous channel map before running.",
              PREFLIGHT_COMPONENT,
              EDIT_PROFILE));
    }
    if (profile.array().microphones().stream().map(Microphone::positionMeters).distinct().count()
        != profile.array().channels()) {
      findings.add(
          finding(
              "source.geometry-overlap",
              Severity.BLOCKING,
              "Microphones have overlapping positions",
              "Measure and enter distinct microphone positions in metres.",
              PREFLIGHT_COMPONENT,
              EDIT_PROFILE));
    }
    inspectLiveDevice(mode, profile, findings, facts);
    AudioFormatDescriptor format = sourceFormat(configuration, profile, findings);
    facts.put(
        "source.format",
        format.sampleRate()
            + " Hz · "
            + format.channels()
            + " channels · "
            + format.sourceSampleSizeInBits()
            + " bit PCM");
    RecordingStorageStatus storage = storagePreflight(configuration, format, facts, findings);
    if (mode == LocalizationInputMode.LIVE && !configuration.recordOutput()) {
      findings.add(
          finding(
              "evidence.live-recording",
              Severity.WARNING,
              "Live samples will not be reproducible without a recording",
              "Enable recording to preserve the experiment input.",
              PREFLIGHT_COMPONENT,
              "enable-recording"));
    }
    health.update(PREFLIGHT_COMPONENT, findings, clock.instant());
    ExperimentHealthSnapshot snapshot =
        health.snapshot(
            PREFLIGHT_COMPONENT,
            configuration.name(),
            ExperimentHealthState.READY,
            "DEFINED → CALIBRATED",
            facts,
            clock.instant());
    Preflight result = new Preflight(fingerprint(configuration, profile), snapshot, storage);
    preview = result;
    return result;
  }

  private void inspectLiveDevice(
      LocalizationInputMode mode,
      MicrophoneArrayProfile profile,
      List<ExperimentHealthFinding> findings,
      Map<String, String> facts) {
    if (mode == LocalizationInputMode.LIVE
        && (profile.liveCapture() == null
            || devices().stream()
                .noneMatch(
                    device ->
                        device.deviceId().equals(profile.liveCapture().device().deviceId())))) {
      findings.add(
          finding(
              "source.live-device",
              Severity.BLOCKING,
              "Selected live capture device is unavailable",
              "Connect a synchronized multichannel device on the application host and select it in"
                  + " the profile. Browser microphones are not host capture devices.",
              PREFLIGHT_COMPONENT,
              "select-device"));
    } else if (mode == LocalizationInputMode.LIVE) {
      try {
        if (!new JavaSoundCaptureDeviceDiscovery().supportsFormat(profile.liveCapture())) {
          findings.add(
              finding(
                  "source.live-format",
                  Severity.BLOCKING,
                  "Device does not advertise the selected PCM format",
                  "Choose a sample rate, channel count and PCM encoding supported by the selected"
                      + " input device.",
                  PREFLIGHT_COMPONENT,
                  EDIT_PROFILE));
        }
        facts.put(
            "source.deviceAccess",
            "Format advertisement checked; opening the device at Start will confirm permission and"
                + " availability.");
      } catch (IllegalArgumentException | SecurityException exception) {
        findings.add(
            finding(
                "source.live-permission",
                Severity.BLOCKING,
                "Capture device capability cannot be inspected",
                exception.toString(),
                PREFLIGHT_COMPONENT,
                "select-device"));
      }
    }
  }

  private RecordingStorageStatus storagePreflight(
      Configuration configuration,
      AudioFormatDescriptor format,
      Map<String, String> facts,
      List<ExperimentHealthFinding> findings) {
    RecordingStorageStatus storage = null;
    if (configuration.recordOutput()) {
      try {
        storage =
            new FileStoreRecordingStorageProbe()
                .probe(
                    preflightDestination(configuration),
                    0L,
                    0.0,
                    RecordingPreflight.expectedBytesPerSecond(format, BLOCK_FRAMES),
                    clock.instant());
        facts.put("storage.destination", storage.destination().toString());
        facts.put("storage.usableBytes", Long.toString(storage.usableBytes()));
        facts.put("storage.safeSeconds", Long.toString(storage.estimatedSafeSecondsRemaining()));
        facts.put(
            "storage.expectedBytesPerSecond", Double.toString(storage.expectedBytesPerSecond()));
        if (!storage.writable() || storage.critical()) {
          findings.add(
              finding(
                  "storage.capacity",
                  Severity.BLOCKING,
                  "Recording destination is not ready",
                  storage.level() + " · usable bytes " + storage.usableBytes(),
                  PREFLIGHT_COMPONENT,
                  "storage-settings"));
        } else if (storage.level() == RecordingStorageLevel.WARNING
            || !storage
                .policy()
                .fits(storage.estimatedSafeSecondsRemaining(), configuration.durationSeconds())) {
          findings.add(
              finding(
                  "storage.margin",
                  Severity.WARNING,
                  "Storage margin is smaller than the planned experiment",
                  "Estimated safe seconds: "
                      + storage.estimatedSafeSecondsRemaining()
                      + "; capacity is advisory.",
                  PREFLIGHT_COMPONENT,
                  "storage-settings"));
        }
      } catch (IOException exception) {
        findings.add(
            finding(
                "storage.query",
                Severity.BLOCKING,
                "Recording capacity cannot be checked",
                exception.getMessage(),
                PREFLIGHT_COMPONENT,
                "storage-settings"));
      }
    }
    return storage;
  }

  /** Recheck readiness before starting, rejecting profile/configuration edits and true blockers. */
  public synchronized String start(
      Configuration configuration, String token, boolean acknowledgeStorageWarning)
      throws IOException {
    if (closed) {
      throw new IllegalStateException("Experiment service is closed.");
    }
    if (active != null && !active.finished) {
      throw new IllegalStateException("An experiment is already running.");
    }
    MicrophoneArrayProfile selectedProfile = profile(configuration.profileId());
    Preflight checked = preflight(configuration, selectedProfile);
    if (!checked.token().equals(token)) {
      throw new IllegalStateException("Configuration changed; review a fresh preflight.");
    }
    if (!checked.health().startAllowed()) {
      throw new IllegalStateException("Experiment is blocked; resolve the listed findings.");
    }
    if (!acknowledgeStorageWarning
        && checked.health().findings().stream()
            .anyMatch(item -> "storage.margin".equals(item.code()))) {
      throw new IllegalStateException("Confirm the storage margin warning before starting.");
    }
    String id = UUID.randomUUID().toString();
    ActiveRun run =
        new ActiveRun(
            id,
            configuration,
            selectedProfile,
            directory.resolve("runs").resolve(id),
            clock.instant());
    run.facts.putAll(checked.health().facts());
    run.health.update(PREFLIGHT_COMPONENT, checked.health().findings(), clock.instant());
    run.facts.put("configuration.sha256", checked.token());
    run.facts.put("operator.attribution", "Unattributed local operator");
    run.state = ExperimentHealthState.RUNNING;
    active = run;
    executor.execute(() -> execute(run));
    return id;
  }

  /** Persistent, immutable active/final status; performs no filesystem or device probe. */
  public RunView snapshot() {
    ActiveRun run = active;
    if (run == null) {
      Preflight current = preview;
      ExperimentHealthSnapshot health =
          current == null
              ? new ExperimentHealthAggregator()
                  .snapshot(
                      "",
                      "Microphone experiment",
                      ExperimentHealthState.NOT_CONFIGURED,
                      "Define → calibrate → acquire → localize → benchmark → export",
                      Map.of(),
                      clock.instant())
              : current.health();
      return new RunView("", health, null, List.of(), false);
    }
    RecordingStatus recording = run.recorder == null ? null : run.recorder.status();
    return new RunView(
        run.id,
        run.health.snapshot(
            run.id, run.configuration.name(), run.state, run.stage, run.facts, clock.instant()),
        recording,
        run.tracks,
        run.finished && run.evidenceReady);
  }

  /**
   * Stop source acquisition and finalize captured blocks; stopping early is incomplete evidence.
   */
  public void stop() throws IOException {
    ActiveRun run = active;
    if (run == null
        || run.finished
        || run.state == ExperimentHealthState.COMPLETED
        || run.state == ExperimentHealthState.INCOMPLETE
        || run.state == ExperimentHealthState.FAILED) {
      return;
    }
    run.cancelled.set(true);
    run.state = ExperimentHealthState.STOPPING;
    if (run.source != null) {
      run.source.close();
    }
  }

  /** Acknowledge a latched warning without permitting unresolved blockers. */
  public void acknowledge(String code, String operator) throws IOException {
    ActiveRun run = active;
    if (run == null) {
      throw new IllegalStateException("No active or completed run.");
    }
    run.evidenceLock.lock();
    try {
      run.health.acknowledge(code, operator, clock.instant());
      if (run.evidenceReady) {
        try {
          writeHealthEvidence(run);
        } catch (IOException failure) {
          fail(run, failure);
          throw failure;
        }
      }
    } finally {
      run.evidenceLock.unlock();
    }
  }

  /**
   * Stream finalized artifacts and their hashes without buffering recordings in browser/server
   * memory.
   */
  public void exportEvidence(OutputStream output) throws IOException {
    ActiveRun run = active;
    if (run == null) {
      throw new IllegalStateException("Finalize the experiment before exporting evidence.");
    }
    run.evidenceLock.lock();
    try {
      if (!run.finished || !run.evidenceReady) {
        throw new IllegalStateException("Finalize the experiment before exporting evidence.");
      }
      ExperimentEvidenceFiles.export(run.directory, output);
    } finally {
      run.evidenceLock.unlock();
    }
  }

  /**
   * Atomically export outside managed inputs/runs so a package can never include its own output.
   */
  public void exportEvidence(Path target) throws IOException {
    Path destination = target.toAbsolutePath().normalize();
    Path parent = destination.getParent();
    if (destination.startsWith(directory) || parent == null) {
      throw new IOException("Choose an evidence export outside the managed experiment directory.");
    }
    Files.createDirectories(parent);
    if (Files.exists(directory) && parent.toRealPath().startsWith(directory.toRealPath())) {
      throw new IOException(
          "Evidence export must not resolve into the managed experiment directory.");
    }
    Path temporary = Files.createTempFile(parent, ".experiment-evidence-", ".partial");
    try {
      try (OutputStream output = Files.newOutputStream(temporary)) {
        exportEvidence(output);
      }
      Files.move(
          temporary,
          destination,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private void execute(ActiveRun run) {
    try {
      Files.createDirectories(run.directory);
      run.facts.put("evidence.complete", "false");
      writeManifest(run, LocalizationExperimentStage.DEFINED);
      requireNotCancelled(run);
      LocalizationInputMode mode = LocalizationInputMode.valueOf(run.configuration.sourceMode());
      try (MultiChannelAudioSource input =
          switch (mode) {
            case SIMULATION ->
                engine.simulate(
                    run.configuration.scenario(),
                    run.profile.array(),
                    run.configuration.durationSeconds());
            case REPLAY ->
                new RecordingMicrophoneSource(
                    recordings.path(run.configuration.recordingId()), run.profile.array());
            case LIVE -> new JavaSoundMicrophoneArraySource(run.profile, 1.0);
          }) {
        run.source =
            new SourceCaptureSession(
                input,
                Math.round(input.format().sampleRate() * run.configuration.durationSeconds()),
                BLOCK_FRAMES);
        requireNotCancelled(run);
        if (run.configuration.recordOutput()) {
          run.recorder =
              RecordingTap.start(
                  run.source, run.directory.resolve(run.configuration.outputFilename()));
        }
        run.stage = "ACQUIRING / LOCALIZING";
        run.facts.put(CAPTURE_STATE, "RUNNING");
        LocalizationExperiment experiment =
            LocalizationExperiment.defined(
                    run.id,
                    run.configuration.name(),
                    run.profile,
                    mode,
                    sourceReference(run.configuration),
                    clock.instant())
                .withMetadata("configuration.sha256", run.facts.get("configuration.sha256"));
        LocalizationEngine.Result result =
            engine.run(
                run.source,
                experiment,
                new LocalizationEngine.Options(
                    run.configuration.scenario(),
                    BLOCK_FRAMES,
                    run.configuration.durationSeconds()),
                frame -> progress(run, frame));
        if (run.recorder != null) {
          if (run.cancelled.get()) {
            run.recorder.stopIncomplete("Experiment stopped before its intended duration.");
          } else {
            run.recorder.stop();
          }
        }
        run.facts.put(CAPTURE_STATE, "STOPPED");
        finish(run, result);
      }
    } catch (IOException | RuntimeException exception) {
      fail(run, exception);
    } finally {
      run.health.update(RUNTIME, List.of(), clock.instant());
      run.finished = true;
    }
  }

  private void progress(ActiveRun run, LocalizationEngine.Frame snapshot) {
    run.facts.put("processed.blocks", Integer.toString(snapshot.index() + 1));
    run.facts.put("processed.frames", Long.toString(run.source.framesRead()));
    run.facts.put("source.lastBlockAt", clock.instant().toString());
    run.facts.put(
        "elapsed.sampleSeconds",
        Double.toString(run.source.framesRead() / run.source.format().sampleRate()));
    updateProcessingHealth(run, snapshot);
    run.tracks =
        snapshot.tracks().stream()
            .map(track -> new TrackPoint(track.id(), track.x(), track.y(), track.confidence()))
            .toList();
    List<ExperimentHealthFinding> findings = new ArrayList<>();
    inspectSignal(run, findings);
    if (snapshot.synchronization().status()
        != org.hammer.audio.acquisition.SynchronizationStatus.TRUSTED) {
      boolean usable = snapshot.synchronization().usable();
      findings.add(
          finding(
              "calibration.runtime",
              usable ? Severity.WARNING : Severity.ERROR,
              usable ? "Synchronization is degraded" : "Synchronization is no longer usable",
              String.join(" ", snapshot.synchronization().diagnostics()),
              RUNTIME,
              EDIT_PROFILE));
    }
    if (!snapshot.reliableTdoa()) {
      findings.add(
          finding(
              "signal.tdoa",
              Severity.WARNING,
              "TDOA pair evidence is inconsistent",
              "Inspect geometry, calibration, reflections and signal quality.",
              RUNTIME,
              INSPECT_DIAGNOSTICS));
    }
    RecordingStatus recording = run.recorder == null ? null : run.recorder.status();
    if (recording != null
        && (recording.storage().level() != RecordingStorageLevel.NORMAL
            || !recording.completeSoFar())) {
      findings.add(
          finding(
              "recording.health",
              recording.terminal() ? Severity.ERROR : Severity.WARNING,
              "Recording: " + recording.state() + " · storage " + recording.storage().level(),
              recording.stopReason() + " " + recording.storage().errorMessage(),
              RUNTIME,
              "inspect-recording"));
    }
    findings.add(
        new ExperimentHealthFinding(
            "source.freshness",
            Severity.INFO,
            "Source samples received",
            "Block " + (snapshot.index() + 1),
            RUNTIME,
            clock.instant(),
            5L,
            "select-source",
            false,
            false));
    run.health.update(RUNTIME, findings, clock.instant());
  }

  private void updateProcessingHealth(ActiveRun run, LocalizationEngine.Frame snapshot) {
    run.facts.put("processing.lastNanos", Long.toString(snapshot.processingNanos()));
    run.facts.put("processing.budgetNanos", Long.toString(snapshot.processingBudgetNanos()));
    if (snapshot.processingNanos() > snapshot.processingBudgetNanos()) {
      run.processingOverruns++;
    }
    updateProcessingHealth(run, snapshot.index() + 1L, run.processingOverruns);
  }

  private void updateProcessingHealth(ActiveRun run, long blocks, long overruns) {
    run.facts.put(PROCESSING_OVERRUNS, Long.toString(overruns));
    List<ExperimentHealthFinding> findings =
        overruns >= Math.max(3L, blocks / 5L)
            ? List.of(
                finding(
                    PROCESSING_OVERRUNS,
                    Severity.WARNING,
                    "Processing repeatedly exceeded its real-time budget",
                    overruns
                        + " of "
                        + blocks
                        + " blocks exceeded budget. Inspect algorithm settings before a live run.",
                    PERFORMANCE_COMPONENT,
                    INSPECT_DIAGNOSTICS))
            : List.of();
    // Clear the current condition on recovery; the aggregator retains its warning and review.
    // A stable message preserves acknowledgement while only the running counts change.
    run.health.update(PERFORMANCE_COMPONENT, findings, clock.instant());
  }

  private void inspectSignal(ActiveRun run, List<ExperimentHealthFinding> findings) {
    org.hammer.audio.core.AudioBlock block = run.source.getLatestBlock();
    if (block == null || block.frames() == 0) {
      return;
    }
    long clipping = 0L;
    List<String> channelRms = new ArrayList<>();
    double largestRms = 0.0;
    for (int channel = 0; channel < block.channels(); channel++) {
      double squares = 0.0;
      for (float sample : block.channelView(channel)) {
        squares += sample * sample;
        if (Math.abs(sample) >= 0.999f) {
          clipping++;
        }
      }
      double rms = Math.sqrt(squares / block.frames());
      largestRms = Math.max(largestRms, rms);
      channelRms.add(Double.toString(rms));
    }
    run.facts.put("signal.channelRms", String.join(",", channelRms));
    run.facts.put("signal.clippedSamples", Long.toString(clipping));
    if (clipping > 0L) {
      findings.add(
          finding(
              "signal.clipping",
              Severity.WARNING,
              "Input samples are clipping",
              clipping + " samples reached the PCM limit in the latest block; reduce capture gain.",
              RUNTIME,
              "inspect-signal"));
    }
    if (largestRms < 0.000001) {
      findings.add(
          finding(
              "signal.silence",
              Severity.WARNING,
              "All microphone channels are silent",
              "Check device routing, input gain and the physical sound source.",
              RUNTIME,
              "inspect-signal"));
    }
  }

  private void finish(ActiveRun run, LocalizationEngine.Result result) throws IOException {
    run.stage = "BENCHMARKED / FINALIZING EVIDENCE";
    boolean complete = !run.cancelled.get() && result.blocks() > 0;
    complete &=
        run
            .health
            .snapshot(
                run.id, run.configuration.name(), run.state, run.stage, run.facts, clock.instant())
            .findings()
            .stream()
            .noneMatch(
                finding ->
                    finding.severity() == Severity.ERROR
                        || finding.severity() == Severity.BLOCKING);
    complete &= recordingEvidenceComplete(run);
    run.facts.put(
        "benchmark.status",
        result.benchmarkAvailable() ? "AVAILABLE" : "Unavailable: no reference ground truth");
    updateProcessingHealth(run, result.blocks(), result.overruns());
    run.facts.put(CAPTURE_STATE, "STOPPED");
    complete &= writeResultArtifacts(run, result);
    if ("REPLAY".equals(run.configuration.sourceMode())) {
      Path input = recordings.path(run.configuration.recordingId());
      Files.copy(input, run.directory.resolve("input.aarec"));
      Path recovery = input.resolveSibling(input.getFileName() + ".recovery.txt");
      if (Files.isRegularFile(recovery)) {
        Files.copy(recovery, run.directory.resolve("input-recovery.txt"));
      }
    }
    complete &= !run.cancelled.get();
    run.facts.put("evidence.complete", Boolean.toString(complete));
    run.state = complete ? ExperimentHealthState.COMPLETED : ExperimentHealthState.INCOMPLETE;
    run.stage = "EXPORTED";
    run.evidenceLock.lock();
    try {
      writeManifest(run, LocalizationExperimentStage.EXPORTED);
      writeHealthEvidence(run);
      run.evidenceReady = true;
    } finally {
      run.evidenceLock.unlock();
    }
  }

  private boolean writeResultArtifacts(ActiveRun run, LocalizationEngine.Result result)
      throws IOException {
    Set<String> generated = new TreeSet<>();
    for (Map.Entry<String, String> artifact : result.artifacts().entrySet()) {
      if (!artifact.getKey().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")) {
        throw new IOException("Plugin output filename is not portable: " + artifact.getKey());
      }
      Path path = run.directory.resolve(artifact.getKey());
      Files.writeString(path, artifact.getValue());
      if (Files.isRegularFile(path)) {
        generated.add(artifact.getKey());
      }
    }
    Set<String> required = new TreeSet<>(result.requiredArtifacts());
    Set<String> missing = new TreeSet<>(required);
    missing.removeAll(generated);
    String generatedNames = String.join(", ", generated);
    String missingNames = String.join(", ", missing);
    run.facts.put("artifacts.required", String.join(", ", required));
    run.facts.put("artifacts.generated", generatedNames);
    run.facts.put("artifacts.missing", missingNames);
    boolean complete = !required.isEmpty() && missing.isEmpty();
    List<ExperimentHealthFinding> findings = new ArrayList<>();
    if (!complete) {
      findings.add(
          finding(
              "evidence.artifacts",
              Severity.ERROR,
              required.isEmpty()
                  ? "Required analysis reports were not declared"
                  : "Required analysis reports are missing",
              (required.isEmpty()
                      ? "The localization provider returned no required report filenames."
                      : "Missing required reports: " + missingNames + '.')
                  + " Persisted reports: "
                  + generatedNames
                  + ". Existing files remain available as incomplete evidence.",
              "artifacts",
              INSPECT_DIAGNOSTICS));
    }
    run.health.update("artifacts", findings, clock.instant());
    return complete;
  }

  private static boolean recordingEvidenceComplete(ActiveRun run) throws IOException {
    if (run.recorder == null) {
      return !"LIVE".equals(run.configuration.sourceMode());
    }
    if (!Files.isRegularFile(run.recorder.file())) {
      return false;
    }
    RecordingInspection inspection = AudioBlockRecordingReader.inspect(run.recorder.file());
    run.facts.put("recording.integrity", inspection.integrity().name());
    run.facts.put("recording.verifiedContentSha256", inspection.sha256());
    run.facts.put("recording.sha256", ExperimentEvidenceFiles.sha256(run.recorder.file()));
    run.facts.put("recording.bytes", Long.toString(Files.size(run.recorder.file())));
    return run.recorder.status().state() == RecordingState.COMPLETED && inspection.complete();
  }

  private void fail(ActiveRun run, Exception exception) {
    run.evidenceReady = false;
    run.state =
        run.cancelled.get() ? ExperimentHealthState.INCOMPLETE : ExperimentHealthState.FAILED;
    run.stage = "FINALIZATION FAILED";
    run.facts.put("evidence.complete", "false");
    run.facts.put(CAPTURE_STATE, "STOPPED");
    run.health.update(
        "failure",
        List.of(
            finding(
                "run.failure",
                Severity.ERROR,
                run.cancelled.get() ? "Experiment stopped before completion" : "Experiment failed",
                exception.toString(),
                "failure",
                INSPECT_DIAGNOSTICS)),
        clock.instant());
    try {
      if (run.source != null) {
        run.source.close();
      }
      if (run.recorder != null) {
        run.recorder.stopIncomplete("Experiment did not complete: " + exception);
      }
      run.evidenceLock.lock();
      try {
        Files.createDirectories(run.directory);
        writeManifest(run, LocalizationExperimentStage.DEFINED);
        writeHealthEvidence(run);
        run.evidenceReady = true;
      } finally {
        run.evidenceLock.unlock();
      }
    } catch (IOException closeFailure) {
      run.facts.put("evidence.finalizationError", closeFailure.toString());
    }
  }

  private void writeHealthEvidence(ActiveRun run) throws IOException {
    Files.writeString(run.directory.resolve("health.txt"), diagnosticText(run));
    ExperimentEvidenceFiles.writeHashes(run.directory);
  }

  private static void requireNotCancelled(ActiveRun run) throws IOException {
    if (run.cancelled.get()) {
      if (run.source != null) {
        run.source.close();
      }
      throw new IOException("Experiment stopped before source execution.");
    }
  }

  private void writeManifest(ActiveRun run, LocalizationExperimentStage stage) throws IOException {
    LocalizationExperiment experiment =
        new LocalizationExperiment(
            run.id,
            run.configuration.name(),
            run.profile,
            LocalizationInputMode.valueOf(run.configuration.sourceMode()),
            sourceReference(run.configuration),
            run.createdAt,
            stage,
            run.facts);
    Files.writeString(
        run.directory.resolve("experiment.manifest"),
        new LocalizationExperimentCodec().encode(experiment));
  }

  private String diagnosticText(ActiveRun run) {
    ExperimentHealthSnapshot snapshot =
        run.health.snapshot(
            run.id, run.configuration.name(), run.state, run.stage, run.facts, clock.instant());
    StringBuilder text =
        new StringBuilder()
            .append(snapshot.state())
            .append('\n')
            .append(snapshot.stage())
            .append('\n');
    new TreeMap<>(snapshot.facts())
        .forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
    snapshot.findings().forEach(finding -> text.append(finding).append('\n'));
    snapshot.history().forEach(event -> text.append(event).append('\n'));
    if (run.recorder != null) {
      text.append(run.recorder.status()).append('\n');
    }
    return text.toString();
  }

  private Map<String, String> initialFacts(
      Configuration configuration, MicrophoneArrayProfile profile) {
    Map<String, String> facts = new ConcurrentHashMap<>();
    facts.put("readiness.scope", "STANDALONE_MICROPHONE");
    facts.put(
        "document.status",
        "Not applicable: this run uses a saved microphone profile, not an open .audioexp document");
    facts.put(
        "workflow.status",
        "Not applicable: this run uses the localization pipeline, not the editable workflow graph");
    facts.put(
        "bindings.status",
        "Source resources are checked for this profile and configuration; document bindings are not"
            + " used");
    facts.put("source.mode", configuration.sourceMode());
    facts.put("profile.id", profile.profileId());
    facts.put("profile.layout", profile.layout().name());
    facts.put("profile.channels", Integer.toString(profile.array().channels()));
    facts.put("profile.channelMap", profile.array().microphones().toString());
    facts.put(
        "profile.sha256",
        ExperimentEvidenceFiles.sha256(
            profileCodec.encode(profile).getBytes(StandardCharsets.UTF_8)));
    facts.put("physics.speedOfSound", "343 m/s; simulated air assumption");
    facts.put("algorithm", "GCC_PHAT / TrackingPipeline");
    facts.put("plugin", engine == null ? "Unavailable" : engine.id());
    facts.put("plugin.version", engine == null ? "Unavailable" : engine.version());
    facts.put("intended.seconds", Integer.toString(configuration.durationSeconds()));
    facts.put(
        "calibration.status",
        profile.calibration() == null
            ? "Not supplied (required for LIVE)"
            : profile.calibration().profileId());
    if (profile.calibration() != null) {
      org.hammer.audio.acquisition.SynchronizationAssessment assessment =
          profile
              .calibration()
              .assess(
                  clock.instant(),
                  profile.liveCapture() == null
                      ? 16_000f
                      : profile.liveCapture().format().sampleRate(),
                  1.0);
      facts.put("calibration.calibratedAt", profile.calibration().calibratedAt().toString());
      facts.put("calibration.validUntil", profile.calibration().validUntil().toString());
      facts.put("calibration.synchronization", assessment.status().name());
      facts.put("calibration.errorSamples", Double.toString(assessment.estimatedErrorSamples()));
      facts.put("calibration.errorSeconds", Double.toString(assessment.estimatedErrorSeconds()));
      facts.put(
          "calibration.budgetPercent", Double.toString(assessment.estimatedErrorSamples() * 100.0));
    }
    return facts;
  }

  private AudioFormatDescriptor sourceFormat(
      Configuration configuration,
      MicrophoneArrayProfile profile,
      List<ExperimentHealthFinding> findings)
      throws IOException {
    if ("LIVE".equals(configuration.sourceMode()) && profile.liveCapture() != null) {
      return profile.liveCapture().format();
    }
    if ("REPLAY".equals(configuration.sourceMode())) {
      try {
        RecordingInspection inspection =
            AudioBlockRecordingReader.inspect(recordings.path(configuration.recordingId()));
        if (!inspection.complete() || inspection.continuityGapCount() != 0L) {
          findings.add(
              finding(
                  "source.replay-integrity",
                  Severity.BLOCKING,
                  "Replay input is incomplete or unverified",
                  inspection.integrity() + ": " + inspection.detail(),
                  PREFLIGHT_COMPONENT,
                  "recover-recording"));
        }
        if (inspection.format().channels() != profile.array().channels()) {
          findings.add(
              finding(
                  "source.replay-channels",
                  Severity.BLOCKING,
                  "Replay channel count differs from the selected profile",
                  "Recording channels: " + inspection.format().channels(),
                  PREFLIGHT_COMPONENT,
                  EDIT_PROFILE));
        }
        return inspection.format();
      } catch (IOException | IllegalArgumentException exception) {
        findings.add(
            finding(
                "source.replay-file",
                Severity.BLOCKING,
                "Select a readable recording for replay",
                exception.getMessage(),
                PREFLIGHT_COMPONENT,
                "import-recording"));
      }
    }
    return new AudioFormatDescriptor(
        engine == null ? 16_000f : engine.simulationSampleRate(configuration.scenario()),
        profile.array().channels(),
        32);
  }

  private MicrophoneArrayProfile profile(String id) throws IOException {
    return profiles
        .find(id)
        .orElseGet(
            () -> {
              MicrophoneArrayProfile starter = starterProfile();
              if (!starter.profileId().equals(id)) {
                throw new IllegalArgumentException("Unknown microphone profile: " + id);
              }
              return starter;
            });
  }

  private MicrophoneArrayProfile starterProfile() {
    return new MicrophoneArrayProfile(
        "rectangular-example",
        "Rectangular four-microphone example",
        MicrophoneArrayLayout.RECTANGULAR,
        engine == null
            ? new MicrophoneArray(
                List.of(
                    new Microphone("mic-0", new Vector2(0, 0), 0),
                        new Microphone("mic-1", new Vector2(1, 0), 1),
                    new Microphone("mic-2", new Vector2(1, 1), 2),
                        new Microphone("mic-3", new Vector2(0, 1), 3)))
            : engine.exampleArray(),
        Set.of(LocalizationInputMode.SIMULATION, LocalizationInputMode.REPLAY),
        null,
        null);
  }

  private ProfileView profileView(MicrophoneArrayProfile profile) {
    return new ProfileView(
        profile.profileId(),
        profile.displayName(),
        profile.layout().name(),
        profile.array().channels(),
        profileCodec.encode(profile));
  }

  private Path preflightDestination(Configuration configuration) {
    return directory
        .resolve("runs")
        .resolve(PREFLIGHT_COMPONENT)
        .resolve(configuration.outputFilename());
  }

  private String fingerprint(Configuration configuration, MicrophoneArrayProfile profile) {
    return ExperimentEvidenceFiles.sha256(
        (configuration + "\n" + profileCodec.encode(profile)).getBytes(StandardCharsets.UTF_8));
  }

  private static String sourceReference(Configuration configuration) {
    return "REPLAY".equals(configuration.sourceMode())
        ? configuration.recordingId()
        : configuration.sourceMode().toLowerCase(java.util.Locale.ROOT)
            + ':'
            + configuration.scenario();
  }

  private ExperimentHealthFinding finding(
      String code,
      Severity severity,
      String message,
      String detail,
      String component,
      String remedy) {
    return new ExperimentHealthFinding(
        code,
        severity,
        message,
        detail,
        component,
        clock.instant(),
        0L,
        remedy,
        severity == Severity.WARNING || severity == Severity.ERROR,
        false);
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    stop();
    executor.shutdown();
    try {
      if (!executor.awaitTermination(15L, java.util.concurrent.TimeUnit.SECONDS)) {
        executor.shutdownNow();
        throw new IOException("Experiment did not finish shutdown within 15 seconds.");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
      throw new IOException("Interrupted while finalizing experiment evidence.", exception);
    }
  }

  private static final class ActiveRun {
    private final String id;
    private final Configuration configuration;
    private final MicrophoneArrayProfile profile;
    private final Path directory;
    private final ExperimentHealthAggregator health = new ExperimentHealthAggregator();
    private final Map<String, String> facts = new ConcurrentHashMap<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ReentrantLock evidenceLock = new ReentrantLock();
    private final Instant createdAt;
    // Only the processing executor mutates this counter; views read the published facts.
    private long processingOverruns;
    private volatile ExperimentHealthState state = ExperimentHealthState.NOT_CONFIGURED;
    private volatile String stage = "DEFINED";
    private volatile SourceCaptureSession source;
    private volatile RecordingTap recorder;
    private volatile List<TrackPoint> tracks = List.of();
    private volatile boolean finished;
    private volatile boolean evidenceReady;

    private ActiveRun(
        String id,
        Configuration configuration,
        MicrophoneArrayProfile profile,
        Path directory,
        Instant createdAt) {
      this.id = id;
      this.configuration = configuration;
      this.profile = profile;
      this.directory = directory;
      this.createdAt = createdAt;
    }
  }
}
