package org.hammer;

import java.util.TreeMap;
import org.hammer.audio.acquisition.health.ExperimentHealthSnapshot;

/** Plain-text projection of every shared health field for Swing and copyable diagnostics. */
public final class ExperimentHealthText {
  private ExperimentHealthText() {
    // Shared formatting utility.
  }

  /** Preserve root causes, source timestamps, provenance facts and significant event history. */
  public static String describe(ExperimentHealthSnapshot snapshot) {
    StringBuilder text =
        new StringBuilder(512)
            .append(snapshot.state())
            .append(" — ")
            .append(snapshot.experimentName())
            .append('\n')
            .append(snapshot.stage())
            .append("\nObserved: ")
            .append(snapshot.observedAt())
            .append("\n\n");
    snapshot
        .findings()
        .forEach(
            finding ->
                text.append(finding.severity())
                    .append(": ")
                    .append(finding.message())
                    .append('\n')
                    .append(finding.detail())
                    .append("\nComponent: ")
                    .append(finding.component())
                    .append("\nCode: ")
                    .append(finding.code())
                    .append("\nObserved: ")
                    .append(finding.observedAt())
                    .append("\nAcknowledged: ")
                    .append(finding.acknowledged())
                    .append("\n\n"));
    new TreeMap<>(snapshot.facts())
        .forEach((key, value) -> text.append(key).append(": ").append(value).append('\n'));
    text.append("\nSignificant history:\n");
    snapshot
        .history()
        .forEach(
            event ->
                text.append(event.observedAt())
                    .append(" · ")
                    .append(event.action())
                    .append(" · ")
                    .append(event.detail())
                    .append('\n'));
    return text.toString();
  }
}
