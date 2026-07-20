# Workflow history search

Audio Analyzer provides complementary read models over authoritative workflow checkpoints.

- `WorkflowHistorySearch` in `audio-core` performs exact semantic queries over a bounded set of already loaded authoritative workflow snapshots.
- `IndexedWorkflowHistorySearch` uses the shared `jgit-storage-hibernate-search` projection for compound queries over commit messages, authors, changed paths, commit time and changed `workflow.dsl` content.
- `IndexedWorkflowSemanticHistorySearch` uses an Audio Analyzer-owned branch-aware projection for workflow ids/names, node ids/types/labels and selected workflow/node metadata.
- `IndexedWorkflowCombinedHistorySearch` composes both projections through exact commit candidates before the generic query applies ranking, ordering and the final result limit.

Every result contains the exact Git commit identifier. The matching workflow is loaded through `VersionedWorkflowStore.loadAtCommit`, `/workflow/load`, compare or non-destructive restore commands. Neither relational nor Lucene projections become workflow authority.

```text
checkpoint -> Hibernate-backed JGit commit and ref update
           -> best-effort generic CommitIndexer upsert
           -> best-effort branch-semantic reconciliation

semantic filters -> every exact branch-reachable candidate CommitId
                  -> generic full-text/author/path/time query restricted to candidates
                  -> relevance ranking or newest-first ordering
                  -> final result limit
                  -> semantic evidence for final CommitIds only

exact CommitId -> authoritative workflow snapshot from Git
```

## Projection ownership

The generic library owns reusable Git-history facts:

- commit identity and messages;
- author and commit time;
- actual first-parent changed paths;
- selected changed-file text;
- Lucene lifecycle, analysis and generic query composition;
- exact commit-candidate restrictions applied before the final limit.

Audio Analyzer owns only domain facts that the generic library cannot infer generically:

- branch reachability at projection time;
- workflow id and name;
- node ids, types and labels;
- workflow and node metadata keys, values and exact key/value pairs.

The semantic projector parses the exact stored `workflow.dsl` through the same deterministic `WorkflowDslParser` used by the domain. It rejects inconsistent history where the separately stored `workflow.id` differs from the DSL workflow id.

## Correct combined query plan

The application never intersects two independently limited result lists. That pattern is incorrect because either projection may discard a valid match before the intersection is computed.

The production plan is:

```text
1. semantic projection
   repository + branch + workflow/node/property predicates
   -> all exact candidate CommitIds, no semantic result limit

2. shared generic projection
   candidate CommitIds + text + author + path + time
   -> one server-side query
   -> generic relevance/order
   -> final limit

3. semantic projection
   final CommitIds only
   -> response evidence
```

`jgit-storage-hibernate-search` distinguishes an omitted candidate restriction from an explicitly empty set. An empty semantic candidate set therefore returns no generic hits without executing an unbounded fallback query.

Candidate membership is a filter and does not alter full-text relevance scores. Structured generic queries apply the same IDs before newest-first ordering and limiting.

If the semantic projection changes between candidate selection and evidence retrieval, the combined query fails explicitly instead of returning a partially evidenced result. The caller may retry against the current rebuildable projections.

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

## Workbench UI

When `workbench.persistence.mode=hibernate` provides indexed history, the packaged React Flow workbench exposes a **Search version history** drawer. The drawer stays absent in non-indexed modes instead of presenting controls that cannot succeed.

The production panel supports:

- branch reachability as a mandatory semantic boundary;
- full-text expressions across commit summaries, changed paths and deterministic workflow DSL content;
- exact author-email filtering;
- analyzed changed-path terms;
- inclusive lower and upper commit-time bounds entered in local browser time and transmitted as instants;
- exact workflow id, node id and node type;
- workflow-name/node-label full-text expressions;
- exact metadata keys, values and correlated key/value pairs;
- bounded final result counts from 1 to 200;
- explicit idempotent rebuild from the authoritative branch;
- generic and semantic evidence per exact commit;
- exact loading, comparison and non-destructive restore.

Changed paths use a dedicated, language-neutral analyzer in the shared projection. It splits path punctuation and lowercases components, so `workflow` matches `workflow.dsl`. Language-specific stemming is separately configurable for commit-message fields in `jgit-storage-hibernate`; it is not applied to paths, identifiers or changed source text.

Historical loading and restore are blocked while the browser is attached to a collaboration session. Restore is also protected server-side against active collaboration membership and a stale branch HEAD. The rebuild action remains safe because it only reconciles disposable projections.

## Combined HTTP API

```text
POST /workflow/history/combined/query
Content-Type: application/json
```

```json
{
  "generic": {
    "text": "wingbeat",
    "authorEmail": "researcher@example.org",
    "pathText": "workflow",
    "from": "2026-07-20T09:00:00Z",
    "to": "2026-07-20T11:00:00Z",
    "limit": 20
  },
  "semantic": {
    "branch": "main",
    "workflowId": "workflow.insect-observer",
    "nodeId": "node.classifier",
    "nodeType": "classifier",
    "labelText": "wingbeat",
    "propertyKey": "mode",
    "propertyValue": "safe"
  }
}
```

The response contains nested generic commit evidence and branch-specific semantic evidence for the same exact commit. Supplying both property parameters requires the key and value to belong to the same metadata entry.

## Specialized APIs

The specialized endpoints remain available for consumers needing only one projection:

```text
GET /workflow/history/index
GET /workflow/history/semantic
```

The generic endpoint supports commit text/author/path/time filters. The semantic endpoint supports branch/workflow/node/property filters with its own bounded result contract. Production UI queries that combine these categories use the combined endpoint, not client-side intersection.

## Rebuild

```text
POST /workflow/history/index/rebuild?branch=main&limit=-1
```

A negative limit means all commits reachable from the branch head. The operation updates both the generic projection and the semantic branch projection. A zero limit performs no work and does not clear existing semantic rows.

## Compare and restore

Compare accepts two exact commits reachable from the requested branch and returns both graph projections plus ordered semantic change atoms.

Restore is non-destructive:

```text
historical snapshot + expected current HEAD
  -> conditional new Git commit on the branch
```

The branch is never reset backward. A stale expected HEAD or active collaboration session produces a machine-readable HTTP 409 response.

## Verification

The combined plan is covered by:

- unit tests for the core query/result contracts;
- H2 evidence proving semantic candidate filtering before a generic `limit=1`;
- PostgreSQL migrate → validate → projection deletion → restart → rebuild → combined query → exact load;
- HTTP adapter tests for nested generic/semantic filters and nested evidence;
- packaged Playwright/Testcontainers evidence for semantic filtering, generic filtering, exact loading, compare, restore and durable restart.

No JGit, Hibernate, Hibernate Search or Lucene type crosses into `audio-core`, the frontend or HTTP response models.
