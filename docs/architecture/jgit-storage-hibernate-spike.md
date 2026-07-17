# Spike: JGit/Hibernate storage consolidation

Status: Completed — external module released and consumed
Related ADR: `docs/architecture/adr-006-versioned-collaborative-workflow-store.md`
Implementation issue: #240

## Outcome

The database-backed JGit implementation lives in the independent
`carstenartur/jgit-storage-hibernate` project. Audio Analyzer does not copy its pack, ref,
reftable or reflog mappings and does not import JGit implementation internals.

The verified integration line is:

```text
jgit-storage-hibernate-core 0.1.4
Hibernate ORM 7.4.5.Final
JGit 7.7.0.202606012155-r
Java 21
```

`jgit-storage-hibernate-core` accepts an application-managed Hibernate `SessionFactory` through
`DefaultHibernateRepositoryFactory`. The same persistence context can contain Audio Analyzer
entities, so later collaboration sessions, operations and outbox rows do not require a second
Hibernate bootstrap.

## Boundary

```text
Audio Analyzer workflow domain
        ↓ VersionedWorkflowStore
HibernateJGitVersionedWorkflowStore
        ↓ public Hibernate storage facade + public JGit Repository API
jgit-storage-hibernate-core
        ↓ encapsulated DFS/Reftable implementation
Hibernate ORM / relational database
```

Hard rules:

- production Audio Analyzer code does not import `org.eclipse.jgit.internal.*`;
- JGit packs, refs, reftables and reflogs are owned only by the shared storage library;
- workflow snapshots use the stable Git paths `workflow.dsl` and `workflow.id`;
- Git commits are durable checkpoints, not a commit per UI gesture;
- live operation history and the transactional outbox remain application-owned durable facts;
- reflog is not the collaboration audit log;
- large recordings remain external assets referenced from versioned metadata.

## Implemented Audio Analyzer adapters

- `HibernateJGitVersionedWorkflowStore` is the production adapter. It opens a logical repository
  through `DefaultHibernateRepositoryFactory(SessionFactory)`.
- `FileSystemJGitVersionedWorkflowStore` is explicitly named and available only when the
  `filesystem` mode is selected for tests or local demonstrations.
- `JGitRepositoryVersionedWorkflowStore` contains the workflow-specific commit/load/history logic
  over an already-opened public JGit `Repository`.

This split prevents lifecycle and backend selection from leaking into workflow-facing APIs.

## Persistence modes

```text
memory      no checkpoint store; default
hibernate   production/development database-backed JGit repository
filesystem  explicit local/test bare-repository fallback
```

The detailed startup contract is documented in `docs/workbench-hibernate-persistence.md`.

## Verification

The storage library release includes a consumer integration test proving that:

1. an application-specific entity and the JGit storage entities share one `SessionFactory`;
2. a Git commit and application row are written;
3. the persistence context is closed and rebuilt against the same database;
4. both the application row and Git ref/commit remain readable.

Audio Analyzer adds its own restart integration test through the `VersionedWorkflowStore` facade,
covering current HEAD, historical commit and history ordering.

## Risk classification

```text
NO_AUDIO_ANALYZER_FORK_WITH_INTERNAL_API_RISK_ENCAPSULATED_UPSTREAM
```

The shared library internally relies on JGit DFS/Reftable implementation packages. That dependency
is version-pinned and covered by compatibility tests in the storage project. If a future JGit
upgrade requires a generic adaptation, it is implemented and released there rather than copied into
Audio Analyzer.

## Decision

The spike is complete. Continue with the shared module and the narrow Audio Analyzer facade. Missing
generic transaction, mapping-registration or projection-lifecycle hooks must be added upstream and
released before downstream use.
