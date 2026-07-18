package org.hammer.audio.workflow.collaboration.retention;

import java.util.Locale;

/** Operational mode for scheduled outbox retention. */
public enum WorkflowOutboxRetentionMode {
  /** Report eligible rows without deleting durable data. */
  REPORT_ONLY,

  /** Revalidate and delete only eligible published rows. */
  DELETE;

  /** Parses a configuration value without accepting ambiguous destructive defaults. */
  public static WorkflowOutboxRetentionMode parse(String value) {
    if (value == null || value.isBlank()) {
      return REPORT_ONLY;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "report", "report-only", "dry-run" -> REPORT_ONLY;
      case "delete" -> DELETE;
      default -> throw new IllegalArgumentException("Unknown retention mode: " + value);
    };
  }
}
