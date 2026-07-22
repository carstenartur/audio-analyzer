package org.hammer.audio.experimental.acoustic.workbench;

import java.util.Objects;
import org.hammer.audio.acquisition.LocalizationExperiment;
import org.hammer.audio.acquisition.LocalizationExperimentCodec;

/** Exports the reproducibility and hardware context associated with a workbench result. */
public final class WorkbenchExperimentExporter {

  private WorkbenchExperimentExporter() {
    // utility class
  }

  /** Return the deterministic machine-readable experiment manifest. */
  public static String toManifest(WorkbenchRunResult result) {
    return new LocalizationExperimentCodec().encode(experiment(result));
  }

  /** Return a compact human-readable experiment and hardware summary. */
  public static String toMarkdown(WorkbenchRunResult result) {
    LocalizationExperiment experiment = experiment(result);
    StringBuilder output =
        new StringBuilder("# Localization Experiment\n\n| Field | Value |\n|---|---|\n");
    appendRow(output, "Experiment", experiment.experimentId());
    appendRow(output, "Stage", experiment.stage());
    appendRow(output, "Input mode", experiment.inputMode());
    appendRow(output, "Source", experiment.sourceReference());
    appendRow(output, "Profile", experiment.profile().profileId());
    appendRow(output, "Layout", experiment.profile().layout());
    appendRow(output, "Microphones", experiment.profile().array().channels());
    experiment
        .profile()
        .liveCaptureConfiguration()
        .ifPresent(configuration -> appendRow(output, "Capture device", configuration.device().deviceId()));
    experiment
        .profile()
        .calibrationProfile()
        .ifPresent(calibration -> appendRow(output, "Calibration", calibration.profileId()));
    for (var entry : experiment.metadata().entrySet()) {
      appendRow(output, "Metadata: " + entry.getKey(), entry.getValue());
    }
    return output.toString();
  }

  private static LocalizationExperiment experiment(WorkbenchRunResult result) {
    Objects.requireNonNull(result, "result");
    return result
        .experimentMetadata()
        .orElseThrow(() -> new IllegalArgumentException("workbench result has no experiment metadata"));
  }

  private static void appendRow(StringBuilder output, String field, Object value) {
    output.append("| ").append(field).append(" | ").append(value).append(" |\n");
  }
}
