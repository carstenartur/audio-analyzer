# Two-browser collaboration end-to-end tests

Status: staged implementation for issue #249  
Harness: `workbench-screenshot-tests`  
CI: `.github/workflows/collaboration-e2e.yml`

## Purpose

The suite provides executable cross-process evidence that the packaged React Flow workbench and the packaged Spring Boot collaboration platform converge across isolated browser actors.

It deliberately reuses the existing Testcontainers and Java Playwright infrastructure instead of introducing a second browser framework or test-only server endpoint.

The first staged suite proves:

- two isolated browser contexts have independent actor and session storage;
- both contexts create or join the same real collaboration session;
- a semantic React Flow edit accepted for client A arrives at client B through ordered SSE without refresh;
- throttled presence is visible to the remote browser but absent from the canonical workflow projection;
- an explicitly stale semantic request receives a revision conflict and never appears in either graph;
- an offline browser reconnects from its accepted sequence without duplicating operations;
- a full page reload restores actor and active-session identity and reloads canonical graph plus durable history;
- personal undo and redo converge across both clients;
- shared undo requires explicit target selection, fresh server preview and acknowledgement;
- the actor that accepted shared undo can redo it and both clients converge again.

Durable full-process restart remains a separate stage because it must validate persisted collaboration rows, outbox delivery and later checkpoint/Git history through one restart boundary. It will extend the same harness rather than create another stack.

## Local execution

Prerequisites:

- JDK 21;
- Maven;
- Docker;
- network access for Maven dependencies and the first Playwright browser installation.

Build the packaged application and browser-test reactor without running tests:

```bash
mvn -B -Pscreenshot-tests -DskipTests install \
  -pl workbench-screenshot-tests -am
```

Install the pinned Playwright Chromium runtime:

```bash
mvn -B -Pscreenshot-tests -pl workbench-screenshot-tests \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.classpathScope=test \
  -Dexec.args="install --with-deps chromium"
```

Run only the two-browser scenarios:

```bash
mvn -B -Pscreenshot-tests -pl workbench-screenshot-tests \
  -Dit.test=WorkbenchTwoBrowserCollaborationIT verify
```

The normal Maven reactor remains Docker-free because `workbench-screenshot-tests` is activated only by the opt-in profile.

## Test architecture

`WorkbenchBrowserHarness` owns:

- one real packaged application container;
- one Chromium process;
- one isolated `BrowserContext` per actor;
- stable actor identity injected before page startup;
- independent browser storage and SSE connections;
- failure-only diagnostics.

The scenario code uses visible workbench selectors, semantic revision and connection-state markers, ordered UI convergence and real HTTP problem responses. It does not use arbitrary sleeps.

The stale-operation assertion intentionally submits a production semantic operation from the second browser with an old `expectedRevision`. This exercises the same REST parser, domain validation and durable append boundary as a real stale client while proving no optimistic graph residue appears.

## Failure diagnostics

On scenario failure the harness writes under:

```text
workbench-screenshot-tests/target/collaboration-e2e-failures/<scenario>/
```

For each actor it captures:

- full-page screenshot;
- final HTML;
- Playwright trace with screenshots, snapshots and sources;
- browser console messages;
- page errors;
- failed network requests.

It also writes the complete packaged-server log and the Java failure stack trace.

The dedicated GitHub workflow additionally publishes:

- `collaboration-e2e-logs`: complete Maven build, Playwright installation and test-session logs;
- `collaboration-e2e-results`: Failsafe XML and text reports;
- `collaboration-e2e-failures`: browser traces, screenshots and server diagnostics when a scenario fails.

These artifacts make early reactor failures and later browser failures independently diagnosable.

## Boundaries

The suite does not make browser state authoritative. Assertions observe canonical server projections, revisions and SSE-driven UI state.

It does not:

- add raw JDBC or a second persistence model;
- add a test-only collaboration endpoint;
- infer undo eligibility from local React Flow state;
- replace Hibernate restart tests;
- treat presence as workflow DSL or checkpoint data;
- close issue #249 until the durable process-restart stage and final milestone coverage are complete.

