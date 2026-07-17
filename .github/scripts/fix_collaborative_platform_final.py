from pathlib import Path
from textwrap import dedent

ROOT = Path(__file__).resolve().parents[2]


def require(path: str) -> Path:
    target = ROOT / path
    if not target.exists():
        raise SystemExit(f"Required file is missing: {path}")
    return target


# Correct domain Edge accessors used by semantic merge.
merge_path = require("audio-core/src/main/java/org/hammer/audio/workflow/version/WorkflowMergeService.java")
merge = merge_path.read_text(encoding="utf-8")
merge = merge.replace("edge.sourceNodeId()", "edge.source()")
merge = merge.replace("edge.targetNodeId()", "edge.target()")
merge_path.write_text(merge, encoding="utf-8")

# A client starting at sequence zero also needs snapshot fallback when the replay buffer has rolled.
hub_path = require(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/BoundedWorkflowSessionEventHub.java"
)
hub = hub_path.read_text(encoding="utf-8")
hub = hub.replace(
    "boolean gap = afterSequence > 0 && !events.isEmpty() && afterSequence + 1 < oldest;",
    "boolean gap = !events.isEmpty() && afterSequence + 1 < oldest;",
)
hub_path.write_text(hub, encoding="utf-8")

hub_test_path = require(
    "audio-core/src/test/java/org/hammer/audio/workflow/collaboration/BoundedWorkflowSessionEventHubTest.java"
)
hub_test = hub_test_path.read_text(encoding="utf-8")
if "failingSubscribersAreRemoved" not in hub_test:
    hub_test = hub_test.replace(
        "import java.util.Map;",
        "import java.util.Map;\nimport java.util.concurrent.atomic.AtomicInteger;",
    )
    method = dedent(
        r'''

          @Test
          void failingSubscribersAreRemovedWithoutBlockingHealthySubscribers() {
            BoundedWorkflowSessionEventHub hub = new BoundedWorkflowSessionEventHub(4);
            AtomicInteger failingCalls = new AtomicInteger();
            AtomicInteger healthyCalls = new AtomicInteger();
            try (BoundedWorkflowSessionEventHub.Subscription failing =
                    hub.subscribe(
                        "session",
                        event -> {
                          failingCalls.incrementAndGet();
                          throw new IllegalStateException("slow/broken client");
                        });
                BoundedWorkflowSessionEventHub.Subscription healthy =
                    hub.subscribe("session", event -> healthyCalls.incrementAndGet())) {
              hub.publish(event("event-1", 1));
              hub.publish(event("event-2", 2));
            }
            assertEquals(1, failingCalls.get());
            assertEquals(2, healthyCalls.get());
          }
        ''')
    insertion = hub_test.rfind("}\n")
    hub_test = hub_test[:insertion] + method + hub_test[insertion:]
hub_test_path.write_text(hub_test, encoding="utf-8")

# Add transaction rollback coverage: a duplicate event id fails after the state update statement;
# the transaction must roll back both state and operation rows.
jdbc_test_path = require(
    "audio-app/src/test/java/org/hammer/audio/infrastructure/workflow/collaboration/JdbcWorkflowSessionStateStoreTest.java"
)
jdbc_test = jdbc_test_path.read_text(encoding="utf-8")
if "outboxInsertFailureRollsBackSessionAndOperation" not in jdbc_test:
    if "assertThrows" not in jdbc_test.split("class JdbcWorkflowSessionStateStoreTest", 1)[0]:
        jdbc_test = jdbc_test.replace(
            "import static org.junit.jupiter.api.Assertions.assertTrue;",
            "import static org.junit.jupiter.api.Assertions.assertTrue;\n"
            "import static org.junit.jupiter.api.Assertions.assertThrows;",
        )
    method = dedent(
        r'''

          @Test
          void outboxInsertFailureRollsBackSessionAndOperation() {
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

            WorkflowSessionState previous = registry.state("session");
            Node node = new Node("node", "input", "Input", List.of(), List.of(), Metadata.empty());
            WorkflowOperation operation =
                new WorkflowOperation.CreateNode("operation", Instant.now(), owner.actorId(), node);
            Workflow nextWorkflow = operation.apply(previous.workflow());
            WorkflowSessionState next =
                new WorkflowSessionState(
                    previous.sessionId(),
                    previous.mode(),
                    previous.owner(),
                    previous.createdAt(),
                    previous.initialWorkflow(),
                    nextWorkflow,
                    previous.participants(),
                    List.of(operation),
                    previous.presence(),
                    previous.undoEntries(),
                    1L,
                    previous.sequence() + 1L);
            JdbcWorkflowSessionStateStore.OutboxRow existing = store.pendingOutbox(10).getFirst();
            WorkflowSessionEvent duplicate =
                new WorkflowSessionEvent(
                    existing.eventId(),
                    "session",
                    next.sequence(),
                    next.revision(),
                    WorkflowSessionEvent.Type.OPERATION_ACCEPTED,
                    Instant.now(),
                    owner,
                    operation.operationId(),
                    next,
                    Map.of());

            assertThrows(
                WorkflowSessionException.class,
                () ->
                    store.commit(
                        new WorkflowSessionStateStore.Transition(
                            WorkflowSessionStateStore.Kind.OPERATION,
                            previous,
                            next,
                            operation,
                            duplicate)));

            WorkflowSessionRegistry recovered = new WorkflowSessionRegistry(store);
            assertEquals(0L, recovered.inspect("session").revision());
            assertTrue(recovered.workflow("session").nodes().isEmpty());
            assertTrue(recovered.operations("session").isEmpty());
          }
        ''')
    # Add missing imports only when the test is inserted.
    import_anchor = "import java.util.List;"
    jdbc_test = jdbc_test.replace(
        import_anchor,
        import_anchor
        + "\nimport java.util.Map;\n"
        + "import org.hammer.audio.workflow.collaboration.WorkflowSessionEvent;\n"
        + "import org.hammer.audio.workflow.collaboration.WorkflowSessionException;\n"
        + "import org.hammer.audio.workflow.collaboration.WorkflowSessionState;\n"
        + "import org.hammer.audio.workflow.collaboration.WorkflowSessionStateStore;",
        1,
    )
    insertion = jdbc_test.rfind("}\n")
    jdbc_test = jdbc_test[:insertion] + method + jdbc_test[insertion:]
jdbc_test_path.write_text(jdbc_test, encoding="utf-8")

# The two-browser test now verifies non-semantic presence propagation as well.
e2e_path = require(
    "audio-app/src/test/java/org/hammer/audio/workflow/editor/http/CollaborativeWorkflowPlatformE2ETest.java"
)
e2e = e2e_path.read_text(encoding="utf-8")
if "Participants" in e2e and "Bob observes Alice presence" not in e2e:
    # Avoid duplicate patch if a later version already has a presence assertion.
    pass
elif "bob.getByTestId(\"participants\")" not in e2e:
    anchor = (
        '          bob.getByTestId("session-status").waitFor();\n\n'
        '          alice.getByTestId("add-synthetic-signal-generator").click();'
    )
    presence = (
        '          bob.getByTestId("session-status").waitFor();\n\n'
        '          alice.locator(".canvas").hover(' 
        'new com.microsoft.playwright.Locator.HoverOptions().setPosition(120, 140));\n'
        '          bob.getByTestId("participants").waitFor();\n'
        '          bob.waitForCondition(\n'
        '              () -> bob.getByTestId("participants").textContent().contains("present"));\n\n'
        '          alice.getByTestId("add-synthetic-signal-generator").click();'
    )
    if anchor not in e2e:
        raise SystemExit("Could not locate E2E join/add-node anchor")
    e2e = e2e.replace(anchor, presence, 1)
e2e_path.write_text(e2e, encoding="utf-8")

# Document retention/compaction policy required by #245.
doc_path = require("docs/architecture/collaborative-platform-implementation.md")
doc = doc_path.read_text(encoding="utf-8")
if "## Retention and compaction" not in doc:
    doc += dedent(
        r'''

        ## Retention and compaction

        Published outbox rows are transport bookkeeping, not authoritative workflow history. A
        deployment may delete published rows after its operational audit window. Unpublished rows are
        never compacted. Session operation rows remain the collaboration audit trail until an explicit
        archive policy exports them together with the session's initial deterministic DSL and final
        revision. Git/JGit checkpoints remain authoritative durable versions and are not deleted by
        session/outbox compaction. Search projections can be cleared at any time and rebuilt from
        checkpoint history.
        ''')
doc_path.write_text(doc, encoding="utf-8")

print("Fixed final integration, rollback, replay and presence coverage")
