# GLSP Spike Notes

**Issue**: [#219](https://github.com/carstenartur/audio-analyzer/issues/219)  
**Authority**: Spike result (informational)  
**Status**: Complete — findings fed into [ADR-007](adr-007-editor-stack.md)

---

## Goal

Evaluate GLSP as the model-driven, server-authoritative graph editor for experiment workflows by
prototyping the `Input → Gain → Output` graph. The canonical workflow state must remain in
`audio-core Workflow → WorkflowOperationLog → VersionedWorkflowStore`; GLSP is only the editor
adapter.

---

## GLSP stack overview

|      Component      |                            Role in the adapter                             |
|---------------------|----------------------------------------------------------------------------|
| `GModelState`       | Holds the current `GGraph` (GModel) derived from `audio-core Workflow`     |
| `OperationHandler`  | Translates each GLSP action to a `WorkflowOperation`                       |
| `GModelFactory`     | Rebuilds the `GGraph` projection after every `WorkflowOperation`           |
| GLSP server process | Java process (Vert.x or Spring); communicates with the client via JSON-RPC |
| GLSP client         | TypeScript; runs inside a VS Code extension host or Eclipse Theia          |

---

## Prototype: rendering `Input → Gain → Output`

The server-side projection from `audio-core Workflow` to `GGraph` requires three mapping steps:

1. **Nodes** — each `audio-core Node` becomes a `GNode` with a `GLabel` and one `GCompartment`
   per port group (inputs / outputs).
2. **Ports** — each `audio-core Port` becomes a `GPort` inside the corresponding compartment.
   The `dataType` identifier is stored as a `GPort` CSS class so the client can colour-code it.
3. **Edges** — each `audio-core Edge` becomes a `GEdge` connecting the source `GPort` to the
   target `GPort`.

The resulting `GGraph` for the minimal workflow:

```
GGraph
  GNode [id=node.input]
    GCompartment [outputs]
      GPort [id=node.input::audio-out, class=datatype-AudioBlock]
  GNode [id=node.gain]
    GCompartment [inputs]
      GPort [id=node.gain::audio-in, class=datatype-AudioBlock]
    GCompartment [outputs]
      GPort [id=node.gain::audio-out, class=datatype-AudioBlock]
  GNode [id=node.output]
    GCompartment [inputs]
      GPort [id=node.output::audio-in, class=datatype-AudioBlock]
  GEdge [id=edge.input-gain, source=node.input::audio-out, target=node.gain::audio-in]
  GEdge [id=edge.gain-output, source=node.gain::audio-out, target=node.output::audio-in]
```

**Finding**: the minimal graph is renderable. Port types are expressible as CSS classes or GModel
properties. Layout is handled by GLSP's built-in ELK integration and does not enter the
`audio-core` model.

---

## Edge operation prototype

### Valid edge: `SyntheticSignalGenerator::signal-out → Gain::audio-in`

GLSP fires a `CreateEdgeOperation`:

```json
{
  "kind": "createEdge",
  "elementTypeId": "workflow:edge",
  "sourceElementId": "node.gen::signal-out",
  "targetElementId": "node.gain::audio-in"
}
```

The `WorkflowOperationHandler` (Audio Analyzer server side) translates this:

```java
WorkflowOperation op = WorkflowOperation.connectPorts(
    newEdgeId(),
    "node.gen", "signal-out",
    "node.gain", "audio-in");
WorkflowValidator validator = new WorkflowValidator(currentWorkflow);
List<String> errors = validator.validate(op);
if (errors.isEmpty()) {
    operationLog.apply(op);
    gModelState.updateFrom(operationLog.currentWorkflow());
}
// Return GModelDelta to GLSP client
```

**Finding**: valid edge creation works. The server remains authoritative.

### Invalid edge: `RecordingInput::audio-out → Gain::audio-in` (Dataset → AudioBlock mismatch)

The same `CreateEdgeOperation` is fired. `WorkflowValidator` checks type compatibility:

```
source port dataType: Dataset
target port dataType: AudioBlock
→ type mismatch → operation rejected
```

The GLSP client receives an `ActionMessage` with `kind: "serverStatus"` carrying the rejection
message. The `GGraph` is not updated.

**Finding**: invalid edges are rejected at the application service layer before reaching the
`GModel`; the GModel is never modified for rejected operations.

---

## Parameter edit prototype

GLSP fires a `ChangeBoundsOperation` or a custom `ChangePropertyOperation` when the user edits a
node label or property panel value:

```json
{
  "kind": "changeProperty",
  "elementId": "node.gain",
  "propertyId": "gain-factor",
  "value": "2.5"
}
```

The handler translates this to:

```java
WorkflowOperation op = WorkflowOperation.updateProperty(
    "node.gain", "gain-factor", "2.5");
operationLog.apply(op);
gModelState.updateFrom(operationLog.currentWorkflow());
```

**Finding**: parameter edits translate cleanly to `WorkflowOperation.updateProperty`. Server
remains authoritative.

---

## Integration cost assessment

### Setup complexity

|                 Requirement                  | Effort |
|----------------------------------------------|--------|
| GLSP server (Java, Vert.x/Spring)            | High   |
| GLSP client (TypeScript, VS Code extension)  | High   |
| Eclipse Theia host or VS Code extension host | High   |
| ELK layout wiring                            | Medium |
| GModel ↔ audio-core Workflow synchronization | Medium |

Minimum viable GLSP editor requires five Maven/npm artefacts and a running Theia or VS Code host.
A developer unfamiliar with the GLSP ecosystem needs to understand the full JSON-RPC protocol,
`GModelState`, `OperationHandler` lifecycle, and the React/Sprotty client model before writing a
single test.

### Two-model overhead

Every state mutation requires two consistent updates:

```
WorkflowOperation applied to audio-core Workflow
  → GModelFactory rebuilds GGraph projection
  → GModel delta sent to client
```

If `GModelFactory` has a bug, the GModel diverges silently from `audio-core Workflow`. Tests must
cover both layers, doubling the assertion surface.

### Strengths

- Port and connector model is natively expressed in GModel.
- ELK provides automatic layout without custom code.
- The server-authoritative pattern is encouraged by the GLSP design (actions flow through the
  server before the GModel is updated).
- Eclipse and Theia tooling integration is strong for enterprise or IDE-based tooling.

### Weaknesses

- High initial setup: Theia/VS Code extension host required before any graph is visible.
- Steep learning curve: GModel, GLSP JSON-RPC, Sprotty renderer, ELK all require separate
  understanding.
- Two-model overhead: GModel and audio-core Workflow must be kept in sync explicitly.
- Large framework footprint for a first research-workbench MVP.
- Testing the GModel projection layer requires either a running GLSP server or deep mock
  infrastructure.

---

## Spike conclusion

GLSP can implement the required server-authoritative architecture. The integration pattern is
sound: GModel is a pure derived projection; `WorkflowOperationHandler` is the only code that
modifies `audio-core Workflow`. All spike acceptance criteria are met in the prototype.

However, the setup complexity and two-model overhead are significant relative to the scope of a
first research workbench. A developer needs to maintain knowledge of the GLSP protocol, the GModel
projection and the audio-core domain model simultaneously. This increases the cognitive load at the
adapter layer.

See [ADR-007](adr-007-editor-stack.md) for the final decision.
