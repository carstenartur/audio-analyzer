package org.hammer.audio.infrastructure.workflow.collaboration.schema;

import java.util.Objects;

/**
 * Outcome and startup invariant for ordered workflow schema migrations.
 *
 * @param applied whether versioned migrations were applied during this startup
 * @param coreMigrationsExecuted number of shared JGit Core migrations executed
 * @param searchMigrationsExecuted number of shared JGit Search migrations executed
 * @param semanticMigrationsExecuted number of Audio Analyzer semantic-search migrations executed
 * @param collaborationMigrationsExecuted number of Audio Analyzer collaboration migrations executed
 */
public record WorkflowSchemaMigrationResult(
    boolean applied,
    int coreMigrationsExecuted,
    int searchMigrationsExecuted,
    int semanticMigrationsExecuted,
    int collaborationMigrationsExecuted) {

  public WorkflowSchemaMigrationResult {
    requireNonNegative(coreMigrationsExecuted, "coreMigrationsExecuted");
    requireNonNegative(searchMigrationsExecuted, "searchMigrationsExecuted");
    requireNonNegative(semanticMigrationsExecuted, "semanticMigrationsExecuted");
    requireNonNegative(collaborationMigrationsExecuted, "collaborationMigrationsExecuted");
  }

  /** Preserves the migration-result contract that predates semantic projection storage. */
  public WorkflowSchemaMigrationResult(
      boolean applied,
      int coreMigrationsExecuted,
      int searchMigrationsExecuted,
      int collaborationMigrationsExecuted) {
    this(
        applied,
        coreMigrationsExecuted,
        searchMigrationsExecuted,
        0,
        collaborationMigrationsExecuted);
  }

  /** Returns a marker for an explicitly disabled migration phase. */
  public static WorkflowSchemaMigrationResult skipped() {
    return new WorkflowSchemaMigrationResult(false, 0, 0, 0, 0);
  }

  /** Prevents Hibernate DDL mutation after versioned migrations have run. */
  public void requireValidateSchemaAction(String schemaAction) {
    String requiredSchemaAction = Objects.requireNonNull(schemaAction, "schemaAction").trim();
    if (applied && !"validate".equalsIgnoreCase(requiredSchemaAction)) {
      throw new IllegalStateException(
          "Versioned workflow migrations require workbench.persistence.schema-action=validate");
    }
  }

  private static void requireNonNegative(int value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be >= 0");
    }
  }
}
