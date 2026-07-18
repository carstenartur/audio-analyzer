# Hibernate-backed workflow persistence

The normal workbench starts in `memory` mode. Checkpoint, history, collaboration recovery and durable
outbox delivery require an explicit persistence mode.

## Shared persistence runtime

Audio Analyzer consumes the released migration-bearing storage library:

```text
io.github.carstenartur:jgit-storage-hibernate-core:0.1.5
Hibernate ORM 7.4.5.Final
Flyway 12.8.1
```

The application creates one Spring-managed `DataSource` and one application-owned Hibernate
`SessionFactory`. Shared JGit storage entities and Audio Analyzer collaboration/session/operation/
outbox entities use that same persistence context.

Starting `hibernate` mode without an explicit `spring.datasource.url` or
`spring.datasource.jndi-name` fails immediately. Versioned migrations never run against an implicit
embedded database.

## Schema ownership

The schema is split by owner and migration history:

|             Owner             |                               Tables                                |             Flyway history table              |
|-------------------------------|---------------------------------------------------------------------|-----------------------------------------------|
| `jgit-storage-hibernate-core` | packs, refs/reftables and reflogs                                   | `jgit_storage_hibernate_core_schema_history`  |
| Audio Analyzer                | collaboration session, accepted operations and transactional outbox | `audio_analyzer_collaboration_schema_history` |

Audio Analyzer does not copy generic JGit DDL. `WorkflowSchemaMigrator` loads migration resources
from the shared Core artifact and then applies the application-owned resources.

The deterministic startup order is:

```text
1. jgit-storage-hibernate Core migrations
2. Audio Analyzer collaboration migrations
3. Hibernate schema validation
4. Session recovery and outbox dispatch startup
```

Spring Boot's single default Flyway instance is disabled with `spring.flyway.enabled=false` because
combining both owners in one history table would destroy this boundary.

## Clean production provisioning

A clean H2 or PostgreSQL database can be provisioned at application startup by enabling the ordered
migration phase and keeping Hibernate in validation mode:

```properties
workbench.persistence.mode=hibernate
workbench.persistence.repository-name=audio-analyzer-workflows
workbench.persistence.migrations.enabled=true
workbench.persistence.schema-action=validate
spring.datasource.url=jdbc:postgresql://database.example/audio_analyzer
spring.datasource.driver-class-name=org.postgresql.Driver
spring.datasource.username=audio_analyzer
spring.datasource.password=${AUDIO_ANALYZER_DATABASE_PASSWORD}
```

Equivalent local H2 startup:

```bash
java -jar audio-app/target/audio-app-0.0.4-SNAPSHOT-workbench.jar \
  --workbench.persistence.mode=hibernate \
  --workbench.persistence.repository-name=audio-analyzer-workflows \
  --workbench.persistence.migrations.enabled=true \
  --workbench.persistence.schema-action=validate \
  --spring.datasource.url='jdbc:h2:file:./data/audio-analyzer;AUTO_SERVER=TRUE' \
  --spring.datasource.driver-class-name=org.h2.Driver \
  --spring.datasource.username=sa \
  --spring.datasource.password=
```

Migration execution is opt-in. With `workbench.persistence.migrations.enabled=false`, the deployment
must run the packaged Flyway resources externally before starting the application with `validate`.

`update`, `create` and `create-drop` are restricted to disposable development/test data. When the
versioned migration phase is enabled, startup rejects every schema action except `validate`.

## Adopting schemas created before versioned migrations

Two one-time flags exist for verified legacy schemas:

```properties
workbench.persistence.migrations.adopt-core-0.1.4=true
workbench.persistence.migrations.adopt-collaboration-pre-lease=true
```

The Core flag baselines a schema that exactly matches `jgit-storage-hibernate-core` 0.1.4. The
collaboration flag baselines the application schema at version 1, which represents the session,
operation and pre-lease outbox mappings from before issue #265. Flyway then applies version 2, adding:

- optimistic `entity_version`;
- `lease_owner` and `lease_token`;
- `lease_expires_at`;
- the lease-aware pending-event index.

Version 2 is additive apart from replacing the old pending-event index. Pending, failed and published
outbox rows are retained unchanged; new version values start at zero.

Use the adoption flags only with this procedure:

1. stop all writers and outbox dispatchers;
2. take and verify a restorable database backup;
3. compare the existing tables, columns, constraints and indexes with the immutable legacy fixtures
   under `audio-app/src/test/resources/db/legacy/`;
4. start exactly one migration instance with the required adoption flag(s) and `schema-action=validate`;
5. verify both Flyway history tables and application recovery;
6. remove the adoption flags before normal multi-instance startup.

Do not use baselining to make an unknown or partially modified schema appear current. Flyway records a
baseline instead of reconstructing missing historical changes.

## Upgrade and rollback expectations

Run migrations before rolling out application instances that require the new mappings. During a
multi-instance deployment, keep old writers stopped until the migration has completed and validation
has succeeded.

Database migrations are forward-moving. Application rollback must not assume that Flyway reverses
DDL. Keep the pre-upgrade backup until the new version has completed recovery and outbox dispatch
checks. If rollback requires the old physical schema, restore that backup rather than editing Flyway
history or manually dropping lease columns.

## Published-outbox retention

Automatic retention is disabled by default. Even after it is enabled, the default mode is a read-only
report so an operator can inspect the exact candidate set before any durable row is deleted:

```properties
workbench.collaboration.outbox.retention.enabled=true
workbench.collaboration.outbox.retention.mode=report
workbench.collaboration.outbox.retention.published-retention=P30D
workbench.collaboration.outbox.retention.batch-size=100
workbench.collaboration.outbox.retention.interval-ms=3600000
```

Each run captures one immutable logical time, computes an inclusive publication cutoff and reports:

- published rows scanned;
- eligible rows in stable `(publishedAt, eventId)` order;
- oldest and newest candidate publication times;
- deleted, skipped and failed counts.

Only rows satisfying all of these conditions are candidates:

1. publication completed and `published_at` is set;
2. `published_at` is at or before the immutable cutoff;
3. no lease owner, lease token or lease expiry remains;
4. the row is still identical and eligible when locked for deletion.

`mode=delete` is an explicit operational decision. The implementation re-reads each planned row under
`PESSIMISTIC_WRITE`, checks the session identity and original publication timestamp, and deletes it in
a bounded transaction only if it remains eligible. A competing cleanup that already removed the row,
or any changed/uncertain state, is reported as skipped. Repeating the same plan is therefore
idempotent.

Retention never deletes:

- pending, failed-due, leased or uncertain outbox rows;
- open or closed collaboration sessions;
- accepted operations or operation identifiers used for duplicate-command detection;
- Git checkpoints, refs, commits, reflogs or other Git history;
- search projections as a substitute for authoritative state.

The default published-row horizon is an operational diagnostic and idempotency safeguard, not a legal
retention recommendation. Choose a longer value where incident response, broker redelivery,
organizational audit or regulatory policy requires it. Verify a restorable backup and review several
report-only runs before enabling deletion. Closed-session cleanup and operation-history compaction are
separate future policies and remain disabled until recovery and command-idempotency guarantees are
proven by executable tests.

## Application-specific entity registration

Audio Analyzer modules register only their own ORM mappings through a
`WorkflowPersistenceEntityContributor` bean:

```java
@Bean
WorkflowPersistenceEntityContributor collaborationPersistenceEntities() {
  return CollaborationPersistenceEntities::annotatedClasses;
}
```

The resulting classes are added to the same application-managed `SessionFactory` as the generic JGit
storage entities. This extension point does not copy or replace any mapping from
`jgit-storage-hibernate-core`.

## GitHub Packages access

The shared artifact is published through GitHub Packages. Maven uses repository id `github`. Local
users need a settings entry with a token that has `read:packages`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_TOKEN</password>
    </server>
  </servers>
</settings>
```

Repository CI configures this server from `GITHUB_TOKEN` and requests `packages: read`.

## Explicit filesystem fallback

The old local bare-repository implementation remains available under an unambiguous mode and class
name:

```bash
java -jar audio-app/target/audio-app-0.0.4-SNAPSHOT-workbench.jar \
  --workbench.persistence.mode=filesystem \
  --workbench.persistence.filesystem.path=./data/workbench.git
```

This mode is for tests and local demonstrations. It is never selected by the production persistent
profile.

## Transaction boundary

`jgit-storage-hibernate-core` owns generic JGit storage. Audio Analyzer owns collaboration session,
operation, revision and outbox state.

One accepted live semantic operation and its outbox record share one Hibernate transaction. A Git
checkpoint is a separate application command based on an expected semantic revision. Cross-component
atomicity must not be claimed unless the shared storage library exposes and verifies a matching
generic transaction participation contract.
