# Workflow history search

Audio Analyzer provides three complementary ways to query versioned workflow history.

- `WorkflowHistorySearch` in `audio-core` performs exact semantic queries over a bounded set of already loaded authoritative workflow snapshots.
- `IndexedWorkflowHistorySearch` uses the shared `jgit-storage-hibernate-search` projection for repeated compound queries over commit messages, authors, changed paths, commit time and changed `workflow.dsl` content.
- `IndexedWorkflowSemanticHistorySearch` uses an Audio Analyzer-owned branch-aware projection for workflow ids/names, node ids/types/labels and selected workflow/node metadata.

Every indexed result contains the exact Git commit identifier. The matching workflow is loaded through `VersionedWorkflowStore.loadAtCommit`; neither Lucene projection becomes workflow authority.

```text
checkpoint -> Hibernate-backed JGit commit and ref update
           -> best-effort generic CommitIndexer upsert
           -> best-effort branch-semantic reconciliation

generic query  -> one bounded CommitHistoryQuery
               -> generic GitHistorySearchService
               -> exact CommitId

semantic query -> branch + exact workflow/node/property predicates
               -> application semantic projection
               -> exact CommitId

exact CommitId -> authoritative workflow snapshot from Git
```

## Projection ownership

The generic library owns reusable Git-history facts:

- commit identity and messages;
- author and commit time;
- actual first-parent changed paths;
- selected changed-file text;
- generic Lucene lifecycle and query composition.

Audio Analyzer owns only domain facts that the generic library cannot infer generically:

- branch reachability at projection time;
- workflow id and name;
- node ids, types and labels;
- workflow and node metadata keys, values and exact key/value pairs.

The semantic projector parses the exact stored `workflow.dsl` through the same deterministic `WorkflowDslParser` used by the domain. It rejects inconsistent history where the separately stored `workflow.id` differs from the DSL workflow id.

## Consistency model

A successful checkpoint remains valid even when either projection update fails. The failure is logged, and the explicit rebuild command derives the missing generic and semantic rows again from Git history.

Semantic reconciliation is branch-specific and idempotent:

- rows are unique by repository, branch and commit;
- existing rows are updated in place;
- newly reachable commits are inserted;
- commits no longer reachable after a successful ref move are removed from that branch only;
- the same commit may legitimately have one row for each branch from which it is reachable.

Arbitrary node labels and metadata values are stored losslessly for exact matching. Encoded property-pair terms preserve key/value correlation, so a workflow containing `mode=research` and `threshold=high` does not falsely satisfy `mode=high`.

The application uses one Hibernate `SessionFactory` for generic JGit storage, generic Search, semantic Search and collaboration entities. Startup migrations execute in this order:

1. `jgit-storage-hibernate-core` migrations;
2. `jgit-storage-hibernate-search` migrations;
3. Audio Analyzer semantic-search migrations;
4. Audio Analyzer collaboration migrations;
5. Hibernate schema validation.

Each ownership boundary has its own Flyway history table. No second Hibernate bootstrap, raw JDBC search repository or Audio Analyzer copy of the generic commit index exists.

## PostgreSQL rebuild evidence

`WorkflowPostgreSqlProjectionRebuildIntegrationTest` exercises the complete production-oriented path on PostgreSQL 17.10:

1. apply all four migration histories;
2. start the shared Hibernate context in `validate` mode;
3. create canonical workflow commits through the Hibernate-backed JGit store;
4. delete both disposable projection row sets;
5. restart Hibernate without touching Git objects or refs;
6. rebuild generic and semantic projections from authoritative Git history;
7. run generic and branch-semantic queries;
8. load and compare the exact canonical snapshot.

This proves that projection loss is recoverable and cannot destroy workflow authority.

## Workbench UI

When `workbench.persistence.mode=hibernate` provides the indexed-search and history-command beans, the packaged React Flow workbench exposes a **Search version history** drawer. The drawer stays absent in non-indexed modes instead of presenting controls that cannot succeed.

The production panel supports:

- full-text expressions across commit summaries, changed paths and deterministic workflow DSL content;
- exact author-email filtering;
- analyzed changed-path terms;
- inclusive lower and upper commit-time bounds entered in local browser time and transmitted as instants;
- bounded result counts from 1 to 200;
- explicit idempotent rebuild from an authoritative branch;
- exact commit identity, author, timestamp and changed-path evidence per result;
- loading an exact authoritative workflow snapshot;
- comparing two exact result commits reachable from the selected branch;
- restoring one exact result as a **new audit commit** on the selected branch.

All supplied generic predicates execute in one bounded server-side query. The client does not intersect independently truncated result lists.

Changed paths use a dedicated, language-neutral analyzer in the shared projection. It splits path punctuation and lowercases components, so `workflow` matches `workflow.dsl`. Language-specific stemming is separately configurable for commit-message fields in `jgit-storage-hibernate`; it is not applied to paths, identifiers or changed source text.

## Compare semantics

Comparison is read-only. Both commits must be reachable from the requested branch and contain the same workflow id. The response contains:

- exact before and after commit ids;
- full transport-safe graph projections for both states;
- ordered semantic change atoms produced by `WorkflowDiff`.

Full graph projections are included because node labels, workflow names and other visible fields may differ even when the current `WorkflowDiff` atom model does not yet assign a dedicated change subtype.

```text
POST /workflow/history/compare
{
  "branch": "main",
  "beforeCommitId": "<exact commit>",
  "afterCommitId": "<exact commit>"
}
```

## Non-destructive restore semantics

Restore never moves a branch backward and never deletes later history. It loads the historical target snapshot and commits that snapshot again on top of the current branch HEAD.

The command requires the caller's observed `expectedHeadCommitId`. The JGit adapter compares that value with the actual ref and publishes the new commit through the same optimistic ref-update boundary. A concurrent branch advance therefore produces HTTP 409 instead of silently overwriting work.

```text
POST /workflow/history/restore
{
  "branch": "main",
  "targetCommitId": "<reachable historical commit>",
  "expectedHeadCommitId": "<current branch HEAD>",
  "author": "Restorer",
  "message": "Restore approved baseline",
  "timestamp": "2026-07-20T08:00:00Z"
}
```

The response identifies the historical source, previous HEAD and newly created restore commit. The workbench subsequently loads only the newly created commit.

Restore is blocked while a collaboration session for the same workflow still has joined participants. A session may remain durable for reconnect after every participant leaves; that retained but inactive session does not block restore. Compare remains read-only and is allowed.

Conflict responses use RFC 9457 problem details:

- `STALE_WORKFLOW_HEAD` for optimistic-concurrency failure;
- `WORKFLOW_HISTORY_ACCESS_CONFLICT` for active collaboration membership.

The browser also performs an immediate local active-session guard for better feedback, but the server policy remains authoritative.

## Generic HTTP API

Search indexed commit metadata and changed workflow content:

```text
GET /workflow/history/index
    ?q=<simple-query-string expression>
    &author=<exact email>
    &path=<analyzed path terms>
    &from=<inclusive ISO-8601 instant>
    &to=<inclusive ISO-8601 instant>
    &limit=<1..200>
```

Every filter is optional. Blank full text with structured filters uses the shared relational newest-first query path; a non-blank expression combines the remaining predicates as filters.

## Semantic HTTP API

Search domain semantics for commits reachable from one branch:

```text
GET /workflow/history/semantic
    ?branch=main
    &workflow=<exact workflow id>
    &node=<exact node id>
    &type=<exact node type>
    &label=<workflow-name/node-label full-text expression>
    &propertyKey=<exact metadata key>
    &propertyValue=<exact metadata value>
    &limit=<1..200>
```

`branch` is mandatory in the application contract and defaults to `main` at the HTTP boundary. Supplying both property parameters requires the key and value to belong to the same metadata entry.

## Rebuild

```text
POST /workflow/history/index/rebuild?branch=main&limit=-1
```

A negative limit means all commits reachable from the branch head. The operation updates both the generic projection and the semantic branch projection. A zero limit performs no work and does not clear existing semantic rows.

## Query-composition boundary

The generic and semantic search endpoints remain intentionally separate. Intersecting two independently limited result lists would produce incorrect omissions. A future combined endpoint must either execute one bounded server-side plan or use an explicitly continued commit-id candidate plan before ranking and limiting.

## Current boundary

No JGit, Hibernate, Hibernate Search or Lucene type crosses into `audio-core`, the frontend or HTTP response models. Remaining issue #247 work is the correctness-preserving combined generic/semantic query plan and semantic filter controls in the production drawer.
