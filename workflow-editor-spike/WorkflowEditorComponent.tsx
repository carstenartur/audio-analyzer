/**
 * React Flow workbench MVP for the Input → Gain → Output workflow editor.
 *
 * Design contract (ADR-007 / issue #210):
 *   - React Flow state is derived from server WorkflowProjection.
 *   - Every semantic user gesture fires a WorkflowOperation request and updates state only from the
 *     server response.
 *   - Save, reload and history go through application-service endpoints; the UI never talks to DSL,
 *     JGit or storage internals.
 *   - Yjs (if added later) may own cursor/awareness/layout viewport — not canonical workflow nodes
 *     or edges.
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
  properties: Record<string, string>;
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

interface CatalogEntry {
  type: string;
  label: string;
  inputHandles: HandleProjection[];
  outputHandles: HandleProjection[];
}

interface HistoryEntry {
  commitId: string;
  workflowId: string;
  author: string;
  message: string;
  timestamp: string;
}

interface ValidationResponse {
  violations: string[];
}

interface CheckpointResponse {
  commitId: string;
}

interface WorkflowSnapshot {
  workflowId: string;
  dslText: string;
}

const DATA_TYPE_COLORS: Record<string, string> = {
  AudioBlock: '#4a90d9',
  Dataset: '#7b68ee',
  Spectrum: '#e67e22',
  FeatureSet: '#27ae60',
  ClassificationResult: '#e74c3c',
  LocalizationResult: '#16a085',
  BenchmarkResult: '#d35400',
  Report: '#95a5a6',
};

function handleColor(dataType: string): string {
  return DATA_TYPE_COLORS[dataType] ?? '#888';
}

function AudioNode({ data }: NodeProps) {
  const { label, inputHandles, outputHandles, properties } = data as NodeProjection;
  return (
    <div
      style={{
        border: '1px solid #555',
        borderRadius: 6,
        padding: '8px 12px',
        background: '#1e1e2e',
        color: '#cdd6f4',
        minWidth: 160,
      }}
    >
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
      {Object.keys(properties ?? {}).length > 0 && (
        <div style={{ marginTop: 4, fontSize: 10, opacity: 0.85 }}>
          {Object.entries(properties).map(([key, value]) => (
            <div key={key}>{key}: {value}</div>
          ))}
        </div>
      )}
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

function toReactFlowNodes(nodeProjections: NodeProjection[]): Node[] {
  return nodeProjections.map((np, index) => ({
    id: np.id,
    type: 'audioNode',
    position: { x: 80 + index * 230, y: 220 + (index % 2) * 80 },
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

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${url} failed: ${res.status}`);
  return res.json() as Promise<T>;
}

async function postJson<T>(url: string, body: unknown): Promise<T> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (res.status === 422) {
    const payload = await res.json() as ValidationResponse;
    throw new Error(`Operation rejected: ${payload.violations.join('; ')}`);
  }
  if (!res.ok) throw new Error(`${url} failed: ${res.status}`);
  return res.json() as Promise<T>;
}

function operationId(prefix: string): string {
  return `op.${prefix}.${Date.now()}`;
}

export default function WorkflowEditorComponent() {
  const [projection, setProjection] = useState<WorkflowProjection | null>(null);
  const [catalog, setCatalog] = useState<CatalogEntry[]>([]);
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChangeDefault] = useEdgesState([]);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [propertyKey, setPropertyKey] = useState('gain');
  const [propertyValue, setPropertyValue] = useState('1.5');
  const [branch, setBranch] = useState('main');
  const [checkpointMessage, setCheckpointMessage] = useState('Workbench checkpoint');
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [violations, setViolations] = useState<string[]>([]);
  const [snapshotPreview, setSnapshotPreview] = useState('');
  const [status, setStatus] = useState('');
  const [error, setError] = useState<string | null>(null);

  const applyProjection = useCallback((nextProjection: WorkflowProjection) => {
    setProjection(nextProjection);
    setNodes(toReactFlowNodes(nextProjection.nodes));
    setEdges(toReactFlowEdges(nextProjection.edges));
    setError(null);
  }, [setNodes, setEdges]);

  const refreshProjection = useCallback(() => {
    getJson<WorkflowProjection>('/workflow/projection')
      .then(applyProjection)
      .catch((err: Error) => setError(err.message));
  }, [applyProjection]);

  const refreshHistory = useCallback(() => {
    getJson<HistoryEntry[]>(`/workflow/history?branch=${encodeURIComponent(branch)}&limit=20`)
      .then(setHistory)
      .catch((err: Error) => setError(err.message));
  }, [branch]);

  const refreshValidation = useCallback(() => {
    getJson<ValidationResponse>('/workflow/validation')
      .then((response) => setViolations(response.violations))
      .catch((err: Error) => setError(err.message));
  }, []);

  useEffect(() => {
    refreshProjection();
    refreshValidation();
    getJson<CatalogEntry[]>('/workflow/catalog')
      .then(setCatalog)
      .catch((err: Error) => setError(err.message));
  }, [refreshProjection, refreshValidation]);

  useEffect(() => {
    if (!projection || !selectedNodeId) return;
    const selected = projection.nodes.find((n) => n.id === selectedNodeId);
    if (!selected) return;
    setPropertyValue(selected.properties?.[propertyKey] ?? '');
  }, [projection, selectedNodeId, propertyKey]);

  const postOperation = useCallback((operation: unknown) => {
    postJson<WorkflowProjection>('/workflow/operations', operation)
      .then((nextProjection) => {
        applyProjection(nextProjection);
        refreshValidation();
        setStatus('Graph updated');
      })
      .catch((err: Error) => {
        setError(err.message);
        refreshValidation();
      });
  }, [applyProjection, refreshValidation]);

  const addCatalogNode = useCallback((entry: CatalogEntry) => {
    const nodeId = `node.${entry.type}.${Date.now()}`;
    postOperation({
      type: 'CreateNode',
      operationId: operationId('create'),
      author: 'web-editor',
      nodeId,
      catalogType: entry.type,
    });
    setSelectedNodeId(nodeId);
  }, [postOperation]);

  const onConnect = useCallback((connection: Connection) => {
    const edge = {
      id: `edge.${connection.source}-${connection.sourceHandle}-to-${connection.target}-${connection.targetHandle}`,
      sourceNodeId: connection.source,
      sourcePortId: connection.sourceHandle,
      targetNodeId: connection.target,
      targetPortId: connection.targetHandle,
    };
    postOperation({
      type: 'ConnectPorts',
      operationId: operationId('connect'),
      author: 'web-editor',
      edge,
    });
  }, [postOperation]);

  const onEdgesChange = useCallback((changes: EdgeChange[]) => {
    const nonRemoveChanges = changes.filter((c) => c.type !== 'remove');
    onEdgesChangeDefault(nonRemoveChanges);

    for (const change of changes.filter((c) => c.type === 'remove')) {
      const edge = edges.find((e) => e.id === change.id);
      if (!edge) continue;
      postOperation({
        type: 'DisconnectPorts',
        operationId: operationId('disconnect'),
        author: 'web-editor',
        edgeId: edge.id,
        disconnectedEdge: {
          id: edge.id,
          sourceNodeId: edge.source,
          sourcePortId: edge.sourceHandle ?? '',
          targetNodeId: edge.target,
          targetPortId: edge.targetHandle ?? '',
        },
      });
    }
  }, [onEdgesChangeDefault, edges, postOperation]);

  const updateProperty = useCallback(() => {
    if (!selectedNodeId) return;
    postOperation({
      type: 'UpdateProperty',
      operationId: operationId('update'),
      author: 'web-editor',
      target: 'NODE',
      targetId: selectedNodeId,
      propertyKey,
      previousValue: projection?.nodes.find((n) => n.id === selectedNodeId)?.properties?.[propertyKey] ?? null,
      newValue: propertyValue,
    });
  }, [postOperation, selectedNodeId, propertyKey, propertyValue, projection]);

  const saveCheckpoint = useCallback(() => {
    postJson<CheckpointResponse>('/workflow/checkpoints', {
      branch,
      author: 'web-editor',
      message: checkpointMessage,
    })
      .then((response) => {
        setStatus(`Checkpoint saved: ${response.commitId}`);
        refreshHistory();
      })
      .catch((err: Error) => setError(err.message));
  }, [branch, checkpointMessage, refreshHistory]);

  const loadBranch = useCallback(() => {
    postJson<WorkflowProjection>('/workflow/load', { branch })
      .then((nextProjection) => {
        applyProjection(nextProjection);
        refreshValidation();
        setStatus(`Loaded branch ${branch}`);
      })
      .catch((err: Error) => setError(err.message));
  }, [branch, applyProjection, refreshValidation]);

  const loadCommit = useCallback((commitId: string) => {
    postJson<WorkflowProjection>('/workflow/load', { commitId })
      .then((nextProjection) => {
        applyProjection(nextProjection);
        refreshValidation();
        setStatus(`Loaded commit ${commitId}`);
      })
      .catch((err: Error) => setError(err.message));
  }, [applyProjection, refreshValidation]);

  const loadSnapshotPreview = useCallback(() => {
    getJson<WorkflowSnapshot>('/workflow/snapshot')
      .then((snapshot) => setSnapshotPreview(snapshot.dslText.slice(0, 900)))
      .catch((err: Error) => setError(err.message));
  }, []);

  const selectedNode = projection?.nodes.find((n) => n.id === selectedNodeId) ?? null;

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '280px 1fr 320px', width: '100vw', height: '100vh', background: '#11111b', color: '#cdd6f4' }}>
      <aside style={{ borderRight: '1px solid #313244', padding: 12, overflow: 'auto' }}>
        <h2 style={{ marginTop: 0 }}>Node palette</h2>
        <p style={{ fontSize: 12, opacity: 0.8 }}>Nodes are added through WorkflowOperation.CreateNode.</p>
        {catalog.map((entry) => (
          <button
            key={entry.type}
            onClick={() => addCatalogNode(entry)}
            style={{ display: 'block', width: '100%', marginBottom: 8, padding: 8, textAlign: 'left', background: '#1e1e2e', color: '#cdd6f4', border: '1px solid #45475a', borderRadius: 6 }}
          >
            <strong>{entry.label}</strong>
            <div style={{ fontSize: 11, opacity: 0.75 }}>{entry.type}</div>
            <div style={{ fontSize: 10, opacity: 0.75 }}>in {entry.inputHandles.length} · out {entry.outputHandles.length}</div>
          </button>
        ))}
      </aside>

      <main style={{ minWidth: 0, display: 'grid', gridTemplateRows: 'auto 1fr' }}>
        <div style={{ padding: '8px 16px', background: '#181825', borderBottom: '1px solid #313244', fontSize: 14 }}>
          <strong>{projection?.workflowName ?? 'Workflow'}</strong>
          {status && <span style={{ marginLeft: 16, color: '#a6e3a1' }}>{status}</span>}
          {error && <span style={{ marginLeft: 16, color: '#f38ba8' }}>⚠ {error}</span>}
        </div>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onNodeClick={(_, node) => setSelectedNodeId(node.id)}
          nodeTypes={NODE_TYPES}
          fitView
        >
          <Background />
          <Controls />
        </ReactFlow>
      </main>

      <aside style={{ borderLeft: '1px solid #313244', padding: 12, overflow: 'auto' }}>
        <h2 style={{ marginTop: 0 }}>Workbench</h2>

        <section style={{ marginBottom: 18 }}>
          <h3>Parameter panel</h3>
          {selectedNode ? (
            <>
              <div style={{ fontSize: 12, marginBottom: 8 }}>{selectedNode.label} ({selectedNode.id})</div>
              <label style={{ display: 'block', fontSize: 12 }}>Property key</label>
              <input value={propertyKey} onChange={(e) => setPropertyKey(e.target.value)} style={{ width: '100%', marginBottom: 8 }} />
              <label style={{ display: 'block', fontSize: 12 }}>Value</label>
              <input value={propertyValue} onChange={(e) => setPropertyValue(e.target.value)} style={{ width: '100%', marginBottom: 8 }} />
              <button onClick={updateProperty}>Apply property</button>
            </>
          ) : (
            <p style={{ fontSize: 12, opacity: 0.8 }}>Select a node to edit parameters.</p>
          )}
        </section>

        <section style={{ marginBottom: 18 }}>
          <h3>Validation overlay</h3>
          <button onClick={refreshValidation}>Validate graph</button>
          {violations.length === 0 ? (
            <p style={{ color: '#a6e3a1' }}>No validation violations.</p>
          ) : (
            <ul style={{ color: '#f38ba8', paddingLeft: 18 }}>
              {violations.map((violation) => <li key={violation}>{violation}</li>)}
            </ul>
          )}
        </section>

        <section style={{ marginBottom: 18 }}>
          <h3>Save / reload</h3>
          <label style={{ display: 'block', fontSize: 12 }}>Branch</label>
          <input value={branch} onChange={(e) => setBranch(e.target.value)} style={{ width: '100%', marginBottom: 8 }} />
          <label style={{ display: 'block', fontSize: 12 }}>Checkpoint message</label>
          <input value={checkpointMessage} onChange={(e) => setCheckpointMessage(e.target.value)} style={{ width: '100%', marginBottom: 8 }} />
          <button onClick={saveCheckpoint}>Save checkpoint</button>{' '}
          <button onClick={loadBranch}>Reload branch</button>{' '}
          <button onClick={refreshHistory}>Refresh history</button>
        </section>

        <section style={{ marginBottom: 18 }}>
          <h3>History</h3>
          {history.length === 0 ? (
            <p style={{ fontSize: 12, opacity: 0.8 }}>No commits loaded.</p>
          ) : (
            <ul style={{ paddingLeft: 18 }}>
              {history.map((entry) => (
                <li key={entry.commitId} style={{ marginBottom: 8 }}>
                  <div><strong>{entry.message}</strong></div>
                  <div style={{ fontSize: 11, opacity: 0.75 }}>{entry.commitId}</div>
                  <button onClick={() => loadCommit(entry.commitId)}>Load commit</button>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section>
          <h3>Execution handoff snapshot</h3>
          <button onClick={loadSnapshotPreview}>Preview DSL snapshot</button>
          {snapshotPreview && (
            <pre style={{ whiteSpace: 'pre-wrap', maxHeight: 180, overflow: 'auto', background: '#1e1e2e', padding: 8 }}>{snapshotPreview}</pre>
          )}
        </section>
      </aside>
    </div>
  );
}
