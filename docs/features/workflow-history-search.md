# Workflow history search

Audio Analyzer provides two complementary ways to query versioned workflow history.

- `WorkflowHistorySearch` in `audio-core` performs exact semantic queries over a bounded set of authoritative workflow snapshots. It can answer domain questions such as which versions contain a node type or metadata value.
- `IndexedWorkflowHistorySearch` uses the released `jgit-storage-hibernate-search` projection for repeated full-text queries over commit messages, changed paths and changed `workflow.dsl` content.

Every indexed result contains the exact Git commit identifier. The matching workflow is loaded through the existing `VersionedWorkflowStore.loadAtCommit` boundary or the `/workflow/load` command; the Lucene projection never becomes workflow authority.

```text
checkpoint -> Hibernate-backed JGit commit and ref update
           -> best-effort CommitIndexer upsert

query      -> GitHistorySearchService / Lucene projection
           -> WorkflowHistoryTextResult
           -> exact CommitId
           -> authoritative workflow snapshot
```

## Consistency model

A successful checkpoint remains valid even when projection indexing fails. The failure is logged, and the explicit rebuild command can derive missing rows again from Git history. Repeated indexing and rebuilds are idempotent by repository name and object ID.

The application uses one Hibernate `SessionFactory` for generic JGit storage, generic Search projections and Audio Analyzer collaboration entities. Startup migrations execute in this order:

1. `jgit-storage-hibernate-core` migrations;
2. `jgit-storage-hibernate-search` migrations;
3. Audio Analyzer collaboration migrations;
4. Hibernate schema validation.

No second Hibernate bootstrap, raw JDBC search repository or Audio Analyzer copy of the generic index exists.

## HTTP API

Search the indexed commit message, changed paths and changed workflow DSL:

```text
GET /workflow/history/index?q=<query>&limit=<count>
```

Each result includes:

- exact `commitId`;
- commit message;
- author name and email;
- commit timestamp;
- changed paths.

Rebuild missing projections from a branch head:

```text
POST /workflow/history/index/rebuild?branch=main&limit=-1
```

A negative rebuild limit means all reachable commits. Search result loading continues to use the existing authoritative load endpoint:

```text
POST /workflow/load
{"commitId":"<exact commit id>"}
```

## Current boundary

This first slice deliberately exposes no JGit, Hibernate, Hibernate Search or Lucene type in `audio-core` or HTTP responses. Structured compound author/path/time filters, workflow-specific indexed fields and the production search panel remain subsequent work in issue #247.
