# Audio Analyzer Web Editor

This module is the maintained production source for the browser-based workflow workbench selected by ADR-007.

React Flow is a rendering and input adapter. The server owns workflow validation, semantic operations, revisions, history and persistence. The browser rebuilds its graph from server projections and contains no database or repository implementation knowledge.

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

Vite serves the UI on port 5173 and proxies `/workflow` requests to port 8080.

## Verification

```bash
npm run verify
```

The command runs:

1. strict TypeScript type checking;
2. Node-native unit tests;
3. an architecture-boundary lint that rejects persistence implementation knowledge and mandatory Yjs dependencies;
4. a production Vite build;
5. two independent clean production builds whose file sets and SHA-256 content digests must match.

The repository-level command includes the same work:

```bash
mvn -B clean verify --file pom.xml
```

## Packaging

Vite writes generated files only below `target/`. Maven copies the verified production output into the module JAR under:

```text
/workbench-ui/
```

`audio-app` depends on this module, so the executable Spring Boot workbench JAR serves the React Flow application without a separate Vite process. Asset filenames contain content hashes for cache-safe deployment.

## Historical spike

`workflow-editor-spike/README.md` is retained only as design evidence. It is not a build input and contains no second production source tree.

## Troubleshooting

- Run `mvn -pl audio-web-editor -am clean verify` to isolate frontend failures.
- Remove `audio-web-editor/node_modules` and `audio-web-editor/target` when diagnosing a local npm cache problem.
- A reproducibility failure means the two clean Vite builds produced different filenames or bytes; do not bypass the check by committing generated output.
- API failures shown by the UI originate from the `/workflow` application-service contract; do not add browser-side persistence fallbacks.

