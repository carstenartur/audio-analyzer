# Collaboration end-to-end tests

Status: Stage 0, Stage 1, semantic undo/redo and durable full-process restart implemented for issue #249  
Harness: `workbench-screenshot-tests`  
CI: `.github/workflows/collaboration-e2e.yml`

## Purpose

The suite provides executable cross-process evidence that the packaged React Flow workbench and packaged Spring Boot collaboration platform converge across isolated browser actors and recover across a complete application-process restart.

It reuses one Testcontainers and Java Playwright infrastructure. It does not introduce a second browser framework, a test-only semantic endpoint, raw JDBC or a second persistence model.

## Stage 0 and Stage 1: live multi-browser behavior

`WorkbenchTwoBrowserCollaborationIT` proves:

- two isolated browser contexts have independent actor and session storage;
- both contexts create or join the same real collaboration session;
- a semantic edit accepted for client A arrives at client B through ordered SSE without refresh;
- throttled presence is visible remotely but absent from the canonical workflow projection;
- an explicitly stale semantic request receives a revision conflict and never appears in either graph;
- an intentionally interrupted SSE request misses a real accepted operation, reconnects from its previous sequence and converges without a duplicate;
- a full page reload restores actor identity, active session, canonical graph and durable history capabilities;
- personal undo and redo converge across both clients;
- shared undo requires explicit target selection, fresh server preview and acknowledgement;
- the actor that accepted shared undo can redo it and both clients converge again.

The reconnect scenario interrupts only client B's session-event request while ordinary REST reads remain available. Client A appends another semantic operation, and the test proves that B remains on the old revision until the route is restored. Convergence to the missing revision and projection is the replay evidence; the scenario does not depend on browser-specific operating-system offline timing.

## Stage 2: durable process restart

`WorkbenchDurableRestartIT` starts two complete packaged application processes against the same mounted file-backed H2 database and repository name.

It proves:

- both startup cycles run the production migration sequence and Hibernate `validate`;
- an accepted semantic operation, operation identity, revision, event sequence and canonical projection survive complete browser/JVM/container shutdown;
- the exact operation retry remains idempotent before and after restart;
- a durable outbox row left pending without a publisher is leased and published after restart by the production scheduler;
- the post-restart publication keeps the same stable event id, session id, sequence and revision observed before shutdown;
- two exact JGit checkpoints remain in history and can be loaded after restart;
- the older checkpoint excludes a later editor node while the newer checkpoint contains it.

See [Durable collaboration restart evidence](durable-restart-e2e.md) for the process topology, outbox observer design and authority boundaries.

The collaboration aggregate and the versioned workflow-editor store are separately verified. The suite does not claim that every live-session append automatically creates a Git commit or that collaboration append and checkpoint creation are one atomic cross-component transaction.

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

Run the complete collaboration suite:

```bash
mvn -B -Pscreenshot-tests -pl workbench-screenshot-tests \
  -Dit.test=WorkbenchTwoBrowserCollaborationIT,WorkbenchDurableRestartIT verify
```

Run either stage separately by selecting only its test class.

The normal Maven reactor remains Docker-free because `workbench-screenshot-tests` is activated only by the opt-in profile.

## Test architecture

`WorkbenchBrowserHarness` owns:

- one real packaged application container per process phase;
- one Chromium process per harness;
- one isolated `BrowserContext` per actor;
- stable actor identity injected before page startup;
- independent browser storage and SSE connections;
- failure-only diagnostics.

A new actor context starts with empty session storage, so the harness injects only the stable actor identity. It deliberately does not clear the active-session key during later navigations. This lets a full reload exercise the production client's own session-restore path rather than an artificial shortcut.

The durable launcher adds only a test-module transport adapter. The production application, migrations, Hibernate `SessionFactory`, collaboration store, outbox dispatcher and Hibernate-backed JGit store remain the code under test.

## Synchronization discipline

The suite contains no `sleep`, `Thread.sleep`, Playwright `waitForTimeout` or equivalent fixed-delay synchronization. Every wait is tied to an observable contract:

- process HTTP readiness;
- session identity in the active-session view;
- ordered event transport reporting `live` or `reconnecting`;
- semantic and capability revisions;
- expected nodes becoming visible or disappearing;
- server-reported controls becoming enabled;
- preview and confirmation dialogs;
- structured HTTP responses;
- exact commit identities and loaded projections;
- filesystem `WatchService` events for post-restart publication.

Configured timeouts are upper bounds that turn a missing condition into a diagnostic failure. Increasing a delay is not an accepted stabilization strategy.

## Failure diagnostics

On browser scenario failure the harness writes under:

```text
workbench-screenshot-tests/target/collaboration-e2e-failures/<scenario>/
```

For each actor it captures, before the browser context is closed:

- full-page screenshot;
- final HTML;
- Playwright trace with screenshots, snapshots and sources;
- browser console messages;
- page errors;
- failed network requests.

It also writes the packaged-server log and Java failure stack trace. The dedicated GitHub workflow always publishes build/test logs and Failsafe reports and uploads browser/server/durable diagnostics on failure.

## Boundaries

The suite does not make browser or test files authoritative. Assertions observe canonical server projections, revisions, durable history and production dispatch behavior.

It does not:

- add raw JDBC or a second persistence model;
- add a test-only collaboration or persistence endpoint;
- infer undo eligibility from local React Flow state;
- treat presence as workflow DSL or checkpoint data;
- claim automatic Git commits for live collaboration operations;
- claim cross-component atomicity that the application does not implement.
