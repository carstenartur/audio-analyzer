# Audio Analyzer Web Editor

This module is the maintained production source for the browser-based workflow workbench selected by ADR-007.

React Flow is a rendering and input adapter. The server owns workflow validation, semantic operations, revisions, history, sessions, presence and persistence. The browser rebuilds its graph from server projections and contains no database or repository implementation knowledge.

## Toolchain

The toolchain is pinned in both Maven and `package.json`:

- Node.js `22.14.0`
- npm `10.9.2`
- React `18.3.1`
- React Flow `11.11.4`
- Vite `5.4.14`
- TypeScript `5.7.2`

A normal Maven build downloads its own Node/npm runtime through `frontend-maven-plugin`; no developer-global installation is required.

## Local development

Start the Java workbench API on port 8080, then run:

```bash
cd audio-web-editor
npm ci
npm run dev
```

Vite serves the UI on port 5173 and proxies `/workflow` requests, including SSE, to port 8080.

## Collaboration workflow

The initial seed graph remains visible before a session is joined, but it is read-only. Production semantic editing follows this sequence:

1. keep the generated browser actor identity or save an explicit actor/user/display-name triple;
2. create a session or join an existing session by stable session id;
3. wait until the connection state is `live` and the canonical session projection has loaded;
4. submit node, edge and property gestures as stable operation ids with the currently observed revision;
5. replace React Flow state only from accepted REST projections or ordered SSE projections.

The collaboration panel exposes session mode, connection state, semantic revision, event sequence, pending command, participants and remote presence. The three server-defined modes are available; `SHARED_SESSION_PERSONAL_UNDO` is the recommended shared default.

### Reconnect and conflict behavior

- Native SSE event ids are per-session numeric sequences.
- Duplicate events are ignored.
- A normal sequence gap triggers a canonical projection/session reload rather than client-side guessing.
- A `SNAPSHOT` event is always authoritative, including when a restarted server reports a lower sequence than the browser cursor.
- Revision conflicts reconcile the projection before another semantic edit is enabled.
- Cursor, selection and viewport samples use the separate presence endpoint, are throttled and expire locally; they never enter workflow nodes, DSL or Git checkpoints.
- Actor identity is stored per browser tab when session storage is available, so independent tabs do not accidentally share one actor id.

Checkpoint/undo integration for a live collaboration session is intentionally not emulated through the legacy single-workflow endpoints. Those user journeys are implemented by their dedicated follow-up slices.

## Verification

```bash
npm run verify
```

The command runs:

1. strict TypeScript type checking;
2. Node-native unit tests, including operation acceptance, duplicate SSE, replay gaps, restart snapshots, idempotent command revisions and presence isolation;
3. an architecture-boundary lint that rejects persistence implementation knowledge and mandatory Yjs dependencies;
4. a production Vite build;
5. two independent clean production builds whose file sets and SHA-256 content digests must match.

The repository-level command includes the same work:

```bash
mvn -B clean verify --file pom.xml
```

## Integration-generated documentation screenshots

The browser documentation images are generated against the packaged application, not from the Vite dev server or a mocked component:

```bash
# Compare the browser scenarios with committed PNG baselines
mvn -B -Pscreenshot-tests verify --file pom.xml

# Intentionally regenerate the baselines after a reviewed UI change
mvn -B -Pscreenshot-tests verify -DupdateScreenshots=true --file pom.xml
```

`WorkbenchInitialLoadIT` owns the seed-workflow screenshots. `WorkbenchCollaborationScreenshotIT` creates a deterministic live session, waits for ordered SSE, submits two semantic operations and generates `docs/assets/screenshots/workbench/collaboration-session.png`. The root README and the collaboration architecture page embed these committed outputs directly.

The scenarios seed fixed actor metadata and render accepted revisions rather than collision-resistant operation ids. This keeps the documentation output stable while the production command ids remain unique and retry-safe.

## Packaging

Vite writes generated files only below `target/`. Maven copies the verified production output into the module JAR under:

```text
/workbench-ui/
```

`audio-app` depends on this module, so the executable Spring Boot workbench JAR serves the React Flow application without a separate Vite process. Asset filenames contain content hashes for cache-safe deployment.

## Stable browser-test hooks

The collaboration slice exposes stable `data-testid` hooks for session id, actor identity, mode, create/join/leave/close controls, connection state, semantic revision, event sequence, command state, pending operation, participants and remote presence. Process-level two-browser orchestration remains owned by issue #249 rather than being duplicated in this module.

## Historical spike

`workflow-editor-spike/README.md` is retained only as design evidence. It is not a build input and contains no second production source tree.

## Troubleshooting

- Run `mvn -pl audio-web-editor -am clean verify` to isolate frontend failures.
- Remove `audio-web-editor/node_modules` and `audio-web-editor/target` when diagnosing a local npm cache problem.
- A reproducibility failure means the two clean Vite builds produced different filenames or bytes; do not bypass the check by committing generated output.
- A permanently reconnecting client should first verify the session still exists and inspect the RFC 9457 API error shown by the UI.
- A rejected `WORKFLOW_SESSION_REVISION_CONFLICT` is recovered by reloading session metadata and the canonical projection; do not add optimistic browser merges.
- API failures shown by the UI originate from the `/workflow` application-service contract; do not add browser-side persistence fallbacks.

