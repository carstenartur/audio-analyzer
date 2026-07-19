package org.hammer.audio.infrastructure.workflow.collaboration.schema;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.schema.SearchSchemaMigrations;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/** Applies shared Core and Search migrations before Audio Analyzer collaboration migrations. */
public final class WorkflowSchemaMigrator {

  private final DataSource dataSource;

  /** Creates an ordered migrator over the shared application data source. */
  public WorkflowSchemaMigrator(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  /**
   * Migrates with no legacy Search adoption.
   *
   * <p>This overload preserves the pre-Search application integration contract.
   */
  public WorkflowSchemaMigrationResult migrate(
      boolean adoptLegacyCoreSchema, boolean adoptPreLeaseCollaborationSchema) {
    return migrate(adoptLegacyCoreSchema, false, adoptPreLeaseCollaborationSchema);
  }

  /** Migrates generic Core, generic Search and application collaboration state in that order. */
  public WorkflowSchemaMigrationResult migrate(
      boolean adoptLegacyCoreSchema,
      boolean adoptLegacySearchSchema,
      boolean adoptPreLeaseCollaborationSchema) {
    DatabaseFamily family = detectDatabaseFamily();
    int core = migrateCore(family, adoptLegacyCoreSchema);
    int search = migrateSearch(family, adoptLegacySearchSchema);
    int collaboration = migrateCollaboration(family, adoptPreLeaseCollaborationSchema);
    return new WorkflowSchemaMigrationResult(true, core, search, collaboration);
  }

  private int migrateCore(DatabaseFamily family, boolean legacy) {
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(family.coreLocation)
            .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
            .baselineOnMigrate(true);
    baseline(
        configuration,
        legacy,
        CoreSchemaMigrations.LEGACY_SCHEMA_VERSION,
        CoreSchemaMigrations.LEGACY_BASELINE_DESCRIPTION,
        CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION,
        CoreSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION);
    return configuration.load().migrate().migrationsExecuted;
  }

  private int migrateSearch(DatabaseFamily family, boolean legacy) {
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(family.searchLocation)
            .table(SearchSchemaMigrations.SCHEMA_HISTORY_TABLE)
            .baselineOnMigrate(true);
    baseline(
        configuration,
        legacy,
        SearchSchemaMigrations.LEGACY_SCHEMA_VERSION,
        SearchSchemaMigrations.LEGACY_BASELINE_DESCRIPTION,
        SearchSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION,
        SearchSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION);
    return configuration.load().migrate().migrationsExecuted;
  }

  private int migrateCollaboration(DatabaseFamily family, boolean legacy) {
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(family.collaborationLocation)
            .table(CollaborationSchemaMigrations.SCHEMA_HISTORY_TABLE)
            .baselineOnMigrate(true);
    baseline(
        configuration,
        legacy,
        CollaborationSchemaMigrations.PRE_LEASE_SCHEMA_VERSION,
        CollaborationSchemaMigrations.PRE_LEASE_BASELINE_DESCRIPTION,
        CollaborationSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION,
        CollaborationSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION);
    return configuration.load().migrate().migrationsExecuted;
  }

  private static void baseline(
      FluentConfiguration configuration,
      boolean legacy,
      String legacyVersion,
      String legacyDescription,
      String initialVersion,
      String initialDescription) {
    if (legacy) {
      configuration.baselineVersion(legacyVersion).baselineDescription(legacyDescription);
    } else {
      configuration.baselineVersion(initialVersion).baselineDescription(initialDescription);
    }
  }

  private DatabaseFamily detectDatabaseFamily() {
    try (Connection connection = dataSource.getConnection()) {
      String product =
          connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
      if (product.contains("h2")) {
        return DatabaseFamily.H2;
      }
      if (product.contains("postgresql")) {
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
    H2(
        CoreSchemaMigrations.H2_LOCATION,
        SearchSchemaMigrations.H2_LOCATION,
        CollaborationSchemaMigrations.H2_LOCATION),
    POSTGRESQL(
        CoreSchemaMigrations.POSTGRESQL_LOCATION,
        SearchSchemaMigrations.POSTGRESQL_LOCATION,
        CollaborationSchemaMigrations.POSTGRESQL_LOCATION);

    private final String coreLocation;
    private final String searchLocation;
    private final String collaborationLocation;

    DatabaseFamily(String coreLocation, String searchLocation, String collaborationLocation) {
      this.coreLocation = coreLocation;
      this.searchLocation = searchLocation;
      this.collaborationLocation = collaborationLocation;
    }
  }
}
