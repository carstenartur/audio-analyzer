package org.hammer.audio.acquisition.health;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable diagnostic with explicit freshness and remediation semantics.
 *
 * @param code stable diagnostic code
 * @param severity information, warning, blocker or error
 * @param message concise user-facing explanation
 * @param detail technical evidence
 * @param component affected component or document path
 * @param observedAt source observation time
 * @param maximumAgeSeconds acceptable age; zero means no expiry
 * @param remediationAction navigation or action identifier
 * @param acknowledgeable whether a warning can be acknowledged
 * @param acknowledged whether the operator acknowledged the latched finding
 */
public record ExperimentHealthFinding(
    String code,
    Severity severity,
    String message,
    String detail,
    String component,
    Instant observedAt,
    long maximumAgeSeconds,
    String remediationAction,
    boolean acknowledgeable,
    boolean acknowledged) {

  /** Severity never derives from display color or exception absence. */
  public enum Severity {
    INFO,
    WARNING,
    BLOCKING,
    ERROR
  }

  // Keep findings immutable and normalize optional diagnostic text.
  public ExperimentHealthFinding {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(component, "component");
    Objects.requireNonNull(observedAt, "observedAt");
    detail = detail == null ? "" : detail;
    remediationAction = remediationAction == null ? "" : remediationAction;
    if (severity == Severity.BLOCKING) {
      acknowledgeable = false;
      acknowledged = false;
    }
  }

  /** Whether this observation is older than its declared freshness window. */
  public boolean stale(Instant now) {
    return maximumAgeSeconds > 0L && now.isAfter(observedAt.plusSeconds(maximumAgeSeconds));
  }

  /** Acknowledge a warning without altering the source condition or permitting a blocker. */
  public ExperimentHealthFinding acknowledge() {
    if (!acknowledgeable || severity == Severity.BLOCKING) {
      throw new IllegalArgumentException("This finding must be fixed: " + code);
    }
    return new ExperimentHealthFinding(
        code,
        severity,
        message,
        detail,
        component,
        observedAt,
        maximumAgeSeconds,
        remediationAction,
        true,
        true);
  }
}
