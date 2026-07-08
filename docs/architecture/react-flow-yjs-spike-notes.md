# React Flow / Yjs Spike Notes

**Issue**: [#220](https://github.com/carstenartur/audio-analyzer/issues/220)  
**Authority**: Spike result (informational)  
**Status**: Complete — findings fed into [ADR-007](adr-007-editor-stack.md)

---

## Goal

Evaluate React Flow + Yjs as the graph editor for experiment workflows by prototyping the same
`Input → Gain → Output` graph as the GLSP spike. The canonical workflow state must remain in
`audio-core Workflow → WorkflowOperationLog → VersionedWorkflowStore`. React Flow and Yjs are
adapter and helper layers only.

---

## Stack overview

|           Component            |                                   Role in the adapter                                   |
|--------------------------------|-----------------------------------------------------------------------------------------|
| React Flow (`reactflow` npm)   | Node/edge rendering, drag-and-drop layout, viewport state                               |
| HTTP/WebSocket adapter (Java)  | Receives `WorkflowOperation` requests from the browser                                  |
| `WorkflowEditorService` (Java) | Validates and applies `WorkflowOperation` values; returns updated `Workflow` projection |
| Yjs (optional, scoped)         | Awareness state (user cursors, presence); optimistic layout helpers                     |

---

## Prototype: rendering `Input → Gain → Output`

React Flow represents the graph as two plain arrays: `nodes[]` and `edges[]`. These are populated
from the server response:

```typescript
// Server response DTO (simplified)
interface WorkflowProjection {
  nodes: WorkflowNodeDto[];
  edges: WorkflowEdgeDto[];
}

// Mapped to React Flow state
const [rfNodes, setRfNodes] = useState<Node[]>([]);
const [rfEdges, setRfEdges] = useState<Edge[]>([]);

async function loadWorkflow(branch: string) {
  const projection = await api.loadHead(branch);
  setRfNodes(toReactFlowNodes(projection.nodes));
  setRfEdges(toReactFlowEdges(projection.edges));
}
```

**Typed ports as React Flow handles**: each `audio-core Port` maps to a React Flow `Handle`
component inside a custom node. The `dataType` identifier is passed as a prop so the handle can be
colour-coded:

```typescript
function WorkflowNode({ data }: { data: WorkflowNodeData }) {
  return (
    <div className="workflow-node">
      {data.inputPorts.map(port => (
        <Handle
          key={port.id}
          id={port.id}
          type="target"
          position={Position.Left}
          className={`handle-${port.dataType}`}
        />
      ))}
      <div className="node-label">{data.label}</div>
      {data.outputPorts.map(port => (
        <Handle
          key={port.id}
          id={port.id}
          type="source"
          position={Position.Right}
          className={`handle-${port.dataType}`}
        />
      ))}
    </div>
  );
}
```

**Finding**: the minimal graph is renderable with custom node components. Typed ports are expressed
as `Handle` components with CSS classes. No custom layout engine is required for the first MVP;
React Flow's built-in drag-and-drop is sufficient.

---

## Edge operation prototype

### Valid edge: `SyntheticSignalGenerator::signal-out → Gain::audio-in`

React Flow fires `onConnect` when the user drags from a source handle to a target handle:

```typescript
async function onConnect(connection: Connection) {
  const result = await api.post('/workflow/operations', {
    type: 'CONNECT_PORTS',
    edgeId: generateId(),
    sourceNodeId: connection.source,
    sourcePortId: connection.sourceHandle,
    targetNodeId: connection.target,
    targetPortId: connection.targetHandle,
  });
  if (result.ok) {
    // Update React Flow state from authoritative server projection
    setRfEdges(result.projection.edges.map(toReactFlowEdge));
  } else {
    // Show validation error; React Flow state is not modified
    showError(result.error);
  }
}
```

Server-side (`WorkflowEditorService`):

```java
public WorkflowProjection applyOperation(WorkflowOperation op) {
    WorkflowValidator validator = new WorkflowValidator(currentWorkflow);
    List<String> errors = validator.validate(op);
    if (!errors.isEmpty()) {
        throw new WorkflowOperationRejectedException(errors);
    }
    operationLog.apply(op);
    return WorkflowProjection.from(operationLog.currentWorkflow());
}
```

**Finding**: valid edge creation works. Server validates the operation before accepting it; React
Flow state is updated only from the server response.

### Invalid edge: `RecordingInput::audio-out → Gain::audio-in` (Dataset → AudioBlock mismatch)

The same `onConnect` handler fires the HTTP request. The server returns HTTP 422 with:

```json
{ "error": "Type mismatch: Dataset cannot connect to AudioBlock port" }
```

The browser shows the error message. React Flow's optimistic edge is not added to the permanent
edge list; the state rolls back.

**Finding**: invalid edges are rejected by `WorkflowValidator` at the server; the React Flow state
never permanently accepts the invalid edge.

---

## Parameter edit prototype

React Flow custom nodes include an editable label or parameter field. On change, the browser sends:

```typescript
async function onParameterChange(nodeId: string, key: string, value: string) {
  const result = await api.post('/workflow/operations', {
    type: 'UPDATE_PROPERTY',
    nodeId,
    key,
    value,
  });
  if (result.ok) {
    setRfNodes(result.projection.nodes.map(toReactFlowNode));
  }
}
```

**Finding**: parameter edits map cleanly to `WorkflowOperation.updateProperty`. Server remains
authoritative.

---

## Yjs evaluation: what it may own and what it must not own

### What Yjs may own

|              Use case              |                                   Why it is safe                                    |
|------------------------------------|-------------------------------------------------------------------------------------|
| User cursor / awareness positions  | Awareness state is ephemeral; not stored in `VersionedWorkflowStore`                |
| Optimistic client-side layout drag | Layout position is adapter-layer state, not semantic workflow state                 |
| Local undo for layout-only moves   | Layout undo stays in the browser; semantic undo goes through `WorkflowOperationLog` |

### What Yjs must not own

|        Forbidden use         |                                 Reason                                  |
|------------------------------|-------------------------------------------------------------------------|
| Canonical workflow graph     | Semantic state must remain in `audio-core Workflow`                     |
| Durable workflow history     | History belongs in `VersionedWorkflowStore`                             |
| Semantic conflict resolution | CRDT merges must not silently hide type-incompatible edge conflicts     |
| `WorkflowOperation` ordering | Replay order must be deterministic and stored in `WorkflowOperationLog` |

**Boundary rule**: if a Yjs document change cannot be round-tripped through `WorkflowOperation`
without loss, that change must not be allowed in the Yjs document. Yjs documents are always
derived, never canonical.

**Finding**: Yjs awareness (user cursors, presence) is optional and additive. It can be introduced
for collaborative sessions without making the Yjs document the canonical store. Semantic conflicts
are not hidden by CRDT merges because every semantic edit goes through the server before being
accepted.

---

## Integration cost assessment

### Setup complexity

|              Requirement               |      Effort       |
|----------------------------------------|-------------------|
| React Flow npm package                 | Low               |
| Custom node components for typed ports | Low–Medium        |
| HTTP/WebSocket adapter (Java)          | Low               |
| `WorkflowEditorService` (Java)         | Low               |
| Yjs awareness (optional, additive)     | Low (if deferred) |

A developer can start with a running graph by installing `reactflow`, writing two custom node
components and one `WorkflowEditorService` class. No extension host or language server is required.

### Single-model advantage

React Flow state is always populated from the server projection. There is no second canonical model
to keep in sync:

```
WorkflowOperation
  → server validates and applies
  → server returns WorkflowProjection
  → React Flow state ← WorkflowProjection
```

If the server projection has a bug, the React Flow state reflects that bug visibly in the UI.
There is no silent divergence between two parallel models.

### Test surface

The adapter is a narrow HTTP layer. Tests can exercise it with plain HTTP requests and assert on
the `WorkflowProjection` response without running a browser:

```java
@Test
void connectPortsWithTypeMatchIsAccepted() {
    WorkflowEditorService service = new WorkflowEditorService(startWorkflow());
    WorkflowOperation op = WorkflowOperation.connectPorts(...);
    WorkflowProjection result = service.applyOperation(op);
    assertTrue(result.edges().stream().anyMatch(...));
}

@Test
void connectPortsWithTypeMismatchIsRejected() {
    WorkflowEditorService service = new WorkflowEditorService(startWorkflow());
    WorkflowOperation op = WorkflowOperation.connectPorts(...); // Dataset → AudioBlock
    assertThrows(WorkflowOperationRejectedException.class, () -> service.applyOperation(op));
}
```

### Strengths

- Low barrier to entry: one npm install and two Java classes.
- Single-model design: React Flow state is always a derived view of the server projection.
- Typed ports work as custom `Handle` components without framework ceremony.
- Yjs awareness is optional and strictly scoped to ephemeral state.
- Test surface is small: assert on `WorkflowProjection`, not on rendered DOM.
- No dual-model synchronization complexity.

### Weaknesses

- No built-in model-driven constraint enforcement: typed port rules must be written explicitly in
  `WorkflowValidator`.
- Optimistic updates can drift if the adapter carelessly updates React Flow state before the server
  confirms the operation (mitigated by the design above that only updates from server response).
- Yjs document state must be disciplined to avoid becoming a shadow canonical store.
- React Flow handles layout as client state; if server-side deterministic layout is required later,
  an additional layout service must be introduced.

---

## Spike conclusion

React Flow + Yjs meets all spike acceptance criteria:

- Minimal graph is visible with typed port rendering.
- Valid edge operations work through server-validated `WorkflowOperation` calls.
- Invalid edges are rejected by `WorkflowValidator` before the React Flow state is updated.
- Parameter edits map cleanly to `WorkflowOperation.updateProperty`.
- Yjs may own awareness/presence and optimistic layout; it must not own canonical workflow state.

Adapter complexity is significantly lower than the GLSP spike. A developer working on this layer
needs to understand one narrow HTTP/WebSocket adapter interface and one `WorkflowEditorService`
class. The `audio-core` workflow domain is not exposed to React or Yjs concepts.

See [ADR-007](adr-007-editor-stack.md) for the final decision.
