package org.hammer.audio.acquisition.health;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * UI-independent status consumed unchanged by desktop and browser projections.
 *
 * @param experimentId current experiment identity
 * @param experimentName display name
 * @param state overall readiness or lifecycle
 * @param stage current experiment stage
 * @param observedAt snapshot timestamp
 * @param findings current and latched diagnostic evidence
 * @param facts stable keys for source, storage, processing and provenance measurements
 * @param history significant transitions and operator acknowledgements
 */
public record ExperimentHealthSnapshot(
    String experimentId,
    String experimentName,
    ExperimentHealthState state,
    String stage,
    Instant observedAt,
    List<ExperimentHealthFinding> findings,
    Map<String, String> facts,
    List<Event> history) {

  // A UI cannot mutate the application service through its snapshot.
  public ExperimentHealthSnapshot {
    findings = List.copyOf(findings);
    facts = Map.copyOf(facts);
    history = List.copyOf(history);
  }

  /** Start is permitted only by an explicit ready decision without active blockers. */
  public boolean startAllowed() {
    return (state == ExperimentHealthState.READY
            || state == ExperimentHealthState.READY_WITH_WARNINGS)
        && findings.stream()
            .noneMatch(finding -> finding.severity() == ExperimentHealthFinding.Severity.BLOCKING);
  }

  /**
   * Durable significant event rather than one event per refreshed frame.
   *
   * @param observedAt transition time
   * @param code stable finding or lifecycle code
   * @param action transition or acknowledgement
   * @param detail message including operator attribution where supplied
   */
  public record Event(Instant observedAt, String code, String action, String detail) {
    // Immutable transport value.
  }
}
