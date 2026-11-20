# Hibernate-backed workflow persistence

The normal workbench starts in `memory` mode. Checkpoint, history, collaboration recovery and durable
outbox delivery require an explicit persistence mode.

## Shared persistence runtime

Maven resolves the Core and Search modules from Maven Central with its default settings.
The root `pom.xml` selects the shared storage, JGit and Hibernate ORM versions.
`audio-app/pom.xml` selects Flyway.

The application intentionally selects both `jgit-storage-hibernate-core` and
`jgit-storage-hibernate-search`. Search is an explicit application dependency, not an accidental
transitive of Core. H2 and PostgreSQL are the selected database drivers; the Spring Boot runtime
and Hibernate ORM are also selected by the application.

The executable consumer contract can be invoked from any working directory:

```bash
JGIT_STORAGE_HIBERNATE_CONTRACT_MODE=baseline \
  /path/to/audio-analyzer/.github/jgit-storage-hibernate-contract.sh
```

For a library candidate, first substitute its version into the consuming POM declarations, then
set `JGIT_STORAGE_HIBERNATE_CONTRACT_MODE=candidate` and
`JGIT_STORAGE_HIBERNATE_CANDIDATE_VERSION` before invoking the same script. Every declared storage
module must resolve to that candidate; one matching artifact cannot hide another older module.

The contract verifies and installs the application reactor with Java 21, runs the normal unit-test
selection without the PostgreSQL container scenarios, and skips the separate Failsafe integration
phase. It saves the runtime dependency tree plus the complete resolved artifact list, and compares
every resolved runtime coordinate against the reviewed application-owned list
in `.github/jgit-storage-hibernate-runtime-allowlist.txt`. Intentional runtime additions require
updating that list; dependency version changes do not. Benchmark and test artifacts remain forbidden
even if mistakenly added to the list. The archive check also inspects nested JARs and rejects packaged
libraries absent from the resolved runtime. Spring Boot's own version-matched jarmode helper is the
explicit packaging addition.

Finally, the contract starts the packaged workbench with a new temporary H2 database, runs the
released migrations and Hibernate validation, saves a real workflow checkpoint, reads its history
and reloads it without changing the projection. It uses no external database or credentials.
Dependency evidence, package names, runtime logs, the saved checkpoint/history/projection and
machine-readable results remain in
`target/jgit-storage-hibernate-contract/`. The normal Maven build retains its existing test selection.

```text
io.github.carstenartur:jgit-storage-hibernate-core:0.12.2
io.github.carstenartur:jgit-storage-hibernate-search:0.12.2
JGit 7.8.1.202611041320-r
Hibernate ORM 7.4.14.Final
Flyway 13.8.0
```

The application creates one Spring-managed `DataSource` and one application-owned Hibernate
`SessionFactory`. Shared JGit storage entities and Audio Analyzer collaboration/session/operation/
outbox entities use that same persistence context.

Starting `hibernate` mode without an explicit `spring.datasource.url` or
`spring.datasource.jndi-name` fails immediately. Versioned migrations never run against an implicit
embedded database.

## Schema ownership

The schema is split by owner and migration history:

|              Owner              |                               Tables                                |               Flyway history table                |
|---------------------------------|---------------------------------------------------------------------|---------------------------------------------------|
| `jgit-storage-hibernate-core`   | packs, refs/reftables and reflogs                                   | `jgit_storage_hibernate_core_schema_history`      |
| `jgit-storage-hibernate-search` | generic search projection and indexing metadata                     | `jgit_storage_hibernate_search_schema_history`    |
| Audio Analyzer semantic search  | workflow-aware search projection                                    | `audio_analyzer_workflow_semantic_schema_history` |
| Audio Analyzer                  | collaboration session, accepted operations and transactional outbox | `audio_analyzer_collaboration_schema_history`     |

Audio Analyzer does not copy generic JGit DDL. `WorkflowSchemaMigrator` loads migration resources
from the shared Core and Search artifacts, then applies the application-owned semantic-search and
collaboration resources. Each owner keeps its own Flyway history.

The deterministic startup order is:

```text
1. jgit-storage-hibernate Core migrations
2. jgit-storage-hibernate generic Search migrations
3. Audio Analyzer semantic Search migrations
4. Audio Analyzer collaboration migrations
5. Hibernate schema validation
6. Session recovery and outbox dispatch startup
```

Spring Boot's single default Flyway instance is disabled with `spring.flyway.enabled=false` because
combining these four owners in one history table would destroy this boundary.

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
java -jar audio-app/target/audio-app-*-workbench.jar \
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
must run all four sets of packaged Flyway resources in the order above, with their individual
history-table names, before starting the application with `validate`. Use the H2 or PostgreSQL
locations selected by `WorkflowSchemaMigrator`; applying only Core and collaboration leaves the
search schemas incomplete.

For a first local test, create the `data` directory before running the H2 command. Open
`http://localhost:8080/workbench`, stay outside a live collaboration session, enter a checkpoint
message and choose **Save checkpoint**. **Refresh history** should show the new commit. Stop the
server cleanly and restart with the same URL, repository name and data directory; the commit must
still be available. Follow the [history walkthrough](features/workflow-history-search.md) for search,
exact loading, comparison and restore. Live collaboration history is a separate persisted context;
the checkpoint button does not save an attached session graph.

`update`, `create` and `create-drop` are restricted to disposable development/test data. When the
versioned migration phase is enabled, startup rejects every schema action except `validate`.

## Adopting schemas created before versioned migrations

Three one-time flags exist for verified legacy schemas:

```properties
workbench.persistence.migrations.adopt-core-0.1.4=true
workbench.persistence.migrations.adopt-search-0.1.4=true
workbench.persistence.migrations.adopt-collaboration-pre-lease=true
```

Enable the Core and Search flags only after independently verifying that their respective
shared-library schemas match the legacy 0.1.4 contracts. The flags select a baseline version; they do
not themselves prove that the existing schema matches that historical contract. Search adoption is independent of Core adoption. Application
semantic search has its own version-zero baseline and no legacy-adoption flag. The collaboration
flag baselines the application schema at version 1, which represents the session,
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
3. compare the existing tables, columns, constraints and indexes with the appropriate legacy contract:
   use the collaboration fixtures under `audio-app/src/test/resources/db/legacy/` for the pre-lease
   application schema, and the legacy contracts and migration resources in the respective Core and
   Search library artifacts for their 0.1.4 schemas;
4. start exactly one migration instance with the required adoption flag(s) and `schema-action=validate`;
5. verify all four Flyway history tables and application recovery;
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

## Explicit filesystem fallback

The old local bare-repository implementation remains available under an unambiguous mode and class
name:

```bash
java -jar audio-app/target/audio-app-0.1.0-SNAPSHOT-workbench.jar \
  --workbench.persistence.mode=filesystem \
  --workbench.persistence.filesystem.path=./data/workbench.git
```

This mode is for tests and local demonstrations. It is never selected by the production persistent
profile.

## Executable restart evidence

The opt-in `WorkbenchDurableRestartIT` scenario starts two independent packaged Spring Boot
processes against the same mounted file-backed H2 database. Both processes run the released
storage migrations followed by Hibernate `validate`.

The scenario proves recovery of collaboration revision, operation identity and canonical
projection; publication of a previously pending outbox row with the same stable event id; and
exact load of two JGit checkpoints after restart. See
[Durable collaboration restart evidence](durable-restart-e2e.md) for the complete topology,
synchronization discipline and verified boundaries.

Collaboration state and versioned workflow checkpoints remain separate application contexts.
The restart test proves that both survive the same physical process boundary; it does not claim
that a live-session append automatically creates a Git commit or that both commands share one
cross-component transaction.

## Transaction boundary

`jgit-storage-hibernate-core` owns generic JGit storage. Audio Analyzer owns collaboration session,
operation, revision and outbox state.

One accepted live semantic operation and its outbox record share one Hibernate transaction. A Git
checkpoint is a separate application command based on an expected semantic revision. Cross-component
atomicity must not be claimed unless the shared storage library exposes and verifies a matching
generic transaction participation contract.
