package org.hammer.audio.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.hammer.audio.acquisition.CaptureDeviceDescriptor;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.Catalogue;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.Configuration;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.Preflight;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.ProfileView;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.RecordingView;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.RunView;
import org.hammer.audio.experiment.operations.MicrophoneExperimentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin HTTP projection over the same microphone operations used by the desktop. */
@RestController
@RequestMapping("/experiment-operations")
public final class ExperimentOperationsHttpAdapter {

  private final MicrophoneExperimentService service;

  /** Use the host's shared operations service. */
  public ExperimentOperationsHttpAdapter(MicrophoneExperimentService service) {
    this.service = service;
  }

  /** Read selectable real inputs and saved profiles. */
  @GetMapping("/catalogue")
  public Catalogue catalogue() throws IOException {
    return service.catalogue();
  }

  /** Read real capture descriptors for profile editors and local document bindings. */
  @GetMapping("/devices")
  public List<CaptureDeviceDescriptor> devices() {
    return service.devices();
  }

  /** Validate a complete profile before persisting it. */
  @PostMapping("/profiles")
  public ProfileView saveProfile(@RequestBody ProfileCommand command) throws IOException {
    return service.saveProfile(command.definition());
  }

  /** Show readiness before opening devices or output recordings. */
  @PostMapping("/preflight")
  public Preflight preflight(@RequestBody Configuration configuration) throws IOException {
    return service.preflight(configuration);
  }

  /** Run the exact preflighted configuration after a fresh server-side check. */
  @PostMapping("/start")
  public RunView start(@RequestBody StartCommand command) throws IOException {
    service.start(command.configuration(), command.token(), command.acknowledgeStorageWarning());
    return service.snapshot();
  }

  /** Read immutable current/final status without running disk probes. */
  @GetMapping("/status")
  public RunView status() {
    return service.snapshot();
  }

  /** Stop acquisition and finalize captured data, preserving an incomplete experiment state. */
  @PostMapping("/stop")
  public RunView stop() throws IOException {
    service.stop();
    return service.snapshot();
  }

  /** Acknowledge an allowed warning without exporting private authenticated account handles. */
  @PostMapping("/acknowledge")
  public RunView acknowledge(@RequestBody AcknowledgeCommand command, HttpServletRequest request)
      throws IOException {
    String operator =
        request.getUserPrincipal() == null
            ? "Unattributed local operator"
            : "Authenticated operator (identity withheld)";
    service.acknowledge(command.code(), operator);
    return service.snapshot();
  }

  /** Import recording bytes with bounded streaming and structural inspection. */
  @PostMapping(path = "/recordings", consumes = "application/octet-stream")
  public RecordingView importRecording(HttpServletRequest request) throws IOException {
    return service.importRecording(request.getInputStream());
  }

  /** Preserve an incomplete input and create a separate recoverable-block recording. */
  @PostMapping("/recover")
  public RecordingView recover(@RequestBody RecoverCommand command) throws IOException {
    return service.recoverRecording(command.recordingId());
  }

  /** Export actual artifacts and hashes; incomplete evidence remains explicitly labelled. */
  @GetMapping("/evidence")
  public void evidence(HttpServletResponse response) throws IOException {
    if (!service.snapshot().exportAvailable()) {
      throw new IllegalStateException("Finalize the experiment before exporting evidence.");
    }
    response.setContentType("application/zip");
    response.setHeader("Content-Disposition", "attachment; filename=\"experiment-evidence.zip\"");
    service.exportEvidence(response.getOutputStream());
  }

  /**
   * Report a concrete preflight or lifecycle reason without silently accepting a failed command.
   */
  @ExceptionHandler({
    IllegalArgumentException.class,
    IllegalStateException.class,
    IOException.class
  })
  public ResponseEntity<Map<String, String>> failure(Exception exception) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "code",
                "experiment-operation",
                "detail",
                exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage()));
  }

  /**
   * @param definition complete editable profile text
   */
  public record ProfileCommand(String definition) {
    // Immutable transport value.
  }

  /**
   * @param configuration reviewed experiment choices
   * @param token exact preflight configuration fingerprint
   * @param acknowledgeStorageWarning explicit advisory-margin acknowledgement
   */
  public record StartCommand(
      Configuration configuration, String token, boolean acknowledgeStorageWarning) {
    // Immutable transport value.
  }

  /**
   * @param code stable warning identifier
   */
  public record AcknowledgeCommand(String code) {
    // Immutable transport value.
  }

  /**
   * @param recordingId managed incomplete recording identity
   */
  public record RecoverCommand(String recordingId) {
    // Immutable transport value.
  }
}
