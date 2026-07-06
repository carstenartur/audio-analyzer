# Spike: JGit/Hibernate storage consolidation

Status: Completed — Outcome A (regular JGit is sufficient)  
Related ADR: `docs/architecture/adr-006-versioned-collaborative-workflow-store.md`

## Goal

Determine whether the Hibernate-backed JGit storage code can be consolidated as a reusable module without requiring Audio Analyzer to fork or patch JGit.

The desired result is a separate infrastructure component, tentatively named `jgit-storage-hibernate` or `hibernate-jgit-store`, that can be used by Audio Analyzer, Taxonomy and Sandbox.

## Why this spike comes first

The collaborative workflow editor depends on transactional version storage. If the storage layer is copied into Audio Analyzer too early, the same low-level code will diverge across projects and become harder to test.

This spike must answer one question before larger editor work starts:

```text
Can the DB-backed JGit store be implemented as an external module against a regular JGit release?
```

Result: **yes**. A reusable storage module can be implemented against the normal `org.eclipse.jgit:org.eclipse.jgit` artifact. No Audio Analyzer specific fork or patch is required for the storage layer itself.

The only non-obvious requirement is storage-side conflict detection: the backing database must reject stale reftable replacement writes so that concurrent ref updates become optimistic-lock conflicts instead of lost updates. The JGit API already supports this pattern through DFS/reftable hooks; the responsibility stays inside the storage module.

## Decision

Proceed with an external `jgit-storage-hibernate` / `hibernate-jgit-store` module that:

- hides all `org.eclipse.jgit.internal.storage.dfs.*` usage behind its own persistence facade;
- persists pack data and reftables in DB tables owned by the storage module;
- uses repository-scoped optimistic locking / uniqueness to reject stale ref-table replacement writes;
- treats reflog as optional metadata, not as the authoritative workflow audit log.

## Scope

Included:

- extract or re-create a neutral Hibernate-backed JGit storage prototype;
- compile it against a regular JGit artifact where possible;
- write/read blobs, trees, commits and refs through JGit APIs, and document reflog behavior;
- prove transactional ref update behavior;
- document whether JGit internals are unavoidable;
- expose only a narrow facade to Audio Analyzer.

Excluded:

- graphical editor implementation;
- audio DSP execution;
- full workflow DSL;
- ActiveMQ/WebSocket integration;
- full Hibernate Search history model.

## Candidate facade

Audio Analyzer should depend on a facade similar to this, not directly on JGit DFS classes:

```java
public interface VersionedWorkflowStore {
    CommitId commit(String branch, WorkflowSnapshot snapshot, CommitMetadata metadata);

    WorkflowSnapshot loadAtCommit(CommitId commitId);

    WorkflowSnapshot loadHead(String branch);

    RefUpdateResult updateRef(String refName, CommitId expectedOldCommit, CommitId newCommit);

    List<CommitInfo> history(String refName, int limit);
}
```

The storage module may internally use `DfsRepository`, `DfsObjDatabase`, `DfsReftableDatabase` or related classes, but those types must not appear in Audio Analyzer public APIs.

## Neutral extraction plan

1. **Extract a separate module/repository** named `jgit-storage-hibernate` (or `hibernate-jgit-store`) with no dependency on Audio Analyzer workflow classes.
2. **Keep the public surface neutral**:
   - branch/ref names as strings;
   - commit IDs as opaque value objects;
   - serialized workflow snapshots / metadata as module-owned DTOs;
   - no `org.eclipse.jgit.internal.*` types in exported APIs.
3. **Implement the JGit adapter inside the module** by subclassing DFS/reftable storage classes from the regular JGit artifact:
   - `DfsRepository`
   - `DfsObjDatabase`
   - `DfsReftableDatabase`
4. **Map persistence to Hibernate entities/tables**:
   - repository table;
   - pack/reftable metadata table;
   - pack binary/blob table;
   - optional GC / compaction bookkeeping.
5. **Enforce optimistic ref updates in the database** with a repository-scoped uniqueness/locking rule keyed by the reftable replacement generation (`maxUpdateIndex` or equivalent replacement token).
6. **Keep workflow history outside reflog**:
   - Git refs/commits remain durable checkpoints;
   - operation log + transactional outbox remain the collaboration/audit source of truth.

## Prototype proof

Prototype coverage lives in `audio-app/src/test/java/org/hammer/audio/RegularJGitStoragePrototypeTest.java`.

The prototype compiles against the regular Maven artifact configured as `org.eclipse.jgit:org.eclipse.jgit` via the root `jgit.version` property and proves:

|           Proof area           |          Result           |                                                              Notes                                                               |
|--------------------------------|---------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| Blob/tree/commit/ref roundtrip | ✅                         | Low-level JGit APIs create blob, tree, commit and ref history successfully.                                                      |
| Concurrent ref update          | ✅                         | One writer succeeds and one writer fails once the backing store rejects stale reftable replacement writes.                       |
| Rollback / half-state          | ✅ (documented limitation) | A failure before ref update leaves branch history unchanged, but already-written objects remain orphaned until GC.               |
| Reflog behavior                | ✅ (documented)            | DFS/reftable path did not emit reflog entries in this prototype even when ref-log messages were supplied.                        |
| Restart / cache behavior       | ✅                         | Reopen works; long-lived readers need `scanForRepoChanges()` after external writes; repo-scoped pack naming prevents collisions. |
| Audio Analyzer API isolation   | ✅                         | Prototype is test-scoped only; production modules remain free of JGit dependencies.                                              |

## Required technical proof

### 1. Minimal repository lifecycle

Prove:

```text
create repository
write blob
write tree
write commit
update refs/heads/main or refs/heads/master
read commit through ref
read blob through tree
create second commit
traverse history
```

### 2. Atomic ref update

Concurrent writers must not cause lost updates.

Test shape:

```text
base: main -> C1
thread A: expects C1, wants C2
thread B: expects C1, wants C3
expected: exactly one update succeeds; the other observes a conflict
```

Observed in the prototype: regular JGit is sufficient **if** the storage backend rejects stale reftable replacement commits. In practice the Hibernate module should back the reftable replacement with a repository-scoped optimistic-lock / unique-key rule so the loser becomes a retriable conflict.

### 3. Transaction rollback

Prove there is no visible half-state when failures happen after writing objects but before updating refs or publishing outbox events.

Failure cases:

```text
fail after blob/tree/commit write, before ref update
fail after ref update, before operation log append
fail after operation log append, before outbox event insert
fail before transaction commit
```

Observed in the prototype:

- failure after object write but before ref update leaves `refs/heads/main` unchanged;
- the newly written blob/tree/commit remains addressable by object ID and therefore must be treated as orphaned data until later GC/compaction;
- pure JGit storage does **not** solve operation-log/outbox atomicity by itself.

Decision: accept the ref-visible behavior, but explicitly reject "JGit alone gives one large workflow + outbox transaction" as a design assumption. That guarantee belongs to the surrounding application/Hibernate transaction boundary.

### 4. Reflog behavior

Observed in the prototype:

- reflog entries were **not** emitted for this DFS/reftable-backed repository, even when `RefUpdate#setRefLogMessage(...)` was used;
- Audio Analyzer therefore must not rely on reflog as the workflow audit trail;
- if reflog support becomes desirable later, it should be treated as optional storage-module work, not a blocker for workflow versioning.

### 5. Cache and restart behavior

Observed in the prototype:

- repository state survives close/reopen when packs and reftables are kept in an external backing store;
- a long-lived repository instance can see stale ref state after another writer commits, but `scanForRepoChanges()` refreshes the view;
- multiple logical repositories can share one backing database if pack/reftable identifiers are repository-scoped;
- repo-scoped pack naming avoids collisions across logical repositories.

### 6. Data-size boundary

Document that large audio recordings are not stored as Git blobs in this store. Workflow DSL, layout, metadata and small presets may be versioned; large `.aar` recordings or evidence bundles should remain external assets referenced by content hash or metadata.

## Acceptance criteria

- [x] A neutral storage module exists or a precise extraction plan is documented.
- [x] The module either compiles against regular JGit or identifies the exact required fork/API delta.
- [x] Blob/tree/commit/ref roundtrip is tested.
- [x] Atomic ref update is tested.
- [x] Rollback/half-state behavior is tested or explicitly rejected with a documented reason.
- [x] Reflog behavior is documented.
- [x] Restart/cache behavior is tested.
- [x] Audio Analyzer public APIs do not expose JGit internal classes.
- [ ] A follow-up issue exists for the vertical `Input -> Gain -> Output` workflow store slice.

## Required follow-up issue

Create a follow-up issue for:

```text
Vertical slice: Input -> Gain -> Output workflow store roundtrip
```

Suggested scope:

- serialize a minimal workflow DSL snapshot from `audio-core`;
- persist it through the extracted storage facade;
- reload it from HEAD and from a historical commit;
- append a matching operation-log entry and projection update inside the same application transaction boundary.

## Decision outcomes

### Outcome A: regular JGit is sufficient

Confirmed. Proceed with a separate `jgit-storage-hibernate` module and pin a tested JGit version. Keep upgrade compatibility tests around the low-level DFS/reftable adapter.

### Outcome B: regular JGit is insufficient but a small API change would solve it

Document the missing extension point, keep the Audio Analyzer facade unchanged, and decide whether to maintain a minimal fork or prepare an upstream contribution.

### Outcome C: the storage path is too fragile

Do not build the collaborative editor on this storage layer yet. Re-scope to a simpler operation-log-first persistence model and revisit DB-backed Git later.
