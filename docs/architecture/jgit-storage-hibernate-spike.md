# Spike: JGit/Hibernate storage consolidation

Status: Proposed  
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

If the answer is no, the spike must identify the smallest required JGit API or core change.

## Scope

Included:

- extract or re-create a neutral Hibernate-backed JGit storage prototype;
- compile it against a regular JGit artifact where possible;
- write/read blobs, trees, commits, refs and reflog entries through JGit APIs;
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

### 3. Transaction rollback

Prove there is no visible half-state when failures happen after writing objects but before updating refs or publishing outbox events.

Failure cases:

```text
fail after blob/tree/commit write, before ref update
fail after ref update, before operation log append
fail after operation log append, before outbox event insert
fail before transaction commit
```

The final design may choose compensation instead of one large transaction, but the behavior must be deterministic and documented.

### 4. Reflog behavior

Clarify:

- whether reflog entries are written by JGit automatically for the chosen storage path;
- whether reflog writes are in the same transactional unit as ref updates;
- whether reflog can support later audit/search projections;
- what happens on rollback.

### 5. Cache and restart behavior

Prove:

- repository can be closed and reopened;
- JVM/Spring context restart does not expose stale pack data;
- multiple logical repositories can coexist in the same database;
- pack names do not collide;
- JGit DFS block cache does not leak stale data between tests or repositories.

### 6. Data-size boundary

Document that large audio recordings are not stored as Git blobs in this store. Workflow DSL, layout, metadata and small presets may be versioned; large `.aar` recordings or evidence bundles should remain external assets referenced by content hash or metadata.

## Acceptance criteria

- [ ] A neutral storage module exists or a precise extraction plan is documented.
- [ ] The module either compiles against regular JGit or identifies the exact required fork/API delta.
- [ ] Blob/tree/commit/ref roundtrip is tested.
- [ ] Atomic ref update is tested.
- [ ] Rollback/half-state behavior is tested or explicitly rejected with a documented reason.
- [ ] Reflog behavior is documented.
- [ ] Restart/cache behavior is tested.
- [ ] Audio Analyzer public APIs do not expose JGit internal classes.
- [ ] A follow-up issue exists for the vertical `Input -> Gain -> Output` workflow store slice.

## Decision outcomes

### Outcome A: regular JGit is sufficient

Proceed with a separate `jgit-storage-hibernate` module and pin a tested JGit version. Keep upgrade compatibility tests.

### Outcome B: regular JGit is insufficient but a small API change would solve it

Document the missing extension point, keep the Audio Analyzer facade unchanged, and decide whether to maintain a minimal fork or prepare an upstream contribution.

### Outcome C: the storage path is too fragile

Do not build the collaborative editor on this storage layer yet. Re-scope to a simpler operation-log-first persistence model and revisit DB-backed Git later.
