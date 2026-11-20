package org.hammer.audio.experiment.document;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.hammer.audio.plugin.document.DocumentDiagnostic;
import org.hammer.audio.plugin.document.DocumentValue;

/** Pure capability/mapping/calibration checks against a deliberately selected local device. */
final class ExperimentCaptureReadiness {
  private ExperimentCaptureReadiness() {
    // Utility class.
  }

  static void inspect(
      ExperimentDocument document,
      ExperimentCaptureBinding binding,
      Instant now,
      List<DocumentDiagnostic> diagnostics) {
    if ("unbound".equals(document.experiment().sourceMode())) {
      diagnostics.add(
          error(
              "/experiment/sourceMode",
              "source-binding-missing",
              "Configure explicit source requirements before execution"));
    }
    inspectCapture(document, binding, diagnostics);
    inspectCalibration(document, binding, now, diagnostics);
  }

  private static void inspectCapture(
      ExperimentDocument document,
      ExperimentCaptureBinding binding,
      List<DocumentDiagnostic> diagnostics) {
    DocumentValue captureValue = document.profiles().fields().get("capture");
    boolean hardware =
        Set.of("live", "capture", "hardware", "microphone")
            .contains(document.experiment().sourceMode());
    if (!(captureValue instanceof DocumentValue.ObjectValue capture)) {
      if (hardware) {
        diagnostics.add(
            error(
                "/profiles/capture",
                "capture-profile-missing",
                "Declare explicit PCM and device requirements before live capture"));
      }
      return;
    }
    if (binding == null) {
      if (hardware) {
        diagnostics.add(
            error(
                "/profiles/capture",
                "capture-binding-missing",
                "Select a device and confirm its format and channel mapping"));
      }
      return;
    }
    inspectCapabilities(capture, binding, diagnostics);
  }

  private static void inspectCapabilities(
      DocumentValue.ObjectValue capture,
      ExperimentCaptureBinding binding,
      List<DocumentDiagnostic> diagnostics) {
    int channels = number(capture, "channels").intValueExact();
    DocumentValue.ObjectValue requirements =
        (DocumentValue.ObjectValue) capture.fields().get("deviceRequirements");
    boolean matches =
        Double.compare(binding.sampleRateHz(), number(capture, "sampleRateHz").doubleValue()) == 0
            && binding.channels() >= channels
            && binding.sampleSizeBits() == number(capture, "sampleSizeBits").intValueExact()
            && binding.signed() == bool(capture, "signed")
            && binding.bigEndian() == bool(capture, "bigEndian")
            && (!bool(requirements, "synchronizedChannels") || binding.synchronizedChannels());
    if (!matches) {
      diagnostics.add(
          error(
              "/profiles/capture",
              "capture-capability-mismatch",
              "Selected device capabilities do not satisfy the portable capture requirements"));
    }
    if (binding.channelMapping().size() != channels) {
      diagnostics.add(
          error(
              "/profiles/microphoneArray",
              "capture-channel-mapping",
              "Explicitly map every logical channel to one physical channel"));
    }
  }

  private static void inspectCalibration(
      ExperimentDocument document,
      ExperimentCaptureBinding binding,
      Instant now,
      List<DocumentDiagnostic> diagnostics) {
    DocumentValue calibrationValue = document.profiles().fields().get("calibration");
    if (calibrationValue instanceof DocumentValue.ObjectValue calibration) {
      Instant expires =
          Instant.parse(
              ((DocumentValue.StringValue) calibration.fields().get("validUntil")).value());
      if (!now.isBefore(expires)) {
        diagnostics.add(
            error(
                "/profiles/calibration/validUntil",
                "calibration-expired",
                "Calibration has expired; inspect it or provide a newly calibrated setup"));
      }
      if (binding != null && !binding.calibrationConfirmed()) {
        diagnostics.add(
            error(
                "/profiles/calibration",
                "calibration-confirmation-required",
                "Confirm this calibration applies to the selected device and channel mapping"));
      }
    }
  }

  private static java.math.BigDecimal number(DocumentValue.ObjectValue object, String field) {
    return ((DocumentValue.NumberValue) object.fields().get(field)).value();
  }

  private static boolean bool(DocumentValue.ObjectValue object, String field) {
    return ((DocumentValue.BooleanValue) object.fields().get(field)).value();
  }

  private static DocumentDiagnostic error(String pointer, String code, String message) {
    return new DocumentDiagnostic(DocumentDiagnostic.Severity.ERROR, pointer, code, message);
  }
}
