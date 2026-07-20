package org.hammer.audio.infrastructure.workflow.search;

/** Stable classpath contract for Audio Analyzer-owned semantic search migrations. */
public final class WorkflowSemanticSchemaMigrations {

  /** Flyway location for H2 migrations. */
  public static final String H2_LOCATION =
      "classpath:db/migration/audio-analyzer/workflow-semantic/h2";

  /** Flyway location for PostgreSQL migrations. */
  public static final String POSTGRESQL_LOCATION =
      "classpath:db/migration/audio-analyzer/workflow-semantic/postgresql";

  /** Dedicated Flyway history table for the disposable semantic projection schema. */
  public static final String SCHEMA_HISTORY_TABLE =
      "audio_analyzer_workflow_semantic_schema_history";

  /** Baseline used before installing semantic projection storage. */
  public static final String PRE_MIGRATION_BASELINE_VERSION = "0";

  /** Description for the pre-migration baseline. */
  public static final String PRE_MIGRATION_BASELINE_DESCRIPTION =
      "before Audio Analyzer workflow semantic migrations";

  /** Current application semantic projection schema version. */
  public static final String CURRENT_SCHEMA_VERSION = "1";

  private WorkflowSemanticSchemaMigrations() {
    throw new AssertionError("No instances");
  }
}
