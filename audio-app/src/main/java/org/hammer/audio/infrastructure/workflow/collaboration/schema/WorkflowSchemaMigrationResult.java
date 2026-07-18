package org.hammer.audio.infrastructure.workflow.collaboration.schema;

import java.util.Objects;

/**
 * Outcome and startup invariant for the ordered workflow schema migration phase.
 *
 * @param applied whether versioned migrations were applied during this startup
 * @param coreMigrationsExecuted number of shared JGit Core migrations executed
 * @param collaborationMigrationsExecuted number of Audio Analyzer collaboration migrations executed
 */
public record WorkflowSchemaMigrationResult(
    boolean applied, int coreMigrationsExecuted, int collaborationMigrationsExecuted) {

  public WorkflowSchemaMigrationResult {
    // Validate counters at the boundary before the result participates in startup ordering.
    if (coreMigrationsExecuted < 0) {
      throw new IllegalArgumentException("coreMigrationsExecuted must be >= 0");
    }
    if (collaborationMigrationsExecuted < 0) {
      throw new IllegalArgumentException("collaborationMigrationsExecuted must be >= 0");
    }
  }

  /** Returns a marker for an explicitly disabled migration phase. */
  public static WorkflowSchemaMigrationResult skipped() {
    return new WorkflowSchemaMigrationResult(false, 0, 0);
  }

  /** Prevents Hibernate DDL mutation after the versioned migration phase has run. */
  public void requireValidateSchemaAction(String schemaAction) {
    String requiredSchemaAction = Objects.requireNonNull(schemaAction, "schemaAction").trim();
    if (applied && !"validate".equalsIgnoreCase(requiredSchemaAction)) {
      throw new IllegalStateException(
          "Versioned workflow migrations require workbench.persistence.schema-action=validate");
    }
  }
}
