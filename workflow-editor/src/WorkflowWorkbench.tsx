import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  useEdgesState,
  useNodesState,
  type Connection,
  type EdgeChange,
  type NodeMouseHandler,
} from '@xyflow/react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { workflowApi } from './api';
import { AudioNode } from './AudioNode';
import type {
  CatalogEntry,
  ConnectPortsOperation,
  DisconnectPortsOperation,
  HistoryEntry,
  UpdatePropertyOperation,
  WorkflowOperation,
  WorkflowProjection,
} from './model';
import {
  projectionSummary,
  stableOperationId,
  toFlowEdges,
  toFlowNodes,
  type AudioFlowNode,
} from './projection';

const NODE_TYPES = { audioNode: AudioNode };
const DEFAULT_BRANCH = 'main';

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export function WorkflowWorkbench() {
  const [projection, setProjection] = useState<WorkflowProjection | null>(null);
  const [catalog, setCatalog] = useState<CatalogEntry[]>([]);
  const [nodes, setNodes, onNodesChange] = useNodesState<AudioFlowNode>([]);
  const [edges, setEdges, onEdgesChangeDefault] = useEdgesState([]);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [propertyKey, setPropertyKey] = useState('gain');
  const [propertyValue, setPropertyValue] = useState('1.5');
  const [branch, setBranch] = useState(DEFAULT_BRANCH);
  const [checkpointMessage, setCheckpointMessage] = useState('Workbench checkpoint');
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [violations, setViolations] = useState<string[]>([]);
  const [snapshotPreview, setSnapshotPreview] = useState('');
  const [status, setStatus] = useState('Loading server projection…');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const applyProjection = useCallback(
    (nextProjection: WorkflowProjection) => {
      setProjection(nextProjection);
      setNodes(toFlowNodes(nextProjection.nodes));
      setEdges(toFlowEdges(nextProjection.edges));
      setStatus(projectionSummary(nextProjection));
      setError(null);
    },
    [setEdges, setNodes],
  );

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusy(false);
    }
  }, []);

  const refreshValidation = useCallback(async () => {
    const response = await workflowApi.validation();
    setViolations(response.violations);
  }, []);

  const refreshProjection = useCallback(async () => {
    applyProjection(await workflowApi.projection());
  }, [applyProjection]);

  const refreshHistory = useCallback(async () => {
    setHistory(await workflowApi.history(branch));
  }, [branch]);

  useEffect(() => {
    void run(async () => {
      const [nextCatalog, nextProjection, nextValidation] = await Promise.all([
        workflowApi.catalog(),
        workflowApi.projection(),
        workflowApi.validation(),
      ]);
      setCatalog(nextCatalog);
      applyProjection(nextProjection);
      setViolations(nextValidation.violations);
    });
  }, [applyProjection, run]);

  const postOperation = useCallback(
    async (operation: WorkflowOperation) => {
      await run(async () => {
        applyProjection(await workflowApi.operation(operation));
        await refreshValidation();
        setStatus('Server accepted operation');
      });
    },
    [applyProjection, refreshValidation, run],
  );

  const addCatalogNode = useCallback(
    (entry: CatalogEntry) => {
      const nodeId = `node.${entry.type}.${crypto.randomUUID()}`;
      setSelectedNodeId(nodeId);
      void postOperation({
        type: 'CreateNode',
        operationId: stableOperationId('create'),
        author: 'web-editor',
        nodeId,
        catalogType: entry.type,
      });
    },
    [postOperation],
  );

  const onConnect = useCallback(
    (connection: Connection) => {
      if (
        connection.source == null ||
        connection.target == null ||
        connection.sourceHandle == null ||
        connection.targetHandle == null
      ) {
        setError('Both typed source and target ports are required.');
        return;
      }
      const operation: ConnectPortsOperation = {
        type: 'ConnectPorts',
        operationId: stableOperationId('connect'),
        author: 'web-editor',
        edge: {
          id: `edge.${crypto.randomUUID()}`,
          sourceNodeId: connection.source,
          sourcePortId: connection.sourceHandle,
          targetNodeId: connection.target,
          targetPortId: connection.targetHandle,
        },
      };
      void postOperation(operation);
    },
    [postOperation],
  );

  const onEdgesChange = useCallback(
    (changes: EdgeChange[]) => {
      onEdgesChangeDefault(changes.filter((change) => change.type !== 'remove'));
      for (const change of changes) {
        if (change.type !== 'remove') continue;
        const edge = edges.find((candidate) => candidate.id === change.id);
        if (edge == null) continue;
        const operation: DisconnectPortsOperation = {
          type: 'DisconnectPorts',
          operationId: stableOperationId('disconnect'),
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
        void postOperation(operation);
      }
    },
    [edges, onEdgesChangeDefault, postOperation],
  );

  const onNodeClick: NodeMouseHandler<AudioFlowNode> = useCallback((_event, node) => {
    setSelectedNodeId(node.id);
  }, []);

  const selectedNode = useMemo(
    () => projection?.nodes.find((node) => node.id === selectedNodeId) ?? null,
    [projection, selectedNodeId],
  );

  useEffect(() => {
    setPropertyValue(selectedNode?.properties[propertyKey] ?? '');
  }, [propertyKey, selectedNode]);

  const updateProperty = useCallback(() => {
    if (selectedNode == null) return;
    const operation: UpdatePropertyOperation = {
      type: 'UpdateProperty',
      operationId: stableOperationId('property'),
      author: 'web-editor',
      target: 'NODE',
      targetId: selectedNode.id,
      propertyKey,
      previousValue: selectedNode.properties[propertyKey] ?? null,
      newValue: propertyValue,
    };
    void postOperation(operation);
  }, [postOperation, propertyKey, propertyValue, selectedNode]);

  return (
    <main className="workbench-shell">
      <header className="workbench-toolbar">
        <div>
          <p>Audio Analyzer</p>
          <h1 data-testid="workbench-title">Workflow Workbench</h1>
        </div>
        <span className="status" data-testid="status-message">{status}</span>
        <span className="authority-badge">Server authoritative</span>
      </header>

      {error != null && (
        <div className="error-banner" role="alert" data-testid="error-banner">
          {error}
        </div>
      )}

      <aside className="palette" data-testid="node-palette">
        <h2>Node palette</h2>
        <p>Every semantic edit becomes a validated server operation.</p>
        <div data-testid="catalog-list">
          {catalog.map((entry) => (
            <button
              key={entry.type}
              type="button"
              disabled={busy}
              className="palette-entry"
              data-testid={`palette-node-${entry.type}`}
              onClick={() => addCatalogNode(entry)}
            >
              <strong>{entry.label}</strong>
              <span>{entry.type}</span>
              <small>{entry.inputHandles.length} in · {entry.outputHandles.length} out</small>
            </button>
          ))}
        </div>
      </aside>

      <section className="graph-panel" data-testid="graph-area">
        <div className="graph-canvas" data-testid="graph-canvas">
          <ReactFlow<AudioFlowNode>
            nodes={nodes}
            edges={edges}
            nodeTypes={NODE_TYPES}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={onNodeClick}
            fitView
            minZoom={0.25}
            maxZoom={2}
            deleteKeyCode={['Backspace', 'Delete']}
            nodesConnectable={!busy}
            elementsSelectable
          >
            <Background gap={24} size={1} />
            <MiniMap pannable zoomable />
            <Controls showInteractive={false} />
          </ReactFlow>
        </div>
      </section>

      <aside className="inspector">
        <section>
          <h2>Parameters</h2>
          {selectedNode == null ? (
            <p>Select a node to edit one of its semantic properties.</p>
          ) : (
            <>
              <strong>{selectedNode.label}</strong>
              <label>
                Property
                <input value={propertyKey} onChange={(event) => setPropertyKey(event.target.value)} />
              </label>
              <label>
                Value
                <input value={propertyValue} onChange={(event) => setPropertyValue(event.target.value)} />
              </label>
              <button type="button" disabled={busy} onClick={updateProperty}>Apply operation</button>
            </>
          )}
        </section>

        <section>
          <h2>Validation</h2>
          <button type="button" disabled={busy} onClick={() => void run(refreshValidation)}>Refresh</button>
          {violations.length === 0 ? (
            <p className="valid">No validation violations.</p>
          ) : (
            <ul className="violations">{violations.map((violation) => <li key={violation}>{violation}</li>)}</ul>
          )}
        </section>

        <section>
          <h2>Version history</h2>
          <label>
            Branch
            <input value={branch} onChange={(event) => setBranch(event.target.value)} />
          </label>
          <label>
            Checkpoint message
            <input value={checkpointMessage} onChange={(event) => setCheckpointMessage(event.target.value)} />
          </label>
          <div className="button-row">
            <button
              type="button"
              disabled={busy}
              onClick={() => void run(async () => {
                const response = await workflowApi.checkpoint(branch, checkpointMessage);
                setStatus(`Checkpoint ${response.commitId}`);
                await refreshHistory();
              })}
            >Save</button>
            <button type="button" disabled={busy} onClick={() => void run(refreshHistory)}>History</button>
            <button
              type="button"
              disabled={busy}
              onClick={() => void run(async () => applyProjection(await workflowApi.loadBranch(branch)))}
            >Reload</button>
          </div>
          <ol className="history-list">
            {history.map((entry) => (
              <li key={entry.commitId}>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void run(async () => applyProjection(await workflowApi.loadCommit(entry.commitId)))}
                >
                  <strong>{entry.message}</strong>
                  <span>{entry.author} · {entry.commitId.slice(0, 10)}</span>
                </button>
              </li>
            ))}
          </ol>
        </section>

        <section>
          <h2>Execution handoff</h2>
          <button
            type="button"
            disabled={busy}
            onClick={() => void run(async () => {
              const snapshot = await workflowApi.snapshot();
              setSnapshotPreview(snapshot.dslText.slice(0, 1200));
            })}
          >Preview canonical DSL</button>
          {snapshotPreview.length > 0 && <pre>{snapshotPreview}</pre>}
        </section>
      </aside>
    </main>
  );
}
