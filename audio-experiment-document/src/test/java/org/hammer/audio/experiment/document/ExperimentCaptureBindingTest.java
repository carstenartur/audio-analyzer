package org.hammer.audio.experiment.document;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExperimentCaptureBindingTest {
  @Test
  void capabilityMismatchAndUnconfirmedChannelMappingBlockReadiness() throws Exception {
    var preview =
        new ExperimentDocumentService(List.of())
            .preview(ExperimentDocumentSecurityTest.fixture("full.audioexp"));
    var device =
        new ExperimentCaptureBinding(
            "local-device", 44100, 1, 16, true, false, false, List.of(0), false);
    var result =
        new ExperimentLocalBindingService()
            .inspect(preview, new ExperimentLocalBindings(Map.of(), null, device, Map.of()));
    assertFalse(result.ready());
    assertTrue(
        result.diagnostics().stream()
            .anyMatch(item -> item.code().equals("capture-capability-mismatch")));
    assertTrue(
        result.diagnostics().stream()
            .anyMatch(item -> item.code().equals("calibration-confirmation-required")));
  }

  @Test
  void liveSetupWithoutPortableCaptureRequirementsCannotClaimReadiness() throws Exception {
    ExperimentDocument base =
        new ExperimentDocumentCodec()
            .load(ExperimentDocumentSecurityTest.fixture("minimal.audioexp"));
    ExperimentDocument.ExperimentInfo info = base.experiment();
    ExperimentDocument live =
        new ExperimentDocument(
            base.schema(),
            base.format(),
            base.formatVersion(),
            new ExperimentDocument.ExperimentInfo(
                info.id(),
                info.name(),
                info.description(),
                info.tags(),
                info.intent(),
                "live",
                info.intendedDuration(),
                info.applicationVersion()),
            base.workflow(),
            base.profiles(),
            base.requiredPlugins(),
            base.pluginData(),
            base.assets(),
            base.outputs(),
            base.provenance());
    java.util.List<org.hammer.audio.plugin.document.DocumentDiagnostic> diagnostics =
        new java.util.ArrayList<>();
    ExperimentCaptureReadiness.inspect(
        live, null, Instant.parse("2026-11-20T12:00:00Z"), diagnostics);
    assertTrue(
        diagnostics.stream().anyMatch(item -> item.code().equals("capture-profile-missing")));
  }

  @Test
  void expiredCalibrationIsVisibleEvenWithoutHardware() throws Exception {
    var preview =
        new ExperimentDocumentService(List.of())
            .preview(ExperimentDocumentSecurityTest.fixture("full.audioexp"));
    var service =
        new ExperimentLocalBindingService(
            Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneOffset.UTC));
    var result = service.inspect(preview, new ExperimentLocalBindings(Map.of(), null));
    assertFalse(result.ready());
    assertTrue(
        result.diagnostics().stream().anyMatch(item -> item.code().equals("calibration-expired")));
  }
}
