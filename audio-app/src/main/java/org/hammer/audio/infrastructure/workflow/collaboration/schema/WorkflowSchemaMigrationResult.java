package org.hammer.audio.infrastructure.workflow.collaboration.schema;

import java.util.Objects;

/** Outcome and startup invariant for ordered workflow schema migrations. */
public record WorkflowSchemaMigrationResult(
    boolean applied,
    int coreMigrationsExecuted,
    int searchMigrationsExecuted,
    int collaborationMigrationsExecuted) {

  public WorkflowSchemaMigrationResult {
    if (coreMigrationsExecuted < 0) {
      throw new IllegalArgumentException("coreMigrationsExecuted must be >= 0");
    }
    if (searchMigrationsExecuted < 0) {
      throw new IllegalArgumentException("searchMigrationsExecuted must be >= 0");
    }
    if (collaborationMigrationsExecuted < 0) {
      throw new IllegalArgumentException("collaborationMigrationsExecuted must be >= 0");
    }
  }

  /** Returns a marker for an explicitly disabled migration phase. */
  public static WorkflowSchemaMigrationResult skipped() {
    return new WorkflowSchemaMigrationResult(false, 0, 0, 0);
  }

  /** Prevents Hibernate DDL mutation after versioned migrations have run. */
  public void requireValidateSchemaAction(String schemaAction) {
    String requiredSchemaAction = Objects.requireNonNull(schemaAction, "schemaAction").trim();
    if (applied && !"validate".equalsIgnoreCase(requiredSchemaAction)) {
      throw new IllegalStateException(
          "Versioned workflow migrations require workbench.persistence.schema-action=validate");
    }
  }
}
