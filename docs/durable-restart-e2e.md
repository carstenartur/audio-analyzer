# Durable collaboration restart end-to-end evidence

Status: implemented as Stage 2 of issue #249  
Scenario: `WorkbenchDurableRestartIT`  
CI: `.github/workflows/collaboration-e2e.yml`

## Purpose

The durable restart scenario proves that the packaged Spring Boot workbench can stop completely and start as a new process against the same durable database while retaining collaboration state, transactional-outbox identity and JGit-backed workflow history.

The scenario uses the production Hibernate persistence mode, the application-managed `SessionFactory`, the released `jgit-storage-hibernate` integration, versioned Flyway migrations and Hibernate schema validation. It does not add a raw-JDBC test repository, a second Hibernate bootstrap or a test-only persistence endpoint.

## Process boundary

The test starts two independent application containers in sequence:

```text
process 1
  -> file-backed H2 database mounted from the host
  -> collaboration publisher disabled
  -> accepted operation and pending outbox row
  -> two JGit checkpoints
  -> complete browser, JVM and container shutdown

process 2
  -> same mounted database and repository name
  -> migrations run again, then Hibernate validate
  -> collaboration session recovery
  -> pending outbox dispatch through the production scheduler
  -> JGit history and exact commit load
```

No in-memory application object, browser process, Spring context or container is shared across the restart boundary. Only the mounted durable files are reused.

## Proven collaboration recovery

Before shutdown, the first process:

1. creates a shared session through the production browser/API path;
2. appends a semantic `CreateNode` operation with an explicit operation id;
3. observes the accepted SSE event and records its stable event id and sequence;
4. retries the exact same operation envelope and proves that revision and graph cardinality do not change;
5. stops with the outbox publisher intentionally absent, leaving the durable outbox row pending.

After restart, the second process proves through the production API before browser join that:

- the session exists;
- semantic revision `1` was recovered;
- the canonical projection contains the accepted node;
- operation identity and aggregate ordering survived;
- the exact retry still returns the existing result without appending a duplicate.

The browser then joins the recovered session and renders the same canonical revision and node.

## Proven outbox recovery

The second process registers a test-module-only `WorkflowOutboxPublisher` before Spring evaluates the production conditional outbox configuration. This activates the real `WorkflowOutboxDispatcher` and `ScheduledWorkflowOutboxDispatcher`; the test adapter only records envelopes that the production dispatcher publishes.

The scenario verifies that:

- no publication occurs while process 1 has no publisher;
- the pending row remains durable across full shutdown;
- process 2 leases and publishes it;
- the published event id is identical to the event id observed through SSE before shutdown;
- session id, sequence and revision are unchanged;
- the persisted transport-neutral event type is `WORKFLOW_OPERATION_ACCEPTED`;
- exactly one matching publication is observed.

The publication wait uses `WatchService` file events and an upper failure deadline. It does not poll with `sleep`.

## Proven JGit checkpoint recovery

The scenario also exercises the production workflow-editor version store:

1. create a first checkpoint;
2. apply another deterministic editor operation;
3. create a second checkpoint;
4. restart the complete application;
5. query branch history;
6. load both exact commit ids;
7. prove that the older snapshot lacks the later node and the newer snapshot contains it.

This verifies persistence of refs, commits, trees and workflow DSL through the real Hibernate-backed JGit store.

## Important boundary: session state and checkpoints

The collaboration aggregate and the versioned workflow-editor store are separately authoritative contexts:

- collaboration operations update the durable session aggregate and transactional outbox;
- checkpoint commands version the workflow-editor snapshot through `VersionedWorkflowStore` and JGit.

The test proves both contexts survive the same physical restart. It does **not** claim that every accepted live-session operation automatically creates a Git commit or that session append and checkpoint creation are one cross-component atomic transaction. Live-session checkpoint semantics require an explicit application command with its own expected-revision contract.

## Synchronization discipline

The durable scenario contains no `sleep`, `Thread.sleep` or Playwright `waitForTimeout`. Progress is driven by observable conditions:

- HTTP readiness of each new process;
- active-session identity and live SSE state;
- semantic revision and visible graph nodes;
- direct production API responses;
- exact checkpoint identities and loaded projections;
- filesystem `WatchService` events for the published-envelope file.

Configured timeouts are failure bounds only.

## Local execution

Build the packaged application and optional browser-test reactor:

```bash
mvn -B -Pscreenshot-tests -DskipTests install \
  -pl workbench-screenshot-tests -am
```

Install the pinned Chromium runtime once:

```bash
mvn -B -Pscreenshot-tests -pl workbench-screenshot-tests \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.classpathScope=test \
  -Dexec.args="install --with-deps chromium"
```

Run only the durable restart scenario:

```bash
mvn -B -Pscreenshot-tests -pl workbench-screenshot-tests \
  -Dit.test=WorkbenchDurableRestartIT verify
```

Run all collaboration scenarios exactly as CI does:

```bash
mvn -B -Pscreenshot-tests -pl workbench-screenshot-tests \
  -Dit.test=WorkbenchTwoBrowserCollaborationIT,WorkbenchDurableRestartIT verify
```

The normal Maven reactor remains Docker-free because the module is activated only by the opt-in profile.

## Failure evidence

The dedicated workflow always publishes Maven logs and Failsafe reports. Browser/process failures additionally use the shared harness to capture screenshots, final HTML, Playwright traces, browser/network diagnostics and packaged-server logs. Durable filesystem diagnostics should contain only file metadata and the test publication envelope—not a copied production database.
