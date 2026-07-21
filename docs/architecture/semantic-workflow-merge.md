# Semantic workflow diff and three-way merge

Audio Analyzer compares and merges exact immutable workflow checkpoints by domain identity rather than by textual DSL line positions.

The feature is intentionally a **version-level** capability:

- live collaboration remains an ordered sequence of semantic session operations;
- Git stores explicit workflow checkpoints;
- merge loads one exact base, local and remote checkpoint;
- explicit conflict decisions produce one new checkpoint on the target branch;
- the deterministic workflow DSL remains the committed authority.

A branch merge is not represented as one live `WorkflowOperation`, and the browser never performs the canonical merge locally.

## Architectural boundary

```text
React Flow merge drawer
  -> transport-safe preview/resolve requests
  -> WorkflowMergeCommandService
  -> WorkflowThreeWayMerger
  -> WorkflowValidator
  -> WorkflowDslSerializer
  -> VersionedWorkflowStore.commitIfHead
  -> Hibernate-backed JGit commit/ref update
```

`audio-core` owns the framework-independent diff, merge, conflict and resolution contracts. It has no Spring, React, JGit, Hibernate or Lucene dependency.

`audio-app` owns:

- Spring configuration;
- REST request/response models;
- RFC 9457 error mapping;
- the concrete `VersionedWorkflowStore` implementation.

`audio-web-editor` owns only presentation and user intent. It receives complete server projections and conflict evidence; it contains no parser, graph-merging algorithm, JGit type or Hibernate type.

## Exact input model

Every preview identifies:

- target branch;
- remote branch;
- exact base commit;
- exact local commit;
- exact remote commit.

The service enforces reachability before parsing any snapshot:

```text
base   reachable from target branch and remote branch
local  reachable from target branch
remote reachable from remote branch
```

All three snapshots must carry the same workflow id in both `workflow.id` and deterministic `workflow.dsl`.

The service rejects a mismatching identity rather than trying to merge unrelated workflows.

## Complete semantic diff

The established `workflow.history.WorkflowDiff` API now reports the complete domain surface while keeping older change variants compatible.

Whole-object changes remain:

- node added;
- node removed;
- edge added;
- edge removed.

Existing metadata changes on nodes and edges remain `ParameterChanged`, preserving existing HTTP consumers.

`FieldChanged` covers values that were previously missing from the comparison model:

- workflow name;
- workflow metadata entries;
- node type;
- node label;
- complete ordered input-port declaration;
- complete ordered output-port declaration;
- edge source/target endpoints.

Every change is based on stable identifiers. Canonical value formatting is deterministic and escapes strings explicitly; it is evidence for comparison/conflict display, not a second persistence format.

## Three-way merge rules

For one semantic value with base `B`, local `L` and remote `R`:

```text
L == R        -> use L
L == B        -> use R
R == B        -> use L
otherwise     -> conflict
```

This rule applies independently to workflow fields, metadata keys, node fields and edge metadata.

Nodes and edges are indexed by stable ids. Collections are emitted in deterministic stable-id order.

### Automatically merged changes

Examples:

- local renames one node while remote changes metadata on another node;
- local changes workflow metadata key `owner`, remote adds key `purpose`;
- both sides make the same scalar change;
- local adds one independent node, remote adds another independent node;
- one side remains equal to base while the other changes an edge endpoint.

### Typed conflicts

`DIVERGENT_VALUE`
:   Both branches changed the same scalar or metadata key differently.

`STABLE_ID_COLLISION`
:   Both branches independently added different node or edge snapshots with the same stable id.

`DELETE_MODIFY`
:   One branch deleted an existing object while the other modified it.

`DELETE_CONNECT`
:   One branch deleted a node while the other added or retained a connection that requires that node.

`DIVERGENT_EDGE_ENDPOINTS`
:   Both branches changed the same edge id to different endpoint pairs.

Conflicts are ordered by element kind, stable element id, field path, conflict kind and deterministic conflict id.

## Conservative preview

An unresolved preview never silently chooses local or remote intent.

For ordinary scalar conflicts the preview retains the base value. For whole-node or whole-edge collisions it retains a conservative base/absent neighborhood. Node deletion/connection conflicts are treated as a single node-neighborhood decision so the preview cannot contain a partially selected node with unrelated edges.

The preview contains:

- base graph;
- local graph;
- remote graph;
- automatic candidate graph;
- ordered conflicts;
- structural validation violations;
- `readyToCommit`.

A conflict-free candidate is still not necessarily commit-ready. Independent branch changes can combine into an invalid graph, for example when one branch replaces a port id while the other adds an edge to the old port. `WorkflowValidator` therefore runs on every automatic and explicitly resolved candidate.

## Explicit resolution

Every conflict requires an exact conflict id and one allowed choice:

- `BASE`;
- `LOCAL`;
- `REMOTE`;
- `DELETE`;
- `CUSTOM` for supported scalar values.

The service rejects:

- unknown conflict ids;
- duplicate decisions for one conflict;
- choices not listed by the preview;
- missing custom values;
- custom values for non-custom choices;
- incomplete resolution sets at commit time;
- resolved graphs that fail structural validation.

The browser keeps decisions keyed by server-provided conflict id. It does not infer conflict identity from labels or array positions.

## Optimistic concurrency and commit

A resolve request contains `expectedHeadCommitId`. It must equal the selected local commit.

The service calls:

```text
VersionedWorkflowStore.commitIfHead(
  targetBranch,
  expectedHeadCommit,
  deterministicResolvedSnapshot,
  auditMetadata)
```

If another writer advances the target branch after preview, the existing `StaleWorkflowHeadException` becomes an HTTP 409 response. No merge commit is written from stale local state.

After a successful commit, the service reloads the exact returned commit and parses its deterministic DSL again. The response therefore describes the authoritative stored result, not only the in-memory candidate.

## Audit trail

The existing `CommitMetadata` contract remains the single checkpoint audit boundary. The user message is followed by a deterministic footer:

```text
[workflow-merge]
targetBranch=main
remoteBranch=feature
base=<commit>
local=<commit>
remote=<commit>
resolution.<ordered-conflict-id>=<choice>[:<custom-value>]
```

Resolutions are sorted by conflict id. Re-running the same inputs and decisions produces equivalent workflow DSL and equivalent merge audit content apart from the normal Git commit identity/timestamp inputs.

No merge-specific Hibernate table or JGit-internal type is introduced in Audio Analyzer.

## Branch creation

The history command API can create a new branch from an exact commit reachable from a source branch:

```text
POST /workflow/history/branches
```

The operation uses `VersionedWorkflowStore.updateRef(newBranch, null, fromCommit)`. It does not overwrite an existing branch and does not copy workflow data.

This capability supports real feature-branch workflows and the packaged merge E2E scenario without test-only SQL or direct JGit calls.

## HTTP API

### Preview

```text
POST /workflow/history/merge/preview
```

```json
{
  "targetBranch": "main",
  "remoteBranch": "feature",
  "baseCommitId": "...",
  "localCommitId": "...",
  "remoteCommitId": "..."
}
```

### Resolve and commit

```text
POST /workflow/history/merge/resolve
```

```json
{
  "targetBranch": "main",
  "remoteBranch": "feature",
  "baseCommitId": "...",
  "localCommitId": "...",
  "remoteCommitId": "...",
  "expectedHeadCommitId": "...",
  "resolutions": [
    {
      "conflictId": "...",
      "choice": "CUSTOM",
      "customValue": "resolved"
    }
  ],
  "author": "Merger",
  "message": "Resolve workflow conflict",
  "timestamp": "2026-07-21T08:00:00Z"
}
```

Rejected semantic candidates return an RFC 9457 problem with code `WORKFLOW_MERGE_REJECTED`, ordered unresolved conflicts and validator diagnostics. A stale target branch returns `STALE_WORKFLOW_HEAD`.

## React Flow workbench

The packaged workbench exposes a **Merge workflow versions** drawer in Hibernate persistence mode.

The user:

1. enters target and remote branch names;
2. loads exact branch histories;
3. selects base, local and remote commits;
4. requests a server preview;
5. inspects base/local/remote canonical evidence for every conflict;
6. selects one allowed decision per conflict;
7. supplies a custom value where required;
8. enters audit author and message;
9. commits only when the server preview is fully resolved and valid;
10. may load the exact resulting commit into the workbench.

The drawer sends the selected local commit as the expected target HEAD. It never reconstructs workflow snapshots from current browser graph state.

## Verification

The implementation is covered by:

- complete semantic-diff unit tests;
- deterministic three-way merge tests for independent changes and all conflict classes;
- invalid combined-graph tests;
- store-neutral application-service tests with real branch histories;
- Hibernate-backed JGit branch/merge/reload integration tests;
- REST controller and RFC 9457 problem tests;
- Node-native reducer/request tests for the merge drawer;
- packaged Playwright/Testcontainers evidence that creates two real branches, commits divergent property values, resolves the conflict through visible UI and loads the exact merged commit;
- architecture rules preventing JGit/Hibernate/browser implementation leakage into `audio-core`.

The authoritative result is always the deterministic DSL loaded from the exact merge commit.
