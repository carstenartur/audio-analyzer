# Experiment Workflow Model Mapping

**Issue**: [#214](https://github.com/carstenartur/audio-analyzer/issues/214)  
**Authority**: Spike note (informational)

## Goal

Map the existing `audio-core` workflow model to experiment configurations so that no new domain
types are introduced unnecessarily before the first implementation slice.

---

## Existing classes and their experiment roles

|    Class / constant    |                                                                      Experiment role                                                                       |
|------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Workflow`             | The experiment graph itself – a named, versioned directed graph with stable ID.                                                                            |
| `Node`                 | An experiment component (e.g. `RecordingInput`, `Gain`, `FFT`, `Classifier`). The `type` field maps to a catalog entry; `label` is the display name.       |
| `Port`                 | A typed connection endpoint on a node. `direction` (INPUT/OUTPUT), `dataType`, `required` and `multiplicity` describe the data contract for each endpoint. |
| `Edge`                 | A directional data flow between two ports (`sourceNodeId:sourcePortId → targetNodeId:targetPortId`).                                                       |
| `DataType`             | The domain-level data type exchanged over a port edge (e.g. `AudioBlock`, `Spectrum`, `FeatureSet`).                                                       |
| `DataTypes`            | Canonical built-in type constants matching Audio Analyzer's existing domain (Dataset, AudioBlock, Spectrum, FeatureSet, ClassificationResult, ...).        |
| `Metadata`             | Extensible key-value bag on `Workflow`, `Node`, `Port` and `Edge`. Suitable for rendering hints, group tags and custom properties without model bloat.     |
| `WorkflowOperation`    | Semantic edit: CreateNode, DeleteNode, ConnectPorts, DisconnectPorts, UpdateProperty, GroupNodes etc. All experiment edits are expressed as these values.  |
| `WorkflowOperationLog` | Ordered log of applied operations enabling replay and undo/redo.                                                                                           |
| `WorkflowValidator`    | Structural validation (no dangling edges, no duplicate IDs). Type compatibility of ports must be validated here, not in editor code.                       |
| `ExecutionSnapshot`    | Immutable freeze of a workflow graph taken before execution starts. Each experiment run uses a snapshot, not the live editable workflow.                   |
| `ExecutionPlan`        | Topologically sorted run order derived from a snapshot.                                                                                                    |
| `ExecutionContext`     | Per-node status tracking during a run (IDLE → QUEUED → RUNNING → COMPLETED/FAILED).                                                                        |

---

## What is already sufficient

The following first-experiment requirements are already met by the existing model:

- **Graph structure**: `Workflow`, `Node`, `Port`, `Edge` express the full directed graph.
- **Type safety at ports**: `DataType` + `Port.required` + `Port.multiplicity` cover the data
  contract for experiment connections.
- **Edit semantics**: `WorkflowOperation` and `WorkflowOperationLog` cover all needed structural
  edits (add/remove nodes and edges, rename, move, update properties).
- **Undo/redo**: `WorkflowOperationLog.undoLast()` and replay() give operation-based undo.
- **Execution isolation**: `ExecutionSnapshot` freezes the graph before any run.

---

## Minimal additions identified

|                 Addition                 |                                                    Reason                                                    |       Layer        |
|------------------------------------------|--------------------------------------------------------------------------------------------------------------|--------------------|
| `workflow.catalog.ExperimentNodeCatalog` | Factory methods for typed node prototypes (issue #215). Avoids inventing ad-hoc string constants everywhere. | Workflow domain    |
| `workflow.dsl.WorkflowDslSerializer`     | Deterministic text representation for Git checkpoints, semantic diffs and human review (issue #217).         | DSL layer          |
| `workflow.dsl.WorkflowDslParser`         | Inverse of the serializer. Required for loading persisted workflows back into the domain model.              | DSL layer          |
| `workflow.store.VersionedWorkflowStore`  | Facade that hides JGit and Hibernate internals from all Audio Analyzer workflow services (issue #218).       | Persistence facade |

---

## Rejected additions

|         Rejected concept         |                                                          Reason                                                           |
|----------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| Separate `ExperimentGraph` type  | The existing `Workflow` aggregate is sufficient. A parallel type would duplicate logic and split the model.               |
| Layout / position fields on Node | Viewport and positioning state must not enter the semantic graph. Layout belongs in a separate adapter layer.             |
| Presence or cursor fields        | Collaboration presence is transport state, not workflow domain state (see ADR-006).                                       |
| Execution fields on Node         | `ExecutionStatus` and `ExecutionResult` belong in `workflow.execution`, not the design-time model.                        |
| Separate `DatasetRef` type       | `Dataset` is already a `DataType`. Dataset references are node parameters expressed via `Metadata`, not new domain types. |

---

## Conclusion

`Node`, `Port`, `Edge`, `DataType` and `WorkflowOperation` are sufficient for the first experiment
catalog. The additions above are the minimum needed to serialize, persist and execute the graph;
none of them touch the core `Workflow` aggregate.
