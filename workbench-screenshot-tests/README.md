# Workbench browser integration tests

This opt-in Maven module runs the packaged Spring Boot workbench through Testcontainers and Java Playwright. It owns executable documentation screenshots, two-browser collaboration scenarios and the durable full-process restart scenario.

Run the complete browser suite with:

```bash
mvn -B -Pscreenshot-tests -pl workbench-screenshot-tests verify
```

The default Maven reactor remains Docker-free because this module is activated only by the `screenshot-tests` profile.

The durable restart scenario uses two independent application containers against the same mounted file-backed H2 database. It verifies collaboration recovery, idempotent operation retry, Hibernate-backed JGit checkpoint history and pending outbox publication after restart.

Synchronization is state-based. Tests wait for process readiness, HTTP/SSE contracts, semantic revisions, DOM state and filesystem events; fixed-delay sleeps are not used.

Failure artifacts include server logs, browser HTML/screenshots/traces and an inventory of durable files. Raw database contents are intentionally not copied into CI artifacts.

See [two-browser collaboration evidence](../docs/collaboration-e2e.md) and [durable restart evidence](../docs/durable-restart-e2e.md) for the verified guarantees and architecture boundaries.
