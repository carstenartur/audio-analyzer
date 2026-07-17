from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def require(path: str) -> Path:
    target = ROOT / path
    if not target.exists():
        raise SystemExit(f"Required implementation file is missing: {path}")
    return target


# ---------------------------------------------------------------------------
# Exponential outbox retry/backoff
# ---------------------------------------------------------------------------
store_path = require(
    "audio-app/src/main/java/org/hammer/audio/infrastructure/workflow/collaboration/JdbcWorkflowSessionStateStore.java"
)
store = store_path.read_text(encoding="utf-8")

store = store.replace(
    '"select event_id, event_json, attempt_count from workflow_session_outbox "\n'
    '                + "where published_at is null order by created_at, event_id limit ?",\n'
    '            this::mapOutbox,\n'
    '            limit);',
    '"select event_id, event_json, attempt_count from workflow_session_outbox "\n'
    '                + "where published_at is null "\n'
    '                + "and (next_attempt_at is null or next_attempt_at <= ?) "\n'
    '                + "order by created_at, event_id limit ?",\n'
    '            this::mapOutbox,\n'
    '            Timestamp.from(Instant.now()),\n'
    '            limit);',
)
store = store.replace(
    '"update workflow_session_outbox set published_at = ? where event_id = ?",\n'
    '            Timestamp.from(Instant.now()),\n'
    '            eventId);',
    '"update workflow_session_outbox set published_at = ?, next_attempt_at = null "\n'
    '                + "where event_id = ?",\n'
    '            Timestamp.from(Instant.now()),\n'
    '            eventId);',
)
store = re.sub(
    r"public void markAttempt\(String eventId\) \{\s*jdbc\.update\(\s*\"update workflow_session_outbox set attempt_count = attempt_count \+ 1 where event_id = \?\",\s*eventId\);\s*\}",
    '''public void markAttempt(String eventId, int currentAttemptCount) {
        if (currentAttemptCount < 0) {
          throw new IllegalArgumentException("currentAttemptCount must be >= 0");
        }
        int exponent = Math.min(currentAttemptCount, 9);
        long delaySeconds = Math.min(60L, 1L << exponent);
        Instant nextAttempt = Instant.now().plusSeconds(delaySeconds);
        jdbc.update(
            "update workflow_session_outbox "
                + "set attempt_count = attempt_count + 1, next_attempt_at = ? "
                + "where event_id = ?",
            Timestamp.from(nextAttempt),
            eventId);
      }''',
    store,
    count=1,
    flags=re.S,
)
if "markAttempt(String eventId, int currentAttemptCount)" not in store:
    raise SystemExit("Could not patch outbox markAttempt API")

store = store.replace(
    '+ "published_at timestamp null,"\n'
    '                + "attempt_count integer not null) ");',
    '+ "published_at timestamp null,"\n'
    '                + "next_attempt_at timestamp null,"\n'
    '                + "attempt_count integer not null) ");\n'
    '        jdbc.execute(\n'
    '            "alter table workflow_session_outbox add column if not exists "\n'
    '                + "next_attempt_at timestamp null");',
)
if "next_attempt_at" not in store:
    raise SystemExit("Could not add next_attempt_at schema/query support")
store_path.write_text(store, encoding="utf-8")


dispatcher_path = require(
    "audio-app/src/main/java/org/hammer/audio/infrastructure/workflow/collaboration/WorkflowOutboxDispatcher.java"
)
dispatcher = dispatcher_path.read_text(encoding="utf-8")
dispatcher = dispatcher.replace(
    "store.markAttempt(row.eventId());",
    "store.markAttempt(row.eventId(), row.attemptCount());",
)
if "markAttempt(row.eventId(), row.attemptCount())" not in dispatcher:
    raise SystemExit("Could not patch dispatcher retry call")
dispatcher_path.write_text(dispatcher, encoding="utf-8")


# Add a focused retry deferral test to the existing JDBC integration test.
test_path = require(
    "audio-app/src/test/java/org/hammer/audio/infrastructure/workflow/collaboration/JdbcWorkflowSessionStateStoreTest.java"
)
test = test_path.read_text(encoding="utf-8")
if "failedOutboxRowsAreDeferredBeforeRetry" not in test:
    method = r'''

      @Test
      void failedOutboxRowsAreDeferredBeforeRetry() throws Exception {
        DriverManagerDataSource dataSource =
            new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        WorkflowOperationJsonCodec operationCodec = new WorkflowOperationJsonCodec(mapper);
        WorkflowSessionStateJsonCodec stateCodec =
            new WorkflowSessionStateJsonCodec(mapper, operationCodec);
        WorkflowSessionEventJsonCodec eventCodec =
            new WorkflowSessionEventJsonCodec(mapper, stateCodec);
        JdbcWorkflowSessionStateStore store =
            new JdbcWorkflowSessionStateStore(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                stateCodec,
                eventCodec,
                operationCodec);
        WorkflowSessionRegistry registry = new WorkflowSessionRegistry(store);
        OperationActor owner = new OperationActor("actor", "user", "Owner");
        registry.create(
            "session",
            CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
            owner,
            new Workflow("workflow", "Workflow", List.of(), List.of()));

        JdbcWorkflowSessionStateStore.OutboxRow row = store.pendingOutbox(10).getFirst();
        store.markAttempt(row.eventId(), row.attemptCount());
        assertTrue(store.pendingOutbox(10).isEmpty());
        Thread.sleep(1100L);
        assertFalse(store.pendingOutbox(10).isEmpty());
      }
'''
    insertion = test.rfind("}\n")
    if insertion < 0:
        raise SystemExit("Unexpected JDBC test structure")
    test = test[:insertion] + method + test[insertion:]
    if "assertTrue" not in test.split("class JdbcWorkflowSessionStateStoreTest", 1)[0]:
        test = test.replace(
            "import static org.junit.jupiter.api.Assertions.assertFalse;",
            "import static org.junit.jupiter.api.Assertions.assertFalse;\n"
            "import static org.junit.jupiter.api.Assertions.assertTrue;",
        )
test_path.write_text(test, encoding="utf-8")


# ---------------------------------------------------------------------------
# Enforce session existence on session-scoped history
# ---------------------------------------------------------------------------
checkpoint_service_path = require(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowSessionCheckpointService.java"
)
checkpoint_service = checkpoint_service_path.read_text(encoding="utf-8")
checkpoint_service = checkpoint_service.replace(
    "public List<CommitInfo> history(String branch, int limit) {\n"
    "        return requireStore().history(requireNotBlank(branch, \"branch\"), limit);\n"
    "      }",
    "public List<CommitInfo> history(String sessionId, String branch, int limit) {\n"
    "        sessions.inspect(requireNotBlank(sessionId, \"sessionId\"));\n"
    "        return requireStore().history(requireNotBlank(branch, \"branch\"), limit);\n"
    "      }",
)
if "history(String sessionId, String branch, int limit)" not in checkpoint_service:
    raise SystemExit("Could not patch session-scoped history service")
checkpoint_service_path.write_text(checkpoint_service, encoding="utf-8")

checkpoint_controller_path = require(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowSessionCheckpointHttpAdapter.java"
)
checkpoint_controller = checkpoint_controller_path.read_text(encoding="utf-8")
checkpoint_controller = checkpoint_controller.replace(
    "return service.history(branch, limit).stream().map(HistoryEntry::from).toList();",
    "return service.history(sessionId, branch, limit).stream().map(HistoryEntry::from).toList();",
)
if "service.history(sessionId, branch, limit)" not in checkpoint_controller:
    raise SystemExit("Could not patch session-scoped history controller")
checkpoint_controller_path.write_text(checkpoint_controller, encoding="utf-8")


# ---------------------------------------------------------------------------
# Playwright trace/screenshot diagnostics
# ---------------------------------------------------------------------------
e2e_path = require(
    "audio-app/src/test/java/org/hammer/audio/workflow/editor/http/CollaborativeWorkflowPlatformE2ETest.java"
)
e2e = e2e_path.read_text(encoding="utf-8")
if "alice-trace.zip" not in e2e:
    e2e = e2e.replace(
        "import java.net.URI;\n" if "import java.net.URI;" in e2e else "import com.microsoft.playwright.options.WaitForSelectorState;\n",
        ("import java.net.URI;\n" if "import java.net.URI;" in e2e else "import com.microsoft.playwright.options.WaitForSelectorState;\n")
        + "import java.nio.file.Files;\n"
        + "import java.nio.file.Path;\n",
        1,
    )
    e2e = e2e.replace(
        "          Page alice = aliceContext.newPage();\n"
        "          Page bob = bobContext.newPage();",
        "          Path diagnostics = Path.of(\"target\", \"collaboration-e2e\");\n"
        "          Files.createDirectories(diagnostics);\n"
        "          aliceContext.tracing().start(\n"
        "              new com.microsoft.playwright.Tracing.StartOptions()\n"
        "                  .setScreenshots(true)\n"
        "                  .setSnapshots(true)\n"
        "                  .setSources(true));\n"
        "          bobContext.tracing().start(\n"
        "              new com.microsoft.playwright.Tracing.StartOptions()\n"
        "                  .setScreenshots(true)\n"
        "                  .setSnapshots(true)\n"
        "                  .setSources(true));\n"
        "          Page alice = aliceContext.newPage();\n"
        "          Page bob = bobContext.newPage();",
        1,
    )
    scenario_start = '          String url = "http://127.0.0.1:" + port + "/workbench-ui/index.html";'
    if scenario_start not in e2e:
        raise SystemExit("Could not find E2E scenario start")
    e2e = e2e.replace(scenario_start, "          try {\n" + scenario_start, 1)
    final_assert = (
        '          assertTrue(bob.getByTestId("session-status").textContent().contains("session-e2e"));'
    )
    if final_assert not in e2e:
        raise SystemExit("Could not find E2E scenario end")
    replacement = final_assert + r'''
          } catch (Throwable failure) {
            alice.screenshot(
                new Page.ScreenshotOptions()
                    .setPath(diagnostics.resolve("alice-failure.png"))
                    .setFullPage(true));
            bob.screenshot(
                new Page.ScreenshotOptions()
                    .setPath(diagnostics.resolve("bob-failure.png"))
                    .setFullPage(true));
            throw failure;
          } finally {
            aliceContext.tracing().stop(
                new com.microsoft.playwright.Tracing.StopOptions()
                    .setPath(diagnostics.resolve("alice-trace.zip")));
            bobContext.tracing().stop(
                new com.microsoft.playwright.Tracing.StopOptions()
                    .setPath(diagnostics.resolve("bob-trace.zip")));
          }'''
    e2e = e2e.replace(final_assert, replacement, 1)
e2e_path.write_text(e2e, encoding="utf-8")

# Ensure permanent CI uploads the new trace/screenshot directory.
workflow_path = require(".github/workflows/collaborative-workflow-e2e.yml")
workflow = workflow_path.read_text(encoding="utf-8")
if "audio-app/target/collaboration-e2e" not in workflow:
    workflow = workflow.replace(
        "                audio-app/target/surefire-reports\n",
        "                audio-app/target/surefire-reports\n"
        "                audio-app/target/collaboration-e2e\n",
        1,
    )
workflow_path.write_text(workflow, encoding="utf-8")

# Documentation reflects actual retry and diagnostic behavior.
doc_path = require("docs/architecture/collaborative-platform-implementation.md")
doc = doc_path.read_text(encoding="utf-8")
if "exponential backoff" not in doc:
    doc = doc.replace(
        "independently and marks them after successful delivery.",
        "independently, retries failures with capped exponential backoff and marks rows after "
        "successful delivery.",
    )
if "Playwright traces" not in doc:
    doc = doc.replace(
        "browser contexts to verify live convergence and reconnect.",
        "browser contexts to verify live convergence and reconnect; failed scenarios retain "
        "Playwright traces, screenshots and Surefire reports as CI artifacts.",
    )
doc_path.write_text(doc, encoding="utf-8")

print("Hardened outbox retry, session history and browser diagnostics")
