package org.hammer.audio.experiment.operations;

import java.util.List;
import org.hammer.audio.acquisition.CaptureDeviceDescriptor;
import org.hammer.audio.acquisition.LocalizationInputMode;
import org.hammer.audio.acquisition.health.ExperimentHealthSnapshot;
import org.hammer.audio.recording.runtime.RecordingStatus;
import org.hammer.audio.recording.runtime.RecordingStorageStatus;

/** Immutable transport values shared by browser and Swing experiment operations. */
// Nested records form a transport namespace; callers do not instantiate this holder.
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
public final class ExperimentOperationsModels {

  private ExperimentOperationsModels() {
    // namespace for transport records
  }

  /**
   * Explicit source and output choices; output names are portable filenames inside managed runs.
   *
   * @param name experiment display name
   * @param sourceMode simulation, replay or live
   * @param profileId persisted microphone profile
   * @param scenario deterministic simulation selection
   * @param recordingId managed replay input, empty for other modes
   * @param durationSeconds bounded intended duration
   * @param recordOutput whether every consumed block should be recorded
   * @param outputFilename portable recording filename
   */
  public record Configuration(
      String name,
      String sourceMode,
      String profileId,
      String scenario,
      String recordingId,
      int durationSeconds,
      boolean recordOutput,
      String outputFilename) {

    // Reject ambiguous source modes and unbounded remote runs at the application boundary.
    public Configuration {
      if (name == null || name.isBlank() || profileId == null || profileId.isBlank()) {
        throw new IllegalArgumentException("Experiment name and microphone profile are required.");
      }
      LocalizationInputMode.valueOf(sourceMode);
      if (durationSeconds < 1 || durationSeconds > 3600) {
        throw new IllegalArgumentException(
            "Experiment duration must be between 1 and 3600 seconds.");
      }
      recordingId = recordingId == null ? "" : recordingId;
      scenario = scenario == null ? "single-source" : scenario;
      if (outputFilename == null
          || !outputFilename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,80}\\.aarec")
          || "input.aarec".equalsIgnoreCase(outputFilename)
          || outputFilename.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])\\..*")) {
        throw new IllegalArgumentException(
            "Choose a portable .aarec filename without folders or reserved device names.");
      }
    }
  }

  /**
   * A persisted profile and its editable deterministic definition.
   *
   * @param id profile identity
   * @param name profile name
   * @param layout geometry family
   * @param channels microphone count
   * @param definition complete geometry, device and measured-calibration definition
   */
  public record ProfileView(
      String id, String name, String layout, int channels, String definition) {
    // Immutable transport value.
  }

  /**
   * One managed replay candidate with its inspected completion state.
   *
   * @param id relative managed identity
   * @param name filename
   * @param integrity recording inspection result
   * @param frames complete readable frames
   */
  public record RecordingView(String id, String name, String integrity, long frames) {
    // Immutable transport value.
  }

  /**
   * Available local inputs; an empty devices list is meaningful and never replaced by fake
   * hardware.
   *
   * @param profiles persisted and starter profiles
   * @param devices real local capture devices
   * @param scenarios deterministic simulation names
   * @param recordings managed replay inputs
   */
  public record Catalogue(
      List<ProfileView> profiles,
      List<CaptureDeviceDescriptor> devices,
      List<String> scenarios,
      List<RecordingView> recordings) {
    // Preserve immutable catalogue values across both UI projections.
    public Catalogue {
      profiles = List.copyOf(profiles);
      devices = List.copyOf(devices);
      scenarios = List.copyOf(scenarios);
      recordings = List.copyOf(recordings);
    }
  }

  /**
   * Reviewable preflight tied to the exact profile and configuration hash.
   *
   * @param token configuration fingerprint invalidated by edits
   * @param health explicit readiness findings
   * @param storage capacity estimate, or null when unavailable
   */
  public record Preflight(
      String token, ExperimentHealthSnapshot health, RecordingStorageStatus storage) {
    // Immutable transport value.
  }

  /**
   * One projected track in the microphone coordinate system.
   *
   * @param id tracker identity
   * @param x horizontal metres
   * @param y vertical metres
   * @param confidence tracker confidence
   */
  public record TrackPoint(int id, double x, double y, double confidence) {
    // Immutable transport value.
  }

  /**
   * Persistent active or final run snapshot.
   *
   * @param runId stable run identity
   * @param health shared health state and evidence
   * @param recording complete recorder status when recording was requested
   * @param tracks latest localization tracks
   * @param exportAvailable whether finalized evidence can be exported
   */
  public record RunView(
      String runId,
      ExperimentHealthSnapshot health,
      RecordingStatus recording,
      List<TrackPoint> tracks,
      boolean exportAvailable) {
    // Freeze the track list while acquisition continues on the worker thread.
    public RunView {
      tracks = List.copyOf(tracks);
    }
  }
}
