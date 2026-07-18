# Maintained React Flow workflow editor

This is the production browser frontend for the Audio Analyzer workflow workbench. React Flow is a rendering and input adapter over server-owned workflow projections. The browser does not persist or validate canonical workflow state.

The historical comparison implementation remains in `workflow-editor-spike/` as read-only ADR evidence. New production changes belong here.

## Toolchain

- Node.js `24.18.0` LTS
- npm `11.16.0`
- React `19.2.7`
- React Flow `@xyflow/react` `12.11.2`
- Vite `8.1.5`

Maven installs the pinned Node/npm toolchain below this module's `target/` directory. A developer-global Node installation is therefore not required for `mvn clean verify`.

## Local frontend development

Start the Java workbench HTTP adapter on port 8080, then run:

```bash
cd workflow-editor
nvm use
npm ci
npm run dev
```

Vite serves the frontend on port 5173 and proxies `/workflow` to port 8080.

## Verification

```bash
npm run check
npm run build
npm run verify:reproducible
```

`check` runs ESLint, strict TypeScript checking and Vitest. The reproducibility check performs two clean production builds and compares a SHA-256 fingerprint of every relative path and file byte.

## Maven packaging

The module produces a normal Maven JAR containing:

```text
workbench-ui/index.html
workbench-ui/assets/*-<content-hash>.js
workbench-ui/assets/*-<content-hash>.css
```

`audio-app` depends on that JAR. Spring Boot serves the assets from `classpath:/workbench-ui/`, so the executable workbench JAR does not require a separate Vite server.

## State boundary

Server-owned:

- workflow nodes, ports, edges and semantic properties;
- validation and accepted operation order;
- deterministic DSL snapshots and Git checkpoints.

Browser-local and disposable:

- React Flow positions and viewport;
- current selection and open panels;
- unsent form text.

Yjs is not a production dependency of this module. The historical spike retains the scoped Yjs awareness experiment, but no CRDT document owns canonical workflow state.
