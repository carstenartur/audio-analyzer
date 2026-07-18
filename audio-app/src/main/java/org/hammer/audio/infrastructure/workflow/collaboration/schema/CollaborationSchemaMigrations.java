package org.hammer.audio.infrastructure.workflow.collaboration.schema;

/** Stable classpath contract for Audio Analyzer-owned collaboration schema migrations. */
public final class CollaborationSchemaMigrations {

  /** Flyway location for H2 migrations. */
  public static final String H2_LOCATION =
      "classpath:db/migration/audio-analyzer/collaboration/h2";

  /** Flyway location for PostgreSQL migrations. */
  public static final String POSTGRESQL_LOCATION =
      "classpath:db/migration/audio-analyzer/collaboration/postgresql";

  /** Dedicated Flyway history table for application-owned collaboration state. */
  public static final String SCHEMA_HISTORY_TABLE =
      "audio_analyzer_collaboration_schema_history";

  /** Baseline used when installing collaboration storage into a new or shared schema. */
  public static final String PRE_MIGRATION_BASELINE_VERSION = "0";

  /** Description for the pre-migration baseline. */
  public static final String PRE_MIGRATION_BASELINE_DESCRIPTION =
      "before Audio Analyzer collaboration migrations";

  /** Schema version produced before leased outbox dispatch was introduced. */
  public static final String PRE_LEASE_SCHEMA_VERSION = "1";

  /** Description used when adopting a verified pre-lease collaboration schema. */
  public static final String PRE_LEASE_BASELINE_DESCRIPTION =
      "Audio Analyzer collaboration schema before issue 265";

  /** Current collaboration schema version. */
  public static final String CURRENT_SCHEMA_VERSION = "2";

  private CollaborationSchemaMigrations() {
    throw new AssertionError("No instances");
  }
}
