package org.hammer.audio.infrastructure.workflow.collaboration.schema;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/** Applies shared JGit Core migrations before Audio Analyzer collaboration migrations. */
public final class WorkflowSchemaMigrator {

  private final DataSource dataSource;

  /** Creates an ordered migrator over the application-owned shared data source. */
  public WorkflowSchemaMigrator(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  /**
   * Migrates generic Core storage first and application collaboration state second.
   *
   * @param adoptLegacyCoreSchema whether a verified Core 0.1.4 schema should be baselined
   * @param adoptPreLeaseCollaborationSchema whether a verified pre-lease application schema should
   *     be baselined
   * @return migration execution summary
   */
  public WorkflowSchemaMigrationResult migrate(
      boolean adoptLegacyCoreSchema, boolean adoptPreLeaseCollaborationSchema) {
    DatabaseFamily databaseFamily = detectDatabaseFamily();
    int coreMigrations = migrateCore(databaseFamily, adoptLegacyCoreSchema);
    int collaborationMigrations =
        migrateCollaboration(databaseFamily, adoptPreLeaseCollaborationSchema);
    return new WorkflowSchemaMigrationResult(true, coreMigrations, collaborationMigrations);
  }

  private int migrateCore(DatabaseFamily databaseFamily, boolean adoptLegacySchema) {
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(databaseFamily.coreLocation)
            .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
            .baselineOnMigrate(true);
    if (adoptLegacySchema) {
      configuration
          .baselineVersion(CoreSchemaMigrations.LEGACY_SCHEMA_VERSION)
          .baselineDescription(CoreSchemaMigrations.LEGACY_BASELINE_DESCRIPTION);
    } else {
      configuration
          .baselineVersion(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
          .baselineDescription(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION);
    }
    return configuration.load().migrate().migrationsExecuted;
  }

  private int migrateCollaboration(
      DatabaseFamily databaseFamily, boolean adoptPreLeaseCollaborationSchema) {
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(databaseFamily.collaborationLocation)
            .table(CollaborationSchemaMigrations.SCHEMA_HISTORY_TABLE)
            .baselineOnMigrate(true);
    if (adoptPreLeaseCollaborationSchema) {
      configuration
          .baselineVersion(CollaborationSchemaMigrations.PRE_LEASE_SCHEMA_VERSION)
          .baselineDescription(CollaborationSchemaMigrations.PRE_LEASE_BASELINE_DESCRIPTION);
    } else {
      configuration
          .baselineVersion(CollaborationSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
          .baselineDescription(CollaborationSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION);
    }
    return configuration.load().migrate().migrationsExecuted;
  }

  private DatabaseFamily detectDatabaseFamily() {
    try (Connection connection = dataSource.getConnection()) {
      String productName =
          connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
      if (productName.contains("h2")) {
        return DatabaseFamily.H2;
      }
      if (productName.contains("postgresql")) {
        return DatabaseFamily.POSTGRESQL;
      }
      throw new IllegalStateException(
          "Unsupported workflow migration database: "
              + connection.getMetaData().getDatabaseProductName());
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot inspect workflow migration database", failure);
    }
  }

  private enum DatabaseFamily {
    H2(CoreSchemaMigrations.H2_LOCATION, CollaborationSchemaMigrations.H2_LOCATION),
    POSTGRESQL(
        CoreSchemaMigrations.POSTGRESQL_LOCATION,
        CollaborationSchemaMigrations.POSTGRESQL_LOCATION);

    private final String coreLocation;
    private final String collaborationLocation;

    DatabaseFamily(String coreLocation, String collaborationLocation) {
      this.coreLocation = coreLocation;
      this.collaborationLocation = collaborationLocation;
    }
  }
}
