import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import ReactFlow, {
  Background,
  Controls,
  Handle,
  Position,
  useEdgesState,
  useNodesState,
  type Connection,
  type Edge,
  type EdgeChange,
  type Node,
  type NodeProps,
  type NodeTypes,
  type Viewport,
} from 'reactflow';
import 'reactflow/dist/style.css';

import { CollaborationPanel } from './CollaborationPanel';
import {
  getJson,
  postJson,
  type CatalogEntry,
  type EdgeProjection,
  type NodeProjection,
  type ValidationResponse,
  type WorkflowProjection,
} from './api';
import { useWorkflowSession } from './useWorkflowSession';
import { layoutNodePositions, operationId } from './workflowProjection.mjs';

interface HistoryEntry {
  commitId: string;
  workflowId: string;
  author: string;
  message: string;
  timestamp: string;
}

interface CheckpointResponse {
  commitId: string;
}

const DATA_TYPE_COLORS: Readonly<Record<string, string>> = Object.freeze({
  AudioBlock: '#4a90d9',
  Dataset: '#7b68ee',
  Spectrum: '#e67e22',
  FeatureSet: '#27ae60',
  ClassificationResult: '#e74c3c',
  LocalizationResult: '#16a085',
  BenchmarkResult: '#d35400',
  Report: '#95a5a6',
});

function handleColor(dataType: string): string {
  return DATA_TYPE_COLORS[dataType] ?? '#888';
}

function AudioNode({ data }: NodeProps<NodeProjection>) {
  return (
    <div className="audio-node" data-testid={`node-${data.id}`}>
      <div className="audio-node__label">{data.label}</div>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '1fr 1fr',
          gap: 12,
          marginTop: 8,
          minHeight: 18,
        }}
      >
        <div
          data-testid={`input-ports-${data.id}`}
          style={{ display: 'grid', gap: 4, fontSize: 10, textAlign: 'left' }}
        >
          {data.inputHandles.map((handle, index) => (
            <div key={handle.id} title={`${handle.name} (${handle.dataType})`}>
              <Handle
                type="target"
                position={Position.Left}
                id={handle.id}
                style={{
                  background: handleColor(handle.dataType),
                  height: 10,
                  top: 34 + index * 18,
                  width: 10,
                }}
              />
              {handle.name}
            </div>
          ))}
        </div>
        <div
          data-testid={`output-ports-${data.id}`}
          style={{ display: 'grid', gap: 4, fontSize: 10, textAlign: 'right' }}
        >
          {data.outputHandles.map((handle, index) => (
            <div key={handle.id} title={`${handle.name} (${handle.dataType})`}>
              {handle.name}
              <Handle
                type="source"
                position={Position.Right}
                id={handle.id}
                style={{
                  background: handleColor(handle.dataType),
                  height: 10,
                  top: 34 + index * 18,
                  width: 10,
                }}
              />
            </div>
          ))}
        </div>
      </div>
      {Object.keys(data.properties).length > 0 ? (
        <div className="audio-node__properties">
          {Object.entries(data.properties).map(([key, value]) => (
            <div key={key}>
              {key}: {value}
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}

const NODE_TYPES: NodeTypes = Object.freeze({ audioNode: AudioNode });

function toNodes(projection: WorkflowProjection): Node<NodeProjection>[] {
  const positions = layoutNodePositions(projection.nodes);
  return projection.nodes.map((node) => ({
    id: node.id,
    type: 'audioNode',
    position: positions[node.id],
    data: node,
  }));
}

function toEdges(edges: EdgeProjection[]): Edge[] {
  return edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    sourceHandle: edge.sourceHandle,
    target: edge.target,
    targetHandle: edge.targetHandle,
  }));
}

function newOperationId(kind: string): string {
  return operationId(kind, Date.now(), crypto.randomUUID());
}

function failureMessage(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure);
}

export default function WorkflowEditorApp() {
  const [projection, setProjection] = useState<WorkflowProjection | null>(null);
  const [catalog, setCatalog] = useState<CatalogEntry[]>([]);
  const [nodes, setNodes, onNodesChange] = useNodesState<NodeProjection>([]);
  const [edges, setEdges, onEdgesChangeDefault] = useEdgesState([]);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [propertyKey, setPropertyKey] = useState('gain');
  const [propertyValue, setPropertyValue] = useState('1.5');
  const [branch, setBranch] = useState('main');
  const [checkpointMessage, setCheckpointMessage] = useState('Workbench checkpoint');
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [violations, setViolations] = useState<string[]>([]);
  const [status, setStatus] = useState('Loading server projection…');
  const [error, setError] = useState<string | null>(null);
  const previouslyActive = useRef(false);
  const sessionActiveRef = useRef(false);

  const selectedNode = useMemo(
    () => projection?.nodes.find((node) => node.id === selectedNodeId) ?? null,
    [projection, selectedNodeId],
  );

  const applyProjection = useCallback(
    (nextProjection: WorkflowProjection) => {
      setProjection(nextProjection);
      setNodes(toNodes(nextProjection));
      setEdges(toEdges(nextProjection.edges));
      setSelectedNodeId((current) =>
        current !== null && nextProjection.nodes.some((node) => node.id === current) ? current : null,
      );
      setError(null);
      setStatus(
        `Loaded: ${nextProjection.workflowName} (${nextProjection.nodes.length} nodes, ${nextProjection.edges.length} edges)`,
      );
    },
    [setEdges, setNodes],
  );

  const collaboration = useWorkflowSession({
    onProjection: applyProjection,
    onError: setError,
    onStatus: setStatus,
  });
  sessionActiveRef.current = collaboration.active;

  const canEdit =
    collaboration.active &&
    collaboration.connectionState === 'live' &&
    collaboration.pendingOperationId === null;

  const refreshValidation = useCallback(async () => {
    if (collaboration.active) {
      setViolations([]);
      return;
    }
    try {
      const response = await getJson<ValidationResponse>('/workflow/validation');
      setViolations(response.violations);
    } catch (failure) {
      setError(failureMessage(failure));
    }
  }, [collaboration.active]);

  const refreshProjection = useCallback(async () => {
    try {
      const legacyProjection = await getJson<WorkflowProjection>('/workflow/projection');
      if (!sessionActiveRef.current) {
        applyProjection(legacyProjection);
      }
    } catch (failure) {
      setError(failureMessage(failure));
    }
  }, [applyProjection]);

  const refreshHistory = useCallback(async () => {
    try {
      setHistory(
        await getJson<HistoryEntry[]>(
          `/workflow/history?branch=${encodeURIComponent(branch)}&limit=20`,
        ),
      );
    } catch (failure) {
      setError(failureMessage(failure));
    }
  }, [branch]);

  useEffect(() => {
    void Promise.all([
      refreshProjection(),
      refreshValidation(),
      getJson<CatalogEntry[]>('/workflow/catalog').then(setCatalog),
    ]).catch((failure: unknown) => {
      setError(failureMessage(failure));
    });
  }, [refreshProjection, refreshValidation]);

  useEffect(() => {
    if (previouslyActive.current && !collaboration.active) {
      void Promise.all([refreshProjection(), refreshValidation()]);
    }
    previouslyActive.current = collaboration.active;
  }, [collaboration.active, refreshProjection, refreshValidation]);

  useEffect(() => {
    if (selectedNode !== null) {
      setPropertyValue(selectedNode.properties[propertyKey] ?? '');
    }
  }, [propertyKey, selectedNode]);

  const postOperation = useCallback(
    async (operation: Record<string, unknown>) => {
      if (!canEdit) {
        const message =
          collaboration.active && collaboration.connectionState !== 'live'
            ? 'Semantic editing is paused until the collaboration stream is live'
            : 'Create or join a collaboration session before editing';
        setError(message);
        throw new Error(message);
      }
      try {
        await collaboration.submitOperation(operation);
        setViolations([]);
      } catch (failure) {
        setError(failureMessage(failure));
      }
    },
    [canEdit, collaboration],
  );

  const addCatalogNode = useCallback(
    (entry: CatalogEntry) => {
      const nodeId = `node.${entry.type}.${crypto.randomUUID()}`;
      void postOperation({
        type: 'CreateNode',
        operationId: newOperationId('create'),
        nodeId,
        catalogType: entry.type,
      });
      setSelectedNodeId(nodeId);
    },
    [postOperation],
  );

  const onConnect = useCallback(
    (connection: Connection) => {
      if (
        connection.source === null ||
        connection.target === null ||
        connection.sourceHandle === null ||
        connection.targetHandle === null
      ) {
        return;
      }
      const edgeId = `edge.${crypto.randomUUID()}`;
      void postOperation({
        type: 'ConnectPorts',
        operationId: newOperationId('connect'),
        edge: {
          id: edgeId,
          sourceNodeId: connection.source,
          sourcePortId: connection.sourceHandle,
          targetNodeId: connection.target,
          targetPortId: connection.targetHandle,
        },
      });
    },
    [postOperation],
  );

  const onEdgesChange = useCallback(
    (changes: EdgeChange[]) => {
      onEdgesChangeDefault(changes.filter((change) => change.type !== 'remove'));
      for (const change of changes) {
        if (change.type !== 'remove') {
          continue;
        }
        const edge = edges.find((candidate) => candidate.id === change.id);
        if (edge === undefined) {
          continue;
        }
        void postOperation({
          type: 'DisconnectPorts',
          operationId: newOperationId('disconnect'),
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
    },
    [edges, onEdgesChangeDefault, postOperation],
  );

  const updateProperty = useCallback(() => {
    if (selectedNode === null) {
      return;
    }
    void postOperation({
      type: 'UpdateProperty',
      operationId: newOperationId('property'),
      target: 'NODE',
      targetId: selectedNode.id,
      propertyKey,
      previousValue: selectedNode.properties[propertyKey] ?? null,
      newValue: propertyValue,
    });
  }, [postOperation, propertyKey, propertyValue, selectedNode]);

  const saveCheckpoint = useCallback(async () => {
    if (collaboration.active) {
      setError('Session checkpoint integration is intentionally separated from semantic live editing');
      return;
    }
    try {
      const response = await postJson<CheckpointResponse>('/workflow/checkpoints', {
        branch,
        author: collaboration.actor.actorId,
        message: checkpointMessage,
      });
      setStatus(`Checkpoint saved: ${response.commitId}`);
      await refreshHistory();
    } catch (failure) {
      setError(failureMessage(failure));
    }
  }, [branch, checkpointMessage, collaboration.active, collaboration.actor.actorId, refreshHistory]);

  const loadCommit = useCallback(
    async (commitId: string) => {
      if (collaboration.active) {
        setError('Leave the collaboration session before loading legacy checkpoint history');
        return;
      }
      try {
        applyProjection(await postJson<WorkflowProjection>('/workflow/load', { commitId }));
        await refreshValidation();
      } catch (failure) {
        setError(failureMessage(failure));
      }
    },
    [applyProjection, collaboration.active, refreshValidation],
  );

  const selectNode = useCallback(
    (nodeId: string | null) => {
      setSelectedNodeId(nodeId);
      collaboration.publishPresence({ selection: nodeId ?? '' });
    },
    [collaboration],
  );

  const publishViewport = useCallback(
    (_event: MouseEvent | TouchEvent | null, viewport: Viewport) => {
      collaboration.publishPresence({
        viewportX: viewport.x.toFixed(1),
        viewportY: viewport.y.toFixed(1),
        viewportZoom: viewport.zoom.toFixed(3),
      });
    },
    [collaboration],
  );

  return (
    <div className="workbench">
      <header className="workbench__toolbar">
        <h1 className="workbench__title" data-testid="workbench-title">
          Workflow Workbench
        </h1>
        <span className="workbench__status" data-testid="status-message">
          {status}
        </span>
        {error === null ? null : (
          <span className="workbench__error" data-testid="error-banner">
            {error}
          </span>
        )}
      </header>

      <aside className="workbench__panel workbench__panel--left" data-testid="node-palette">
        <CollaborationPanel controller={collaboration} />
        <h2>Node palette</h2>
        <p className="help-text">
          {canEdit
            ? 'Semantic changes are pending until accepted and projected by the server.'
            : 'The seed graph is read-only. Join a live collaboration session to edit.'}
        </p>
        <div data-testid="catalog-list">
          {catalog.map((entry) => (
            <button
              className="palette-entry"
              data-testid={`palette-node-${entry.type}`}
              disabled={!canEdit}
              key={entry.type}
              onClick={() => addCatalogNode(entry)}
              type="button"
            >
              <span className="palette-entry__label">{entry.label}</span>
              <span className="palette-entry__type">{entry.type}</span>
            </button>
          ))}
        </div>
      </aside>

      <main
        className="workbench__canvas"
        data-testid="graph-area"
        onPointerMove={(event) =>
          collaboration.publishPresence({
            cursorX: Math.round(event.clientX).toString(),
            cursorY: Math.round(event.clientY).toString(),
          })
        }
      >
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={NODE_TYPES}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onMoveEnd={publishViewport}
          onNodeClick={(_, node) => selectNode(node.id)}
          onPaneClick={() => selectNode(null)}
          nodesConnectable={canEdit}
          edgesUpdatable={false}
          fitView
          data-testid="graph-canvas"
        >
          <Background />
          <Controls />
        </ReactFlow>
      </main>

      <aside className="workbench__panel workbench__panel--right">
        <h2>Parameters</h2>
        {selectedNode === null ? (
          <p className="help-text">Select a node to edit a server-validated property.</p>
        ) : (
          <>
            <p>{selectedNode.label}</p>
            <label className="field">
              Property
              <input value={propertyKey} onChange={(event) => setPropertyKey(event.target.value)} />
            </label>
            <label className="field">
              Value
              <input
                value={propertyValue}
                onChange={(event) => setPropertyValue(event.target.value)}
              />
            </label>
            <button
              className="action-button"
              disabled={!canEdit}
              onClick={updateProperty}
              type="button"
            >
              Apply property
            </button>
          </>
        )}

        <h3>Validation</h3>
        {violations.length === 0 ? (
          <p className="help-text">
            {collaboration.active
              ? 'Session operations are validated before server acceptance.'
              : 'No current violations.'}
          </p>
        ) : (
          <ul className="validation-list">
            {violations.map((violation) => (
              <li key={violation}>{violation}</li>
            ))}
          </ul>
        )}

        <h3>Checkpoint</h3>
        {collaboration.active ? (
          <p className="help-text">
            Live-session checkpoint and undo UX are handled by their dedicated follow-up slices.
          </p>
        ) : null}
        <label className="field">
          Branch
          <input value={branch} onChange={(event) => setBranch(event.target.value)} />
        </label>
        <label className="field">
          Message
          <input
            value={checkpointMessage}
            onChange={(event) => setCheckpointMessage(event.target.value)}
          />
        </label>
        <button
          className="action-button"
          disabled={collaboration.active}
          onClick={() => void saveCheckpoint()}
          type="button"
        >
          Save checkpoint
        </button>
        <button
          className="action-button"
          disabled={collaboration.active}
          onClick={() => void refreshHistory()}
          type="button"
        >
          Refresh history
        </button>
        <ul className="history-list">
          {history.map((entry) => (
            <li className="history-entry" key={entry.commitId}>
              <button
                disabled={collaboration.active}
                onClick={() => void loadCommit(entry.commitId)}
                type="button"
              >
                {entry.message || entry.commitId.slice(0, 10)}
              </button>
            </li>
          ))}
        </ul>
      </aside>
    </div>
  );
}
