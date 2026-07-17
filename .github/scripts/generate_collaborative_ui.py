from pathlib import Path
from textwrap import dedent

ROOT = Path(__file__).resolve().parents[2]
FRONTEND = ROOT / "audio-app/workbench-ui"


def write(path: Path | str, content: str) -> None:
    target = path if isinstance(path, Path) else ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(dedent(content).lstrip(), encoding="utf-8")


write(
    FRONTEND / "package.json",
    r'''
    {
      "name": "audio-analyzer-collaborative-workbench",
      "private": true,
      "version": "0.0.4",
      "type": "module",
      "scripts": {
        "dev": "vite",
        "build": "vite build",
        "preview": "vite preview"
      },
      "dependencies": {
        "@xyflow/react": "latest",
        "react": "latest",
        "react-dom": "latest",
        "y-protocols": "latest",
        "yjs": "latest"
      },
      "devDependencies": {
        "@vitejs/plugin-react": "latest",
        "vite": "latest"
      }
    }
    ''',
)

write(
    FRONTEND / "vite.config.js",
    r'''
    import { defineConfig } from 'vite';
    import react from '@vitejs/plugin-react';
    import { resolve } from 'node:path';

    export default defineConfig({
      plugins: [react()],
      base: '/workbench-ui/',
      build: {
        outDir: resolve(import.meta.dirname, '../src/main/resources/workbench-ui'),
        emptyOutDir: true,
        sourcemap: true
      }
    });
    ''',
)

write(
    FRONTEND / "index.html",
    r'''
    <!doctype html>
    <html lang="en">
      <head>
        <meta charset="UTF-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        <title>Audio Analyzer Collaborative Workbench</title>
      </head>
      <body>
        <div id="root"></div>
        <script type="module" src="/src/main.jsx"></script>
      </body>
    </html>
    ''',
)

write(
    FRONTEND / "src/api.js",
    r'''
    const JSON_HEADERS = { 'Content-Type': 'application/json' };

    export async function api(path, options = {}) {
      const response = await fetch(path, {
        ...options,
        headers: { ...JSON_HEADERS, ...(options.headers || {}) }
      });
      if (response.status === 204) return null;
      const contentType = response.headers.get('content-type') || '';
      const body = contentType.includes('json') ? await response.json() : await response.text();
      if (!response.ok) {
        const message = body?.detail || body?.error || body?.message || String(body || response.statusText);
        const error = new Error(message);
        error.status = response.status;
        error.problem = body;
        throw error;
      }
      return body;
    }

    export const actorBody = actor => ({
      actorId: actor.actorId,
      userId: actor.userId,
      displayName: actor.displayName
    });

    export async function createSession(sessionId, mode, actor) {
      return api('/workflow/sessions', {
        method: 'POST',
        body: JSON.stringify({ sessionId, mode, actor: actorBody(actor) })
      });
    }

    export async function joinSession(sessionId, actor) {
      return api(`/workflow/sessions/${encodeURIComponent(sessionId)}/join`, {
        method: 'POST',
        body: JSON.stringify(actorBody(actor))
      });
    }

    export async function leaveSession(sessionId, actor) {
      return api(`/workflow/sessions/${encodeURIComponent(sessionId)}/leave`, {
        method: 'POST',
        body: JSON.stringify({ actorId: actor.actorId })
      });
    }

    export async function loadState(sessionId) {
      return api(`/workflow/sessions/${encodeURIComponent(sessionId)}/state`);
    }

    export async function applyOperation(sessionId, mode, actor, expectedRevision, operation) {
      return api(`/workflow/sessions/${encodeURIComponent(sessionId)}/operations`, {
        method: 'POST',
        body: JSON.stringify({
          mode,
          actor: actorBody(actor),
          expectedRevision,
          operation
        })
      });
    }

    export async function updatePresence(sessionId, actor, presence) {
      return api(`/workflow/sessions/${encodeURIComponent(sessionId)}/presence`, {
        method: 'PUT',
        body: JSON.stringify({ actor: actorBody(actor), ...presence })
      });
    }

    export async function undo(sessionId, actor, expectedRevision, targetOperationId) {
      return api(`/workflow/sessions/${encodeURIComponent(sessionId)}/undo`, {
        method: 'POST',
        body: JSON.stringify({ actor: actorBody(actor), expectedRevision, targetOperationId })
      });
    }

    export async function redo(sessionId, actor, expectedRevision) {
      return api(`/workflow/sessions/${encodeURIComponent(sessionId)}/redo`, {
        method: 'POST',
        body: JSON.stringify({ actor: actorBody(actor), expectedRevision })
      });
    }

    export async function checkpoint(author, message) {
      return api('/workflow/checkpoints', {
        method: 'POST',
        body: JSON.stringify({ branch: 'main', author, message })
      });
    }

    export async function history() {
      return api('/workflow/history?branch=main&limit=50');
    }

    export async function rebuildSearch() {
      return api('/workflow/search/rebuild', {
        method: 'POST',
        body: JSON.stringify({ branches: ['main'], limitPerBranch: 1000 })
      });
    }

    export async function searchHistory(text) {
      return api(`/workflow/search?limit=50&text=${encodeURIComponent(text || '')}`);
    }

    export async function startExecution(sessionId) {
      return api('/workflow/executions', {
        method: 'POST',
        body: JSON.stringify({ sessionId })
      });
    }

    export async function execution(runId) {
      return api(`/workflow/executions/${encodeURIComponent(runId)}`);
    }

    export async function compareVersions(leftCommitId, rightCommitId) {
      return api('/workflow/versions/compare', {
        method: 'POST',
        body: JSON.stringify({ leftCommitId, rightCommitId })
      });
    }

    export async function mergeVersions(request) {
      return api('/workflow/versions/merge', {
        method: 'POST',
        body: JSON.stringify(request)
      });
    }
    ''',
)

write(
    FRONTEND / "src/main.jsx",
    r'''
    import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
    import { createRoot } from 'react-dom/client';
    import {
      Background,
      Controls,
      Handle,
      MiniMap,
      Position,
      ReactFlow,
      ReactFlowProvider,
      addEdge,
      useEdgesState,
      useNodesState
    } from '@xyflow/react';
    import '@xyflow/react/dist/style.css';
    import * as Y from 'yjs';
    import { Awareness } from 'y-protocols/awareness';
    import {
      actorBody,
      api,
      applyOperation,
      checkpoint,
      compareVersions,
      createSession,
      execution,
      history,
      joinSession,
      leaveSession,
      loadState,
      mergeVersions,
      rebuildSearch,
      redo,
      searchHistory,
      startExecution,
      undo,
      updatePresence
    } from './api';
    import './styles.css';

    const MODES = [
      'PRIVATE_WORKSPACE',
      'SHARED_SESSION_PERSONAL_UNDO',
      'SHARED_SESSION_SHARED_UNDO'
    ];

    const PALETTE = [
      ['synthetic-signal-generator', 'Synthetic Signal'],
      ['recording-input', 'Recording Input'],
      ['gain', 'Gain'],
      ['bandpass-filter', 'Band-pass'],
      ['fft', 'FFT'],
      ['classifier', 'Classifier'],
      ['localization', 'Localization'],
      ['report', 'Report']
    ];

    function newId(prefix) {
      if (globalThis.crypto?.randomUUID) return `${prefix}.${crypto.randomUUID()}`;
      return `${prefix}.${Date.now()}.${Math.random().toString(16).slice(2)}`;
    }

    function actorFromStorage() {
      const saved = JSON.parse(localStorage.getItem('audio-workbench-actor') || 'null');
      return saved || {
        actorId: `actor-${Math.random().toString(36).slice(2, 8)}`,
        userId: `user-${Math.random().toString(36).slice(2, 8)}`,
        displayName: 'Researcher'
      };
    }

    function TypedNode({ data }) {
      return (
        <div className="typed-node" data-testid={`node-${data.id}`}>
          <div className="typed-node__title">{data.label}</div>
          <div className="typed-node__type">{data.type}</div>
          <div className="ports ports--inputs">
            {(data.inputHandles || []).map((port, index) => (
              <div className="port-row" key={port.id} title={port.dataType}>
                <Handle
                  type="target"
                  position={Position.Left}
                  id={port.id}
                  style={{ top: 54 + index * 22 }}
                />
                <span>{port.name}</span><small>{port.dataType}</small>
              </div>
            ))}
          </div>
          <div className="ports ports--outputs">
            {(data.outputHandles || []).map((port, index) => (
              <div className="port-row port-row--out" key={port.id} title={port.dataType}>
                <small>{port.dataType}</small><span>{port.name}</span>
                <Handle
                  type="source"
                  position={Position.Right}
                  id={port.id}
                  style={{ top: 54 + index * 22 }}
                />
              </div>
            ))}
          </div>
        </div>
      );
    }

    const nodeTypes = { workflowNode: TypedNode };

    function projectionToFlow(projection, layoutMap) {
      const nodes = (projection?.nodes || []).map((node, index) => ({
        id: node.id,
        type: 'workflowNode',
        position: layoutMap.get(node.id) || { x: 80 + (index % 4) * 260, y: 80 + Math.floor(index / 4) * 210 },
        data: { ...node, id: node.id }
      }));
      const edges = (projection?.edges || []).map(edge => ({
        id: edge.id,
        source: edge.source,
        sourceHandle: edge.sourceHandle,
        target: edge.target,
        targetHandle: edge.targetHandle,
        animated: false
      }));
      return { nodes, edges };
    }

    function App() {
      const [actor, setActor] = useState(actorFromStorage);
      const [sessionId, setSessionId] = useState(localStorage.getItem('audio-workbench-session') || 'experiment-1');
      const [mode, setMode] = useState('SHARED_SESSION_PERSONAL_UNDO');
      const [session, setSession] = useState(null);
      const [projection, setProjection] = useState(null);
      const [presence, setPresence] = useState({});
      const [nodes, setNodes, onNodesChange] = useNodesState([]);
      const [edges, setEdges, onEdgesChange] = useEdgesState([]);
      const [connection, setConnection] = useState('offline');
      const [pending, setPending] = useState(false);
      const [error, setError] = useState('');
      const [selectedNodeId, setSelectedNodeId] = useState(null);
      const [propertyKey, setPropertyKey] = useState('gainDb');
      const [propertyValue, setPropertyValue] = useState('0.0');
      const [historyEntries, setHistoryEntries] = useState([]);
      const [searchText, setSearchText] = useState('');
      const [searchResults, setSearchResults] = useState([]);
      const [run, setRun] = useState(null);
      const [compareLeft, setCompareLeft] = useState('');
      const [compareRight, setCompareRight] = useState('');
      const [diff, setDiff] = useState(null);
      const [mergeForm, setMergeForm] = useState({ base: '', local: '', remote: '' });
      const [mergeResult, setMergeResult] = useState(null);
      const eventSourceRef = useRef(null);
      const lastSequenceRef = useRef(0);
      const stateRef = useRef({ session: null, projection: null });
      const presenceTimer = useRef(null);
      const ydocRef = useRef(new Y.Doc());
      const awarenessRef = useRef(new Awareness(ydocRef.current));
      const layoutMap = useMemo(() => ydocRef.current.getMap('layout'), []);

      const applyState = useCallback(next => {
        if (!next) return;
        const nextSession = next.session || next.state?.session;
        const nextProjection = next.projection || next.state?.projection;
        if (nextSession) {
          setSession(nextSession);
          lastSequenceRef.current = Math.max(lastSequenceRef.current, nextSession.sequence || 0);
        }
        if (nextProjection) setProjection(nextProjection);
        if (next.presence) setPresence(next.presence);
      }, []);

      useEffect(() => {
        stateRef.current = { session, projection };
      }, [session, projection]);

      useEffect(() => {
        const flow = projectionToFlow(projection, layoutMap);
        setNodes(flow.nodes);
        setEdges(flow.edges);
      }, [projection, layoutMap, setNodes, setEdges]);

      useEffect(() => {
        localStorage.setItem('audio-workbench-actor', JSON.stringify(actor));
      }, [actor]);

      const handleError = useCallback(err => {
        setError(err?.message || String(err));
        if (err?.problem?.code === 'REVISION_CONFLICT' && sessionId) {
          loadState(sessionId).then(applyState).catch(() => {});
        }
      }, [applyState, sessionId]);

      const disconnectEvents = useCallback(() => {
        eventSourceRef.current?.close();
        eventSourceRef.current = null;
        setConnection('offline');
      }, []);

      const connectEvents = useCallback(id => {
        disconnectEvents();
        const after = lastSequenceRef.current;
        const source = new EventSource(`/workflow/sessions/${encodeURIComponent(id)}/events?afterSequence=${after}`);
        eventSourceRef.current = source;
        source.onopen = () => setConnection('live');
        source.onerror = () => setConnection('reconnecting');
        const eventNames = [
          'session_created', 'actor_joined', 'actor_left', 'session_closed',
          'operation_accepted', 'presence_updated', 'presence_cleared',
          'undo_accepted', 'redo_accepted', 'snapshot'
        ];
        eventNames.forEach(name => source.addEventListener(name, event => {
          const payload = JSON.parse(event.data);
          if (payload.sequence <= lastSequenceRef.current && name !== 'snapshot') return;
          lastSequenceRef.current = Math.max(lastSequenceRef.current, payload.sequence || 0);
          applyState(payload);
        }));
      }, [applyState, disconnectEvents]);

      useEffect(() => () => disconnectEvents(), [disconnectEvents]);

      const create = async () => {
        setError(''); setPending(true);
        try {
          await createSession(sessionId, mode, actor);
          localStorage.setItem('audio-workbench-session', sessionId);
          applyState(await loadState(sessionId));
          connectEvents(sessionId);
        } catch (err) { handleError(err); } finally { setPending(false); }
      };

      const join = async () => {
        setError(''); setPending(true);
        try {
          const joined = await joinSession(sessionId, actor);
          setMode(joined.mode);
          localStorage.setItem('audio-workbench-session', sessionId);
          applyState(await loadState(sessionId));
          connectEvents(sessionId);
        } catch (err) { handleError(err); } finally { setPending(false); }
      };

      const leave = async () => {
        try { await leaveSession(sessionId, actor); } catch (err) { handleError(err); }
        disconnectEvents(); setSession(null); setProjection(null); setPresence({});
      };

      const sendOperation = useCallback(async operation => {
        const current = stateRef.current.session;
        if (!current || pending) return;
        setPending(true); setError('');
        try {
          const next = await applyOperation(sessionId, current.mode, actor, current.revision, operation);
          applyState(next);
        } catch (err) { handleError(err); } finally { setPending(false); }
      }, [actor, applyState, handleError, pending, sessionId]);

      const addNode = catalogType => sendOperation({
        type: 'CreateNode',
        operationId: newId('operation.create-node'),
        author: actor.actorId,
        nodeId: newId(`node.${catalogType}`),
        catalogType
      });

      const onConnect = connection => {
        if (!connection.sourceHandle || !connection.targetHandle) return;
        sendOperation({
          type: 'ConnectPorts',
          operationId: newId('operation.connect'),
          author: actor.actorId,
          edge: {
            id: newId('edge'),
            sourceNodeId: connection.source,
            sourcePortId: connection.sourceHandle,
            targetNodeId: connection.target,
            targetPortId: connection.targetHandle
          }
        });
      };

      const deleteEdge = edge => sendOperation({
        type: 'DisconnectPorts',
        operationId: newId('operation.disconnect'),
        author: actor.actorId,
        edgeId: edge.id,
        disconnectedEdge: {
          id: edge.id,
          sourceNodeId: edge.source,
          sourcePortId: edge.sourceHandle,
          targetNodeId: edge.target,
          targetPortId: edge.targetHandle
        }
      });

      const updateProperty = () => {
        const selected = projection?.nodes?.find(node => node.id === selectedNodeId);
        if (!selected) return;
        sendOperation({
          type: 'UpdateProperty',
          operationId: newId('operation.property'),
          author: actor.actorId,
          target: 'NODE',
          targetId: selectedNodeId,
          propertyKey,
          previousValue: selected.properties?.[propertyKey] ?? null,
          newValue: propertyValue
        });
      };

      const requestUndo = async () => {
        if (!session) return;
        let target = null;
        if (session.mode === 'SHARED_SESSION_SHARED_UNDO') {
          target = prompt('Operation id to undo (shared mode):');
          if (!target) return;
        }
        setPending(true);
        try { applyState(await undo(sessionId, actor, session.revision, target)); }
        catch (err) { handleError(err); } finally { setPending(false); }
      };

      const requestRedo = async () => {
        if (!session) return;
        setPending(true);
        try { applyState(await redo(sessionId, actor, session.revision)); }
        catch (err) { handleError(err); } finally { setPending(false); }
      };

      const sendPresence = useCallback((cursor = { x: 0, y: 0 }) => {
        if (!session) return;
        awarenessRef.current.setLocalStateField('user', actorBody(actor));
        awarenessRef.current.setLocalStateField('cursor', cursor);
        clearTimeout(presenceTimer.current);
        presenceTimer.current = setTimeout(() => {
          updatePresence(sessionId, actor, {
            cursorX: cursor.x,
            cursorY: cursor.y,
            selectedObjectIds: selectedNodeId ? [selectedNodeId] : [],
            viewportX: 0,
            viewportY: 0,
            viewportZoom: 1
          }).catch(handleError);
        }, 80);
      }, [actor, handleError, selectedNodeId, session, sessionId]);

      const saveCheckpoint = async () => {
        try {
          await checkpoint(actor.displayName, `Collaborative checkpoint r${session?.revision ?? 0}`);
          setHistoryEntries(await history());
        } catch (err) { handleError(err); }
      };

      const runSearch = async () => {
        try {
          await rebuildSearch();
          setSearchResults(await searchHistory(searchText));
        } catch (err) { handleError(err); }
      };

      const runWorkflow = async () => {
        try {
          const started = await startExecution(sessionId);
          setRun(started);
          const poll = setInterval(async () => {
            const current = await execution(started.runId);
            setRun(current);
            if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(current.status)) clearInterval(poll);
          }, 150);
        } catch (err) { handleError(err); }
      };

      const compare = async () => {
        try { setDiff(await compareVersions(compareLeft, compareRight)); }
        catch (err) { handleError(err); }
      };

      const merge = async () => {
        try {
          setMergeResult(await mergeVersions({
            baseCommitId: mergeForm.base,
            localCommitId: mergeForm.local,
            remoteCommitId: mergeForm.remote,
            resolutions: {},
            targetBranch: '',
            author: actor.displayName,
            message: 'Semantic merge preview'
          }));
        } catch (err) { handleError(err); }
      };

      return (
        <div className="app-shell">
          <header>
            <div><h1>Audio Analyzer</h1><p>Collaborative experiment workflow workbench</p></div>
            <div className={`connection connection--${connection}`} data-testid="connection-status">{connection}</div>
          </header>

          <section className="session-bar">
            <input data-testid="session-id" value={sessionId} onChange={e => setSessionId(e.target.value)} placeholder="Session id" />
            <input data-testid="actor-id" value={actor.actorId} onChange={e => setActor({ ...actor, actorId: e.target.value })} placeholder="Actor id" />
            <input data-testid="display-name" value={actor.displayName} onChange={e => setActor({ ...actor, displayName: e.target.value, userId: actor.userId || e.target.value })} placeholder="Display name" />
            <select data-testid="session-mode" value={mode} onChange={e => setMode(e.target.value)}>{MODES.map(value => <option key={value}>{value}</option>)}</select>
            <button data-testid="create-session" disabled={pending} onClick={create}>Create</button>
            <button data-testid="join-session" disabled={pending} onClick={join}>Join</button>
            <button disabled={!session} onClick={leave}>Leave</button>
            <span data-testid="session-status">{session ? `${session.sessionId} · r${session.revision} · s${session.sequence}` : 'No session'}</span>
          </section>

          {error && <div className="error" data-testid="error-banner">{error}</div>}

          <main>
            <aside className="left-panel">
              <h2>Node catalog</h2>
              <div className="palette">{PALETTE.map(([type, label]) => <button data-testid={`add-${type}`} key={type} disabled={!session || pending} onClick={() => addNode(type)}>{label}</button>)}</div>
              <h2>Collaboration</h2>
              <button data-testid="undo" disabled={!session || pending} onClick={requestUndo}>Undo</button>
              <button data-testid="redo" disabled={!session || pending} onClick={requestRedo}>Redo</button>
              <button disabled={!session} onClick={saveCheckpoint}>Checkpoint</button>
              <button disabled={!session} onClick={runWorkflow}>Run snapshot</button>
              {run && <div className="run-card"><b>{run.status}</b><small>{run.runId}</small><pre>{JSON.stringify(run.result, null, 2)}</pre></div>}
              <h3>Participants</h3>
              <ul data-testid="participants">{(session?.participants || []).map(item => <li key={item.actorId}>{item.displayName}{presence[item.actorId] ? ' · present' : ''}</li>)}</ul>
            </aside>

            <section className="canvas" onMouseMove={event => sendPresence({ x: event.clientX, y: event.clientY })}>
              <ReactFlow
                nodes={nodes}
                edges={edges}
                nodeTypes={nodeTypes}
                onNodesChange={changes => {
                  onNodesChange(changes);
                  changes.filter(change => change.type === 'position' && change.position).forEach(change => layoutMap.set(change.id, change.position));
                }}
                onEdgesChange={onEdgesChange}
                onConnect={onConnect}
                onEdgesDelete={deleted => deleted.forEach(deleteEdge)}
                onNodeClick={(_, node) => setSelectedNodeId(node.id)}
                fitView
              >
                <Background /><MiniMap /><Controls />
              </ReactFlow>
            </section>

            <aside className="right-panel">
              <h2>Inspector</h2>
              <p>{selectedNodeId || 'Select a node'}</p>
              <input value={propertyKey} onChange={e => setPropertyKey(e.target.value)} placeholder="Property" />
              <input value={propertyValue} onChange={e => setPropertyValue(e.target.value)} placeholder="Value" />
              <button disabled={!selectedNodeId || pending} onClick={updateProperty}>Apply property</button>

              <details open><summary>History search</summary>
                <input value={searchText} onChange={e => setSearchText(e.target.value)} placeholder="Search versions" />
                <button onClick={runSearch}>Rebuild & search</button>
                <ul>{searchResults.map(result => <li key={`${result.branch}:${result.commitId}`}><button onClick={() => setCompareLeft(result.commitId)}>{result.commitId.slice(0, 10)}</button> {result.message}</li>)}</ul>
              </details>

              <details><summary>Compare</summary>
                <input value={compareLeft} onChange={e => setCompareLeft(e.target.value)} placeholder="Left commit" />
                <input value={compareRight} onChange={e => setCompareRight(e.target.value)} placeholder="Right commit" />
                <button onClick={compare}>Compare</button>
                {diff && <pre>{JSON.stringify(diff, null, 2)}</pre>}
              </details>

              <details><summary>Merge preview</summary>
                {['base','local','remote'].map(key => <input key={key} value={mergeForm[key]} onChange={e => setMergeForm({ ...mergeForm, [key]: e.target.value })} placeholder={`${key} commit`} />)}
                <button onClick={merge}>Preview merge</button>
                {mergeResult && <pre>{JSON.stringify(mergeResult, null, 2)}</pre>}
              </details>
            </aside>
          </main>
        </div>
      );
    }

    createRoot(document.getElementById('root')).render(
      <React.StrictMode><ReactFlowProvider><App /></ReactFlowProvider></React.StrictMode>
    );
    ''',
)

write(
    FRONTEND / "src/styles.css",
    r'''
    :root { font-family: Inter, ui-sans-serif, system-ui, sans-serif; color: #172033; background: #eef2f7; }
    * { box-sizing: border-box; }
    body { margin: 0; min-width: 1180px; }
    button, input, select { font: inherit; }
    button { cursor: pointer; border: 1px solid #b7c1d2; background: white; border-radius: 7px; padding: 7px 10px; }
    button:hover:not(:disabled) { border-color: #4a66d6; color: #304bb5; }
    button:disabled { opacity: .5; cursor: not-allowed; }
    input, select { border: 1px solid #c6cfdd; border-radius: 7px; padding: 7px 9px; min-width: 0; }
    header { height: 76px; display: flex; justify-content: space-between; align-items: center; padding: 12px 20px; background: #172033; color: white; }
    header h1 { margin: 0; font-size: 22px; } header p { margin: 3px 0 0; opacity: .75; }
    .connection { padding: 6px 12px; border-radius: 20px; background: #475569; text-transform: uppercase; font-size: 12px; letter-spacing: .08em; }
    .connection--live { background: #15803d; } .connection--reconnecting { background: #b45309; }
    .session-bar { display: grid; grid-template-columns: 1.15fr 1fr 1fr 1.6fr auto auto auto 1fr; gap: 8px; align-items: center; padding: 10px 14px; background: white; border-bottom: 1px solid #d8dee9; }
    .session-bar span { font-size: 12px; color: #536176; }
    .error { background: #fee2e2; color: #991b1b; border-bottom: 1px solid #fecaca; padding: 8px 16px; }
    main { display: grid; grid-template-columns: 220px minmax(680px, 1fr) 310px; height: calc(100vh - 129px); }
    aside { background: #f8fafc; overflow: auto; padding: 14px; }
    .left-panel { border-right: 1px solid #d8dee9; } .right-panel { border-left: 1px solid #d8dee9; }
    aside h2 { font-size: 15px; margin: 8px 0; } aside h3 { font-size: 13px; margin-top: 18px; }
    .palette { display: grid; gap: 6px; margin-bottom: 16px; }
    .left-panel > button { width: 100%; margin: 3px 0; }
    .canvas { background: white; min-width: 0; }
    .typed-node { width: 210px; min-height: 112px; border: 1px solid #7c8db0; border-radius: 10px; background: white; box-shadow: 0 5px 16px rgba(30, 41, 59, .12); padding-bottom: 8px; }
    .typed-node__title { padding: 9px 11px 3px; font-weight: 700; color: #1e3a8a; }
    .typed-node__type { padding: 0 11px 7px; font-size: 10px; color: #64748b; border-bottom: 1px solid #e2e8f0; }
    .ports { padding: 4px 9px 0; } .port-row { display: flex; justify-content: space-between; gap: 5px; font-size: 11px; line-height: 20px; }
    .port-row small { color: #64748b; font-size: 9px; } .port-row--out { text-align: right; }
    .right-panel input, .right-panel button { width: 100%; margin: 4px 0; }
    details { border-top: 1px solid #d8dee9; margin-top: 14px; padding-top: 10px; } summary { cursor: pointer; font-weight: 700; font-size: 13px; }
    pre { max-height: 220px; overflow: auto; background: #0f172a; color: #dbeafe; border-radius: 6px; padding: 8px; font-size: 10px; white-space: pre-wrap; }
    .run-card { margin-top: 8px; padding: 8px; border: 1px solid #cbd5e1; border-radius: 7px; display: grid; gap: 3px; }
    .run-card small { overflow-wrap: anywhere; color: #64748b; }
    ul { padding-left: 18px; font-size: 12px; } li { margin: 4px 0; }
    ''',
)

write(
    "audio-app/src/main/java/org/hammer/audio/app/WorkbenchRootController.java",
    r'''
    package org.hammer.audio.app;

    import org.springframework.stereotype.Controller;
    import org.springframework.web.bind.annotation.GetMapping;

    /** Redirects the workbench root to the packaged React Flow application. */
    @Controller
    public final class WorkbenchRootController {

      @GetMapping("/")
      public String root() {
        return "redirect:/workbench-ui/index.html";
      }
    }
    ''',
)

write(
    "audio-app/src/test/java/org/hammer/audio/workflow/editor/http/CollaborativeWorkflowPlatformE2ETest.java",
    r'''
    package org.hammer.audio.workflow.editor.http;

    import static org.junit.jupiter.api.Assertions.assertTrue;
    import static org.junit.jupiter.api.Assumptions.assumeTrue;

    import com.microsoft.playwright.Browser;
    import com.microsoft.playwright.BrowserContext;
    import com.microsoft.playwright.Page;
    import com.microsoft.playwright.Playwright;
    import com.microsoft.playwright.options.WaitForSelectorState;
    import org.junit.jupiter.api.Test;
    import org.springframework.boot.test.context.SpringBootTest;
    import org.springframework.boot.test.web.server.LocalServerPort;

    /** Two independent browser contexts proving server-authoritative collaboration and reconnect. */
    @SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "workbench.collaboration.persistence=memory")
    class CollaborativeWorkflowPlatformE2ETest {

      @LocalServerPort private int port;

      @Test
      void twoBrowsersConvergeAfterEditUndoAndReconnect() {
        assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("RUN_COLLABORATION_E2E", "false")));
        try (Playwright playwright = Playwright.create();
            Browser browser = playwright.chromium().launch()) {
          BrowserContext aliceContext = browser.newContext();
          BrowserContext bobContext = browser.newContext();
          Page alice = aliceContext.newPage();
          Page bob = bobContext.newPage();
          String url = "http://127.0.0.1:" + port + "/workbench-ui/index.html";
          alice.navigate(url);
          bob.navigate(url);

          configureActor(alice, "session-e2e", "actor-alice", "Alice");
          alice.getByTestId("create-session").click();
          alice.getByTestId("session-status").waitFor();

          configureActor(bob, "session-e2e", "actor-bob", "Bob");
          bob.getByTestId("join-session").click();
          bob.getByTestId("session-status").waitFor();

          alice.getByTestId("add-synthetic-signal-generator").click();
          bob.locator("[data-testid^='node-']").first().waitFor();
          assertTrue(bob.locator("[data-testid^='node-']").count() >= 1);

          alice.getByTestId("undo").click();
          bob.locator("[data-testid^='node-']").first().waitFor(
              new com.microsoft.playwright.Locator.WaitForOptions()
                  .setState(WaitForSelectorState.DETACHED));

          bob.reload();
          configureActor(bob, "session-e2e", "actor-bob", "Bob");
          bob.getByTestId("join-session").click();
          assertTrue(bob.getByTestId("session-status").textContent().contains("session-e2e"));
        }
      }

      private static void configureActor(
          Page page, String sessionId, String actorId, String displayName) {
        page.getByTestId("session-id").fill(sessionId);
        page.getByTestId("actor-id").fill(actorId);
        page.getByTestId("display-name").fill(displayName);
      }
    }
    ''',
)

write(
    ".github/workflows/collaborative-workflow-e2e.yml",
    r'''
    name: Collaborative workflow E2E

    on:
      pull_request:
        paths:
          - 'audio-core/src/main/java/org/hammer/audio/workflow/**'
          - 'audio-app/src/main/java/org/hammer/audio/**'
          - 'audio-app/workbench-ui/**'
          - 'audio-app/src/test/java/**CollaborativeWorkflowPlatformE2ETest.java'
          - '.github/workflows/collaborative-workflow-e2e.yml'
      workflow_dispatch:

    permissions:
      contents: read

    jobs:
      two-browser:
        runs-on: ubuntu-latest
        timeout-minutes: 20
        steps:
          - uses: actions/checkout@v7
          - uses: actions/setup-java@v5
            with:
              distribution: temurin
              java-version: '21'
              cache: maven
          - uses: actions/setup-node@v5
            with:
              node-version: '22'
              cache: npm
              cache-dependency-path: audio-app/workbench-ui/package-lock.json
          - name: Verify packaged React Flow build
            working-directory: audio-app/workbench-ui
            run: npm ci && npm run build
          - name: Install Playwright Chromium
            run: mvn -B -pl audio-app -am -DskipTests dependency:go-offline && mvn -B -pl audio-app -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args='install --with-deps chromium' org.codehaus.mojo:exec-maven-plugin:3.6.2:java
          - name: Run two-browser collaboration scenario
            env:
              RUN_COLLABORATION_E2E: 'true'
            run: mvn -B -pl audio-app -am -Dtest=CollaborativeWorkflowPlatformE2ETest -Dsurefire.failIfNoSpecifiedTests=false test
          - name: Upload failure diagnostics
            if: failure()
            uses: actions/upload-artifact@v7
            with:
              name: collaboration-e2e-diagnostics
              path: |
                audio-app/target/surefire-reports
                audio-app/test-results
              if-no-files-found: ignore
    ''',
)

write(
    ".github/workflows/workbench-ui-build.yml",
    r'''
    name: Workbench UI build

    on:
      pull_request:
        paths:
          - 'audio-app/workbench-ui/**'
          - 'audio-app/src/main/resources/workbench-ui/**'
          - '.github/workflows/workbench-ui-build.yml'
      workflow_dispatch:

    permissions:
      contents: read

    jobs:
      build:
        runs-on: ubuntu-latest
        steps:
          - uses: actions/checkout@v7
          - uses: actions/setup-node@v5
            with:
              node-version: '22'
              cache: npm
              cache-dependency-path: audio-app/workbench-ui/package-lock.json
          - working-directory: audio-app/workbench-ui
            run: npm ci && npm run build
          - name: Ensure checked-in production bundle is current
            run: git diff --exit-code -- audio-app/src/main/resources/workbench-ui
    ''',
)

print("Generated collaborative React Flow/Yjs UI and E2E workflow")
