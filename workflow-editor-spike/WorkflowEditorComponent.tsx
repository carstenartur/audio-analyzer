/**
 * Minimal React Flow spike for the Input → Gain → Output workflow editor.
 *
 * Design contract (ADR-007):
 *   - React Flow state is derived entirely from server WorkflowProjection.
 *   - Every user gesture (connect, delete, property change) fires a POST to
 *     /workflow/operations and updates state only from the server response.
 *   - Yjs (if added later) may own cursor/awareness/layout viewport — not
 *     canonical workflow nodes or edges.
 *
 * Server API surface (WorkflowEditorService in audio-core):
 *   GET  /workflow/projection      → WorkflowProjection (initial load)
 *   POST /workflow/operations      → WorkflowProjection | 422 { violations }
 *
 * WorkflowProjection JSON shape (see WorkflowProjection.java):
 * {
 *   "workflowId": "workflow.spike",
 *   "workflowName": "Input-Gain-Output Spike",
 *   "nodes": [
 *     { "id": "node.input",  "type": "audioNode", "label": "Synthetic Signal Generator",
 *       "inputHandles": [],
 *       "outputHandles": [{ "id": "signal-out", "name": "Synthetic Signal", "dataType": "AudioBlock" }] },
 *     { "id": "node.gain",   "type": "audioNode", "label": "Gain",
 *       "inputHandles":  [{ "id": "audio-in",  "name": "Audio In",  "dataType": "AudioBlock" }],
 *       "outputHandles": [{ "id": "audio-out", "name": "Audio Out", "dataType": "AudioBlock" }] },
 *     { "id": "node.output", "type": "audioNode", "label": "Output",
 *       "inputHandles":  [{ "id": "audio-in",  "name": "Audio In",  "dataType": "AudioBlock" }],
 *       "outputHandles": [] }
 *   ],
 *   "edges": []
 * }
 */

import React, { useCallback, useEffect, useState } from 'react';
import ReactFlow, {
  Background,
  Connection,
  Controls,
  Edge,
  EdgeChange,
  Handle,
  Node,
  NodeProps,
  Position,
  useEdgesState,
  useNodesState,
} from 'reactflow';
import 'reactflow/dist/style.css';

// ---------------------------------------------------------------------------
// Types mirroring WorkflowProjection records
// ---------------------------------------------------------------------------

interface HandleProjection {
  id: string;
  name: string;
  dataType: string;
}

interface NodeProjection {
  id: string;
  type: string;
  label: string;
  inputHandles: HandleProjection[];
  outputHandles: HandleProjection[];
}

interface EdgeProjection {
  id: string;
  source: string;
  sourceHandle: string;
  target: string;
  targetHandle: string;
}

interface WorkflowProjection {
  workflowId: string;
  workflowName: string;
  nodes: NodeProjection[];
  edges: EdgeProjection[];
}

// ---------------------------------------------------------------------------
// Data-type colour map (extend as new DataTypes are added to audio-core)
// ---------------------------------------------------------------------------

const DATA_TYPE_COLORS: Record<string, string> = {
  AudioBlock: '#4a90d9',
  Dataset: '#7b68ee',
  Spectrum: '#e67e22',
  FeatureSet: '#27ae60',
  ClassificationResult: '#e74c3c',
};

function handleColor(dataType: string): string {
  return DATA_TYPE_COLORS[dataType] ?? '#888';
}

// ---------------------------------------------------------------------------
// Typed-port custom node
// ---------------------------------------------------------------------------

function AudioNode({ data }: NodeProps) {
  const { label, inputHandles, outputHandles } = data as NodeProjection;
  return (
    <div style={{ border: '1px solid #555', borderRadius: 6, padding: '8px 12px', background: '#1e1e2e', color: '#cdd6f4', minWidth: 140 }}>
      {inputHandles.map((h: HandleProjection) => (
        <Handle
          key={h.id}
          type="target"
          position={Position.Left}
          id={h.id}
          title={`${h.name} (${h.dataType})`}
          style={{ background: handleColor(h.dataType), width: 10, height: 10 }}
        />
      ))}
      <div style={{ textAlign: 'center', fontSize: 12, fontWeight: 600 }}>{label}</div>
      {outputHandles.map((h: HandleProjection) => (
        <Handle
          key={h.id}
          type="source"
          position={Position.Right}
          id={h.id}
          title={`${h.name} (${h.dataType})`}
          style={{ background: handleColor(h.dataType), width: 10, height: 10 }}
        />
      ))}
    </div>
  );
}

const NODE_TYPES = { audioNode: AudioNode };

// ---------------------------------------------------------------------------
// Projection → React Flow converters
// ---------------------------------------------------------------------------

function toReactFlowNodes(nodeProjections: NodeProjection[]): Node[] {
  return nodeProjections.map((np, index) => ({
    id: np.id,
    type: 'audioNode',
    position: { x: 80 + index * 220, y: 200 },
    data: np,
  }));
}

function toReactFlowEdges(edgeProjections: EdgeProjection[]): Edge[] {
  return edgeProjections.map((ep) => ({
    id: ep.id,
    source: ep.source,
    sourceHandle: ep.sourceHandle,
    target: ep.target,
    targetHandle: ep.targetHandle,
  }));
}

// ---------------------------------------------------------------------------
// HTTP adapter
// ---------------------------------------------------------------------------

async function fetchProjection(): Promise<WorkflowProjection> {
  const res = await fetch('/workflow/projection');
  if (!res.ok) throw new Error(`Failed to load projection: ${res.status}`);
  return res.json() as Promise<WorkflowProjection>;
}

async function postOperation(operation: unknown): Promise<WorkflowProjection> {
  const res = await fetch('/workflow/operations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(operation),
  });
  if (res.status === 422) {
    const body = await res.json() as { violations: string[] };
    throw new Error(`Operation rejected: ${body.violations.join('; ')}`);
  }
  if (!res.ok) throw new Error(`Server error: ${res.status}`);
  return res.json() as Promise<WorkflowProjection>;
}

// ---------------------------------------------------------------------------
// Main editor component
// ---------------------------------------------------------------------------

export default function WorkflowEditorComponent() {
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChangeDefault] = useEdgesState([]);
  const [workflowName, setWorkflowName] = useState('');
  const [error, setError] = useState<string | null>(null);

  // Apply a projection from the server (the ONLY way state is mutated)
  const applyProjection = useCallback((projection: WorkflowProjection) => {
    setWorkflowName(projection.workflowName);
    setNodes(toReactFlowNodes(projection.nodes));
    setEdges(toReactFlowEdges(projection.edges));
    setError(null);
  }, [setNodes, setEdges]);

  // Initial load
  useEffect(() => {
    fetchProjection().then(applyProjection).catch((err: Error) => setError(err.message));
  }, [applyProjection]);

  // Valid edge connection: fire ConnectPorts operation, update from server response
  const onConnect = useCallback(
    (connection: Connection) => {
      const edge = {
        id: `edge.${connection.source}-${connection.sourceHandle}-to-${connection.target}-${connection.targetHandle}`,
        sourceNodeId: connection.source,
        sourcePortId: connection.sourceHandle,
        targetNodeId: connection.target,
        targetPortId: connection.targetHandle,
      };
      const operation = {
        type: 'ConnectPorts',
        operationId: `op.connect.${Date.now()}`,
        author: 'web-editor',
        edge,
      };
      postOperation(operation)
        .then(applyProjection)
        .catch((err: Error) => {
          // No edge was committed to state; UI stays on last accepted projection
          setError(err.message);
        });
    },
    [applyProjection],
  );

  // Edge change interceptor: intercept "remove" changes and fire DisconnectPorts instead
  // of allowing React Flow to remove the edge from local state immediately.
  // The edge stays in the UI until the server confirms removal (server-authoritative).
  const onEdgesChange = useCallback(
    (changes: EdgeChange[]) => {
      const nonRemoveChanges = changes.filter((c) => c.type !== 'remove');
      onEdgesChangeDefault(nonRemoveChanges);

      const removeChanges = changes.filter((c) => c.type === 'remove');
      for (const change of removeChanges) {
        const edge = edges.find((e) => e.id === change.id);
        if (!edge) continue;
        const operation = {
          type: 'DisconnectPorts',
          operationId: `op.disconnect.${Date.now()}`,
          author: 'web-editor',
          edgeId: edge.id,
          disconnectedEdge: {
            id: edge.id,
            sourceNodeId: edge.source,
            sourcePortId: edge.sourceHandle ?? '',
            targetNodeId: edge.target,
            targetPortId: edge.targetHandle ?? '',
          },
        };
        postOperation(operation)
          .then(applyProjection)
          .catch((err: Error) => setError(err.message));
      }
    },
    [onEdgesChangeDefault, edges, applyProjection],
  );

  // Parameter update: fire UpdateProperty, update from server response
  const onParameterChange = useCallback(
    (nodeId: string, key: string, value: string) => {
      const operation = {
        type: 'UpdateProperty',
        operationId: `op.update.${Date.now()}`,
        author: 'web-editor',
        target: 'NODE',
        targetId: nodeId,
        propertyKey: key,
        newValue: value,
      };
      postOperation(operation)
        .then(applyProjection)
        .catch((err: Error) => setError(err.message));
    },
    [applyProjection],
  );
  void onParameterChange; // exposed for host components; not used in this minimal spike

  return (
    <div style={{ width: '100vw', height: '100vh' }}>
      <div style={{ padding: '8px 16px', background: '#181825', color: '#cdd6f4', fontSize: 14 }}>
        {workflowName}
        {error && (
          <span style={{ marginLeft: 16, color: '#f38ba8' }}>⚠ {error}</span>
        )}
      </div>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        nodeTypes={NODE_TYPES}
        fitView
      >
        <Background />
        <Controls />
      </ReactFlow>
    </div>
  );
}
