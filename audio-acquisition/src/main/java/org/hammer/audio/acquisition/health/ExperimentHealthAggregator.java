package org.hammer.audio.acquisition.health;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.acquisition.health.ExperimentHealthFinding.Severity;
import org.hammer.audio.acquisition.health.ExperimentHealthSnapshot.Event;

/** Thread-safe aggregator for typed component findings and immutable health projections. */
// One monitor protects the compound state/history projection; independent concurrent maps would
// not.
@SuppressWarnings({"PMD.UseConcurrentHashMap", "PMD.AvoidSynchronizedAtMethodLevel"})
public final class ExperimentHealthAggregator {

  private final Map<String, ExperimentHealthFinding> active = new HashMap<>();
  private final Map<String, ExperimentHealthFinding> latched = new HashMap<>();
  private final List<Event> history = new ArrayList<>();
  private ExperimentHealthState lastState;

  /** Replace one component's current observations while preserving significant alert history. */
  public synchronized void update(
      String component, List<ExperimentHealthFinding> findings, Instant now) {
    Objects.requireNonNull(component, "component");
    Map<String, ExperimentHealthFinding> previous = new HashMap<>(active);
    active.values().removeIf(finding -> finding.component().equals(component));
    for (ExperimentHealthFinding finding : findings) {
      ExperimentHealthFinding old = previous.get(finding.code());
      boolean changed = conditionChanged(old, finding);
      active.put(
          finding.code(),
          !changed && old.acknowledged() && finding.acknowledgeable()
              ? finding.acknowledge()
              : finding);
      if (finding.severity() == Severity.WARNING || finding.severity() == Severity.ERROR) {
        ExperimentHealthFinding priorAlert = latched.get(finding.code());
        if (changed || priorAlert == null) {
          latched.put(finding.code(), finding);
        }
      }
      if (changed && finding.severity() != Severity.INFO) {
        history.add(new Event(now, finding.code(), finding.severity().name(), finding.message()));
      }
    }
  }

  private static boolean conditionChanged(
      ExperimentHealthFinding old, ExperimentHealthFinding current) {
    return old == null
        || old.severity() != current.severity()
        || !old.message().equals(current.message());
  }

  /** Record explicit acknowledgement; an active blocker always requires remediation. */
  public synchronized void acknowledge(String code, String operator, Instant now) {
    ExperimentHealthFinding finding = active.getOrDefault(code, latched.get(code));
    if (finding == null) {
      throw new IllegalArgumentException("Unknown finding: " + code);
    }
    ExperimentHealthFinding acknowledged = finding.acknowledge();
    latched.put(code, acknowledged);
    if (active.containsKey(code)) {
      active.put(code, acknowledged);
    }
    history.add(
        new Event(
            now,
            code,
            "ACKNOWLEDGED",
            operator == null || operator.isBlank() ? "Unattributed operator" : operator));
  }

  /** Project freshness, blocking reasons and latched alerts over an explicit lifecycle decision. */
  public synchronized ExperimentHealthSnapshot snapshot(
      String id,
      String name,
      ExperimentHealthState requested,
      String stage,
      Map<String, String> facts,
      Instant now) {
    Map<String, ExperimentHealthFinding> displayed = new HashMap<>(latched);
    displayed.putAll(active);
    for (ExperimentHealthFinding finding : active.values()) {
      if (finding.stale(now)) {
        displayed.put(
            finding.code() + ".stale",
            new ExperimentHealthFinding(
                finding.code() + ".stale",
                Severity.WARNING,
                "Stale: " + finding.message(),
                "Last successful observation: " + finding.observedAt(),
                finding.component(),
                finding.observedAt(),
                0L,
                finding.remediationAction(),
                false,
                false));
      }
    }
    List<ExperimentHealthFinding> findings =
        displayed.values().stream()
            .sorted(Comparator.comparing(ExperimentHealthFinding::code))
            .toList();
    ExperimentHealthState state = resolveState(requested, findings, facts);
    if (state != lastState) {
      history.add(new Event(now, "experiment.state", state.name(), stage));
      lastState = state;
    }
    return new ExperimentHealthSnapshot(id, name, state, stage, now, findings, facts, history);
  }

  private static ExperimentHealthState resolveState(
      ExperimentHealthState requested,
      List<ExperimentHealthFinding> findings,
      Map<String, String> facts) {
    boolean blocked =
        findings.stream().anyMatch(finding -> finding.severity() == Severity.BLOCKING);
    boolean warned =
        findings.stream()
            .anyMatch(
                finding ->
                    !finding.acknowledged()
                        && finding.severity() != Severity.INFO
                        && finding.severity() != Severity.BLOCKING);
    if (requested == ExperimentHealthState.READY
        || requested == ExperimentHealthState.READY_WITH_WARNINGS) {
      if (blocked) {
        return ExperimentHealthState.BLOCKED;
      }
      return warned ? ExperimentHealthState.READY_WITH_WARNINGS : requested;
    }
    if (requested == ExperimentHealthState.RUNNING && (blocked || warned)) {
      return ExperimentHealthState.DEGRADED;
    }
    if (requested == ExperimentHealthState.COMPLETED
        && "false".equals(facts.get("evidence.complete"))) {
      return ExperimentHealthState.INCOMPLETE;
    }
    return requested;
  }
}
