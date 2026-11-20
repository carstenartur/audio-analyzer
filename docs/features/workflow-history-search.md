# Workflow history search

Use **Search version history** to find exact stored workflow checkpoints, compare them and restore
an earlier graph as a new audited commit. This searches Git checkpoints, not the individual edits in
a collaboration session's **Semantic history** panel.

## Prerequisites and a first pair of checkpoints

1. Build the packaged application using [Getting started](../getting-started.md). Start it with the
   local H2 or PostgreSQL configuration in [Hibernate-backed persistence](../workbench-hibernate-persistence.md).
   Supply the explicit data source, enable migrations and keep Hibernate schema validation enabled.
   The indexed drawer is unavailable in the default `memory` mode and in `filesystem` mode.
2. Open [http://localhost:8080/workbench](http://localhost:8080/workbench), or the configured host and
   port. Leave any collaboration session before loading or restoring historical state. Search and
   comparison are read-only and can still be used while collaborating.
3. If the branch already has checkpoints, continue with the search below. For a small first example,
   use the [portable document desktop exercise](portable-experiment-documents.md#desktop-open-edit-save-and-reopen)
   to create `portable-review.audioexp` from `docs/examples/minimal.audioexp`, keeping the workflow ID.
4. Outside collaboration, use **Experiment document** to preview and open the original minimal
   fixture. In the right-hand **Checkpoint** section, set **Branch** to `main`, enter the message
   `Portable baseline` and select **Save checkpoint**. Expect a saved commit ID and a history entry.
   This persists the standalone graph; it does not archive the complete portable envelope.
5. Preview and open `portable-review.audioexp`, resolving any dirty-document prompt deliberately.
   Save another checkpoint on `main` with message `Portable review`. Both checkpoints have workflow
   ID `example.workflow`, but the second has the changed workflow name. Keep the `.audioexp` copies
   separately to retain profiles, plugin data, assets and provenance as a complete setup.

**Save checkpoint** is disabled during collaboration. Leaving a session restores the standalone
editor graph; it does not transfer the session's edits to checkpoint history. Creating a new session
also does not adopt a loaded checkpoint. See [state and persistence boundaries](collaborative-workflows.md#leave-return-and-preserve-work).

## Search, inspect and load an exact version

1. Select **Search version history**. Set **Authoritative branch to search, rebuild and operate on**
   to `main`, or the branch that owns your checkpoints. The field applies to search, compare, restore
   and rebuild; it is not just a maintenance setting.
2. For the fixture example, enter `Portable` in **Full-text query**, `example.workflow` in
   **Exact workflow ID**, and a result limit of at least 2. Leave unrelated filters blank. Select
   **Search combined indexes**. Expect both checkpoint messages with exact commit identity, author,
   time, changed-path and workflow evidence.
3. Refine a query with **Exact node type**, **Exact node ID**, **Workflow name or node label**, or
   **Exact property key/value** when those facts exist in your graph. For example, `gain` is the
   exact type of a catalog Gain node, and `gain.factor` is its execution parameter key. The minimal
   fixture has no nodes, so a Gain filter correctly removes its results. All supplied filters must
   match the same commit; the final result limit is applied after semantic candidate filtering.
4. To inspect one matching graph on the canvas, retain any unsaved standalone document first with
   **Save current document** and its download acknowledgement. Select that result's **Load exact
   version**. The workbench reloads with the exact stored graph. Loading does not move the branch
   HEAD, create a restore commit or join a collaboration session.
5. After reload, reopen **Search version history** and repeat the query if needed. To return to the
   newer graph, load its exact result; to change the branch's current version, use restore below.

Time bounds use the browser's local date/time and are sent as inclusive instants. **Exact author
email** matches stored commit evidence, not a collaboration display name. **Changed-path terms**
uses path components, so `workflow` can match `workflow.dsl`. Start with a blank query and only the
branch when diagnosing unexpectedly empty results, then add filters one at a time.

## Compare and restore

Under **Compare and restore exact versions**, choose two search results in **Before commit** and
**After commit**, then select **Compare exact commits**. Both must be reachable from the selected
branch and have the same workflow ID. Comparison does not change the canvas or history. For the
name-only fixture example, the heading shows `Portable example → Portable review` and the changes
include `WORKFLOW_FIELD_CHANGED` for `example.workflow` at `name`. The API includes the complete
before/after graph projections as well as its typed changes.

To make an earlier version current on the branch:

1. Save any standalone document you need to retain. Leave collaboration; active joined participants
   on the affected workflow must also leave before the server allows restore. Historical load and
   restore replace the standalone graph; they do not provide the document Open save/discard dialog.
2. Select the desired **Version to restore** from the search results and select **Restore as new
   commit**. This button submits the restore; there is no additional preview confirmation dialog.
3. The client reads the current branch HEAD and submits it as the expected HEAD. Success creates a
   new commit containing the selected historical graph, then loads that new commit and reloads the
   workbench. The older and later commits remain in history.
4. Search again or use **Refresh history** to inspect the new restore commit. If the branch advanced
   concurrently, review the latest history and deliberately retry. A rejected stale restore does
   not move the branch backward.

## Empty results and recoverable failures

|                           Symptom                            |                                                                    What to check or do                                                                     |
|--------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| No **Search version history** control                        | Confirm the server is running in Hibernate mode with indexed-history configuration. A browser reload does not enable persistence.                          |
| No matching checkpoints                                      | Check branch, exact IDs/types and filters; confirm **Save checkpoint** actually succeeded. Session operations are not Git checkpoints.                     |
| Checkpoint exists but its derived search evidence is missing | Select the correct branch and **Rebuild missing projections**, then search again. Rebuild reads authoritative Git history and does not create checkpoints. |
| Invalid time bounds                                          | Enter valid browser-local times with the lower bound no later than the upper bound.                                                                        |
| Load/restore asks you to leave collaboration                 | Leave the session before replacing the standalone graph. Restore can also be blocked by other active members of the affected workflow.                     |
| `STALE_WORKFLOW_HEAD`                                        | Search/refresh current history, review the new HEAD and submit a new deliberate restore.                                                                   |
| Semantic evidence changed during a query                     | Retry against the current projections; use rebuild if the projection is incomplete.                                                                        |

## Search model

Audio Analyzer provides complementary domain and indexed search contracts.

- `WorkflowHistorySearch` in `audio-core` performs exact semantic queries over a bounded set of already loaded authoritative workflow snapshots.
- `IndexedWorkflowHistorySearch` uses the shared `jgit-storage-hibernate-search` projection for repeated compound queries over commit messages, authors, changed paths, commit time and changed `workflow.dsl` content.
- `IndexedWorkflowSemanticHistorySearch` uses an Audio Analyzer-owned branch-aware projection for workflow ids/names, node ids/types/labels and selected workflow/node metadata.
- `IndexedWorkflowCombinedHistorySearch` applies semantic candidate selection before generic filtering, ranking and the final limit. The production drawer uses this combined contract.

Every indexed result contains the exact Git commit identifier. The matching workflow is loaded through `VersionedWorkflowStore.loadAtCommit`; neither Lucene projection becomes workflow authority.

Checkpoint creation updates authoritative Git history and then the rebuildable generic and semantic
projections. A combined query selects all exact branch-reachable semantic candidate commit IDs,
applies generic predicates and ranking within that set, and limits only the final results. Loading
uses the resulting exact commit ID to read the authoritative workflow snapshot from Git.

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
- branch reachability and exact workflow id, node id and node type filters;
- workflow-name/node-label full-text expressions and exact correlated metadata key/value filters;
- bounded result counts from 1 to 200;
- explicit idempotent rebuild from an authoritative branch;
- exact commit identity, author, timestamp and changed-path evidence per result;
- loading an exact authoritative workflow snapshot;
- comparing two exact result commits reachable from the selected branch;
- restoring one exact result as a **new audit commit** on the selected branch.

Generic predicates and the full semantic candidate set execute in one bounded server-side query.
The client does not intersect independently truncated result lists.

Changed paths use a dedicated, language-neutral analyzer in the shared projection. It splits path punctuation and lowercases components, so `workflow` matches `workflow.dsl`. Language-specific stemming is separately configurable for commit-message fields in `jgit-storage-hibernate`; it is not applied to paths, identifiers or changed source text.

## Compare semantics

Comparison is read-only. Both commits must be reachable from the requested branch and contain the same workflow id. The response contains:

- exact before and after commit ids;
- full transport-safe graph projections for both states;
- ordered semantic change atoms produced by `WorkflowDiff`.

`WorkflowDiff` includes workflow name/metadata, node type/label/ports and edge endpoint field changes,
alongside additions/removals and metadata parameter changes. The HTTP projection uses
`WORKFLOW_FIELD_CHANGED`, `NODE_FIELD_CHANGED` and `EDGE_FIELD_CHANGED` for those field atoms. Full
before/after graph projections remain available for inspecting the complete states.

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

## Combined HTTP API used by the drawer

```text
POST /workflow/history/combined/query
Content-Type: application/json
```

```json
{
  "generic": {
    "text": "Portable",
    "limit": 20
  },
  "semantic": {
    "branch": "main",
    "workflowId": "example.workflow"
  }
}
```

The generic object also accepts `authorEmail`, `pathText`, `from` and `to`; the semantic object also
accepts `nodeId`, `nodeType`, `labelText`, `propertyKey` and `propertyValue`. `branch` is the required
reachability boundary. Each response hit includes nested generic commit evidence and semantic
evidence for that same exact commit. See [the combined query plan](../architecture/workflow-history-search.md)
for the full contract and its correctness evidence.

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

The specialized generic and semantic endpoints remain available for their own bounded queries. The
implemented combined endpoint composes the complete semantic candidate set with generic ranking
before applying the final limit; it does not intersect two independently truncated hit lists. An
explicitly empty candidate set returns no hits. If semantic evidence changes during composition, the
query fails explicitly so the caller can retry instead of receiving partly evidenced results.

## Current boundary

No JGit, Hibernate, Hibernate Search or Lucene type crosses into `audio-core`, the frontend or HTTP
response models. Combined generic/semantic queries and semantic controls in the production drawer
are implemented. Version search still operates on explicit checkpoints, not live collaboration edits
or durable run records.
