from pathlib import Path
from textwrap import dedent
import re

ROOT = Path(__file__).resolve().parents[2]


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(dedent(content).lstrip(), encoding="utf-8")


# Fix outer-instance construction in the generated non-static SessionEntry.
registry_path = ROOT / "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionRegistry.java"
registry = registry_path.read_text(encoding="utf-8")
registry = registry.replace(
    "SessionEntry previous = sessions.putIfAbsent(restored.sessionId(), SessionEntry.restore(restored));",
    "SessionEntry restoredEntry =\n"
    "          new SessionEntry(\n"
    "              restored.sessionId(),\n"
    "              restored.mode(),\n"
    "              restored.owner(),\n"
    "              restored.createdAt(),\n"
    "              restored.initialWorkflow(),\n"
    "              restored.operations(),\n"
    "              restored.participants(),\n"
    "              restored.presence(),\n"
    "              restored.undoEntries(),\n"
    "              restored.revision(),\n"
    "              restored.sequence());\n"
    "      if (!restoredEntry.operationLog.currentWorkflow().equals(restored.workflow())) {\n"
    "        throw new IllegalStateException(\n"
    "            \"Restored operation replay diverges for session \" + restored.sessionId());\n"
    "      }\n"
    "      SessionEntry previous = sessions.putIfAbsent(restored.sessionId(), restoredEntry);",
)
registry = registry.replace(
    "SessionEntry created = SessionEntry.create(requiredSessionId, mode, owner, initialWorkflow);",
    "SessionEntry created =\n"
    "        new SessionEntry(\n"
    "            requiredSessionId,\n"
    "            mode,\n"
    "            owner,\n"
    "            Instant.now(),\n"
    "            initialWorkflow,\n"
    "            List.of(),\n"
    "            List.of(owner),\n"
    "            Map.of(),\n"
    "            List.of(),\n"
    "            0L,\n"
    "            1L);",
)
registry = re.sub(
    r"\n        static SessionEntry create\(.*?\n        }\n\n        static SessionEntry restore\(WorkflowSessionState state\) \{.*?\n          return entry;\n        }\n",
    "\n",
    registry,
    flags=re.S,
)
registry_path.write_text(registry, encoding="utf-8")

write(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowOperationJsonCodec.java",
    r'''
    package org.hammer.audio.workflow.editor.http;

    import java.time.Instant;
    import java.util.Arrays;
    import java.util.Objects;
    import org.hammer.audio.workflow.Edge;
    import org.hammer.audio.workflow.Node;
    import org.hammer.audio.workflow.WorkflowOperation;
    import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
    import tools.jackson.databind.JsonNode;
    import tools.jackson.databind.ObjectMapper;
    import tools.jackson.databind.node.ObjectNode;

    /** Shared Jackson-3 codec for API commands and durable operation rows. */
    public final class WorkflowOperationJsonCodec {

      private static final String ALLOWED_PREFIX = "org.hammer.audio.workflow.WorkflowOperation$";
      private final ObjectMapper mapper;

      public WorkflowOperationJsonCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
      }

      public String encode(WorkflowOperation operation) {
        Objects.requireNonNull(operation, "operation");
        ObjectNode root = mapper.createObjectNode();
        root.put("class", operation.getClass().getName());
        root.set("payload", mapper.valueToTree(operation));
        try {
          return mapper.writeValueAsString(root);
        } catch (Exception ex) {
          throw new IllegalStateException("Cannot encode workflow operation", ex);
        }
      }

      public WorkflowOperation decode(String json) {
        try {
          JsonNode root = mapper.readTree(json);
          String className = requiredText(root, "class");
          if (!className.startsWith(ALLOWED_PREFIX)) {
            throw new IllegalArgumentException("Unsupported workflow operation class: " + className);
          }
          Class<?> type = Class.forName(className);
          Object decoded = mapper.treeToValue(root.get("payload"), type);
          if (!(decoded instanceof WorkflowOperation operation)) {
            throw new IllegalArgumentException("Decoded value is not a WorkflowOperation");
          }
          return operation;
        } catch (RuntimeException ex) {
          throw ex;
        } catch (Exception ex) {
          throw new IllegalArgumentException("Cannot decode workflow operation", ex);
        }
      }

      /** Decodes the stable public command representation used by both editor endpoints. */
      public WorkflowOperation decodeApi(JsonNode json) {
        String type = requiredText(json, "type");
        String operationId = requiredText(json, "operationId");
        String author = json.has("author") ? json.get("author").asText() : "web-editor";
        Instant timestamp =
            json.has("timestamp") ? Instant.parse(json.get("timestamp").asText()) : Instant.now();
        return switch (type) {
          case "CreateNode" -> {
            String nodeId = requiredText(json, "nodeId");
            Node node = createCatalogNode(requiredText(json, "catalogType"), nodeId);
            yield new WorkflowOperation.CreateNode(operationId, timestamp, author, node);
          }
          case "ConnectPorts" -> {
            JsonNode edgeJson = requireObject(json, "edge");
            yield new WorkflowOperation.ConnectPorts(
                operationId,
                timestamp,
                author,
                new Edge(
                    requiredText(edgeJson, "id"),
                    requiredText(edgeJson, "sourceNodeId"),
                    requiredText(edgeJson, "sourcePortId"),
                    requiredText(edgeJson, "targetNodeId"),
                    requiredText(edgeJson, "targetPortId")));
          }
          case "DisconnectPorts" -> {
            JsonNode edgeJson = requireObject(json, "disconnectedEdge");
            Edge edge =
                new Edge(
                    requiredText(edgeJson, "id"),
                    requiredText(edgeJson, "sourceNodeId"),
                    requiredText(edgeJson, "sourcePortId"),
                    requiredText(edgeJson, "targetNodeId"),
                    requiredText(edgeJson, "targetPortId"));
            yield new WorkflowOperation.DisconnectPorts(
                operationId, timestamp, author, requiredText(json, "edgeId"), edge);
          }
          case "UpdateProperty" ->
              new WorkflowOperation.UpdateProperty(
                  operationId,
                  timestamp,
                  author,
                  parsePropertyTarget(requiredText(json, "target")),
                  requiredText(json, "targetId"),
                  requiredText(json, "propertyKey"),
                  nullableText(json, "previousValue"),
                  nullableText(json, "newValue"));
          default -> throw new IllegalArgumentException("Unknown operation type: " + type);
        };
      }

      private static WorkflowOperation.PropertyTarget parsePropertyTarget(String value) {
        return Arrays.stream(WorkflowOperation.PropertyTarget.values())
            .filter(candidate -> candidate.name().equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown PropertyTarget: " + value));
      }

      private static Node createCatalogNode(String catalogType, String nodeId) {
        return switch (catalogType) {
          case "recording-input" -> ExperimentNodeCatalog.recordingInput(nodeId);
          case "synthetic-signal-generator" -> ExperimentNodeCatalog.syntheticSignalGenerator(nodeId);
          case "humbug-db-import" -> ExperimentNodeCatalog.humBugDbImport(nodeId);
          case "gain" -> ExperimentNodeCatalog.gain(nodeId);
          case "bandpass-filter" -> ExperimentNodeCatalog.bandpassFilter(nodeId);
          case "fft" -> ExperimentNodeCatalog.fft(nodeId);
          case "wingbeat-feature-extraction" ->
              ExperimentNodeCatalog.wingbeatFeatureExtraction(nodeId);
          case "classifier" -> ExperimentNodeCatalog.classifier(nodeId);
          case "localization" -> ExperimentNodeCatalog.localization(nodeId);
          case "benchmark" -> ExperimentNodeCatalog.benchmark(nodeId);
          case "report" -> ExperimentNodeCatalog.report(nodeId);
          case "evidence-export" -> ExperimentNodeCatalog.evidenceExport(nodeId);
          default -> throw new IllegalArgumentException("Unknown catalog node type: " + catalogType);
        };
      }

      private static JsonNode requireObject(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
          throw new IllegalArgumentException("Missing required object field: " + field);
        }
        return value;
      }

      private static String requiredText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
          throw new IllegalArgumentException("Missing or blank field: " + field);
        }
        return value.asText();
      }

      private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
      }
    }
    ''',
)

write(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowSessionStateJsonCodec.java",
    r'''
    package org.hammer.audio.workflow.editor.http;

    import java.time.Instant;
    import java.util.ArrayList;
    import java.util.LinkedHashMap;
    import java.util.List;
    import java.util.Map;
    import java.util.Objects;
    import org.hammer.audio.workflow.Workflow;
    import org.hammer.audio.workflow.WorkflowOperation;
    import org.hammer.audio.workflow.collaboration.CollaborationMode;
    import org.hammer.audio.workflow.collaboration.OperationActor;
    import org.hammer.audio.workflow.collaboration.WorkflowPresence;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionState;
    import org.hammer.audio.workflow.collaboration.WorkflowUndoEntry;
    import org.hammer.audio.workflow.dsl.WorkflowDslParser;
    import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
    import tools.jackson.databind.JsonNode;
    import tools.jackson.databind.ObjectMapper;
    import tools.jackson.databind.node.ArrayNode;
    import tools.jackson.databind.node.ObjectNode;

    /** Explicit Jackson-3 codec for recoverable session state and interface-valued operations. */
    public final class WorkflowSessionStateJsonCodec {

      private final ObjectMapper mapper;
      private final WorkflowOperationJsonCodec operationCodec;
      private final WorkflowDslSerializer serializer = new WorkflowDslSerializer();
      private final WorkflowDslParser parser = new WorkflowDslParser();

      public WorkflowSessionStateJsonCodec(
          ObjectMapper mapper, WorkflowOperationJsonCodec operationCodec) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.operationCodec = Objects.requireNonNull(operationCodec, "operationCodec");
      }

      public String encode(WorkflowSessionState state) {
        ObjectNode root = mapper.createObjectNode();
        root.put("sessionId", state.sessionId());
        root.put("mode", state.mode().name());
        root.set("owner", mapper.valueToTree(state.owner()));
        root.put("createdAt", state.createdAt().toString());
        root.put("initialDsl", serializer.serialize(state.initialWorkflow()));
        root.put("currentDsl", serializer.serialize(state.workflow()));
        root.put("revision", state.revision());
        root.put("sequence", state.sequence());
        root.set("participants", mapper.valueToTree(state.participants()));
        root.set("presence", mapper.valueToTree(state.presence().values()));
        root.set("undoEntries", mapper.valueToTree(state.undoEntries()));
        ArrayNode operations = root.putArray("operations");
        for (WorkflowOperation operation : state.operations()) {
          try {
            operations.add(mapper.readTree(operationCodec.encode(operation)));
          } catch (Exception ex) {
            throw new IllegalStateException("Cannot encode operation array", ex);
          }
        }
        try {
          return mapper.writeValueAsString(root);
        } catch (Exception ex) {
          throw new IllegalStateException("Cannot encode workflow session state", ex);
        }
      }

      public WorkflowSessionState decode(String json) {
        try {
          JsonNode root = mapper.readTree(json);
          OperationActor owner = mapper.treeToValue(root.get("owner"), OperationActor.class);
          List<OperationActor> participants = new ArrayList<>();
          for (JsonNode actor : root.path("participants")) {
            participants.add(mapper.treeToValue(actor, OperationActor.class));
          }
          Map<String, WorkflowPresence> presence = new LinkedHashMap<>();
          for (JsonNode value : root.path("presence")) {
            WorkflowPresence decoded = mapper.treeToValue(value, WorkflowPresence.class);
            presence.put(decoded.actorId(), decoded);
          }
          List<WorkflowUndoEntry> undoEntries = new ArrayList<>();
          for (JsonNode value : root.path("undoEntries")) {
            undoEntries.add(mapper.treeToValue(value, WorkflowUndoEntry.class));
          }
          List<WorkflowOperation> operations = new ArrayList<>();
          for (JsonNode value : root.path("operations")) {
            operations.add(operationCodec.decode(mapper.writeValueAsString(value)));
          }
          Workflow initial = parser.parse(requiredText(root, "initialDsl"));
          Workflow current = parser.parse(requiredText(root, "currentDsl"));
          return new WorkflowSessionState(
              requiredText(root, "sessionId"),
              CollaborationMode.valueOf(requiredText(root, "mode")),
              owner,
              Instant.parse(requiredText(root, "createdAt")),
              initial,
              current,
              participants,
              operations,
              presence,
              undoEntries,
              root.path("revision").asLong(),
              root.path("sequence").asLong());
        } catch (RuntimeException ex) {
          throw ex;
        } catch (Exception ex) {
          throw new IllegalArgumentException("Cannot decode workflow session state", ex);
        }
      }

      private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
          throw new IllegalArgumentException("Missing state field: " + field);
        }
        return value.asText();
      }
    }
    ''',
)

write(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowSessionEventJsonCodec.java",
    r'''
    package org.hammer.audio.workflow.editor.http;

    import java.time.Instant;
    import java.util.LinkedHashMap;
    import java.util.Map;
    import java.util.Objects;
    import org.hammer.audio.workflow.collaboration.OperationActor;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionEvent;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionState;
    import tools.jackson.databind.JsonNode;
    import tools.jackson.databind.ObjectMapper;
    import tools.jackson.databind.node.ObjectNode;

    /** Explicit durable codec for outbox events. */
    public final class WorkflowSessionEventJsonCodec {

      private final ObjectMapper mapper;
      private final WorkflowSessionStateJsonCodec stateCodec;

      public WorkflowSessionEventJsonCodec(
          ObjectMapper mapper, WorkflowSessionStateJsonCodec stateCodec) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
      }

      public String encode(WorkflowSessionEvent event) {
        ObjectNode root = mapper.createObjectNode();
        root.put("eventId", event.eventId());
        root.put("sessionId", event.sessionId());
        root.put("sequence", event.sequence());
        root.put("revision", event.revision());
        root.put("type", event.type().name());
        root.put("occurredAt", event.occurredAt().toString());
        if (event.actor() != null) {
          root.set("actor", mapper.valueToTree(event.actor()));
        }
        if (event.operationId() != null) {
          root.put("operationId", event.operationId());
        }
        if (event.state() != null) {
          try {
            root.set("state", mapper.readTree(stateCodec.encode(event.state())));
          } catch (Exception ex) {
            throw new IllegalStateException("Cannot encode event state", ex);
          }
        }
        root.set("details", mapper.valueToTree(event.details()));
        try {
          return mapper.writeValueAsString(root);
        } catch (Exception ex) {
          throw new IllegalStateException("Cannot encode workflow session event", ex);
        }
      }

      public WorkflowSessionEvent decode(String json) {
        try {
          JsonNode root = mapper.readTree(json);
          OperationActor actor =
              root.has("actor") ? mapper.treeToValue(root.get("actor"), OperationActor.class) : null;
          WorkflowSessionState state =
              root.has("state")
                  ? stateCodec.decode(mapper.writeValueAsString(root.get("state")))
                  : null;
          Map<String, String> details = new LinkedHashMap<>();
          JsonNode detailsNode = root.path("details");
          detailsNode
              .properties()
              .forEach(entry -> details.put(entry.getKey(), entry.getValue().asText()));
          return new WorkflowSessionEvent(
              requiredText(root, "eventId"),
              requiredText(root, "sessionId"),
              root.path("sequence").asLong(),
              root.path("revision").asLong(),
              WorkflowSessionEvent.Type.valueOf(requiredText(root, "type")),
              Instant.parse(requiredText(root, "occurredAt")),
              actor,
              root.has("operationId") ? root.get("operationId").asText() : null,
              state,
              details);
        } catch (RuntimeException ex) {
          throw ex;
        } catch (Exception ex) {
          throw new IllegalArgumentException("Cannot decode workflow session event", ex);
        }
      }

      private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
          throw new IllegalArgumentException("Missing event field: " + field);
        }
        return value.asText();
      }
    }
    ''',
)

write(
    "audio-app/src/main/java/org/hammer/audio/infrastructure/workflow/collaboration/JdbcWorkflowSessionStateStore.java",
    r'''
    package org.hammer.audio.infrastructure.workflow.collaboration;

    import java.sql.ResultSet;
    import java.sql.SQLException;
    import java.sql.Timestamp;
    import java.time.Instant;
    import java.util.List;
    import java.util.Objects;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionException;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionState;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionStateStore;
    import org.hammer.audio.workflow.editor.http.WorkflowOperationJsonCodec;
    import org.hammer.audio.workflow.editor.http.WorkflowSessionEventJsonCodec;
    import org.hammer.audio.workflow.editor.http.WorkflowSessionStateJsonCodec;
    import org.springframework.dao.DuplicateKeyException;
    import org.springframework.jdbc.core.JdbcTemplate;
    import org.springframework.transaction.support.TransactionTemplate;

    /** Spring-JDBC implementation of the recoverable session state and transactional outbox. */
    public final class JdbcWorkflowSessionStateStore implements WorkflowSessionStateStore {

      private final JdbcTemplate jdbc;
      private final TransactionTemplate transactions;
      private final WorkflowSessionStateJsonCodec stateCodec;
      private final WorkflowSessionEventJsonCodec eventCodec;
      private final WorkflowOperationJsonCodec operationCodec;

      public JdbcWorkflowSessionStateStore(
          JdbcTemplate jdbc,
          TransactionTemplate transactions,
          WorkflowSessionStateJsonCodec stateCodec,
          WorkflowSessionEventJsonCodec eventCodec,
          WorkflowOperationJsonCodec operationCodec) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
        this.eventCodec = Objects.requireNonNull(eventCodec, "eventCodec");
        this.operationCodec = Objects.requireNonNull(operationCodec, "operationCodec");
        initializeSchema();
      }

      @Override
      public List<WorkflowSessionState> restore() {
        return jdbc.query(
            "select state_json from workflow_session_state where closed = false order by session_id",
            (rs, rowNum) -> stateCodec.decode(rs.getString(1)));
      }

      @Override
      public void commit(Transition transition) {
        transactions.executeWithoutResult(
            ignored -> {
              try {
                if (transition.kind() == Kind.CREATE) {
                  insertSession(transition.nextState());
                } else {
                  updateSession(transition);
                }
                if (transition.operation() != null) {
                  jdbc.update(
                      "insert into workflow_session_operation "
                          + "(session_id, revision, operation_id, author_id, operation_json, occurred_at) "
                          + "values (?, ?, ?, ?, ?, ?)",
                      transition.nextState().sessionId(),
                      transition.nextState().revision(),
                      transition.operation().operationId(),
                      transition.operation().author(),
                      operationCodec.encode(transition.operation()),
                      Timestamp.from(transition.operation().timestamp()));
                }
                jdbc.update(
                    "insert into workflow_session_outbox "
                        + "(event_id, session_id, sequence_no, revision, event_json, created_at, attempt_count) "
                        + "values (?, ?, ?, ?, ?, ?, 0)",
                    transition.event().eventId(),
                    transition.event().sessionId(),
                    transition.event().sequence(),
                    transition.event().revision(),
                    eventCodec.encode(transition.event()),
                    Timestamp.from(transition.event().occurredAt()));
              } catch (DuplicateKeyException ex) {
                throw new WorkflowSessionException(
                    WorkflowSessionException.Code.SESSION_ALREADY_EXISTS,
                    transition.nextState().sessionId(),
                    "Duplicate session, operation or event identifier");
              }
            });
      }

      public List<OutboxRow> pendingOutbox(int limit) {
        if (limit < 1) {
          throw new IllegalArgumentException("limit must be >= 1");
        }
        return jdbc.query(
            "select event_id, event_json, attempt_count from workflow_session_outbox "
                + "where published_at is null order by created_at, event_id limit ?",
            this::mapOutbox,
            limit);
      }

      public void markPublished(String eventId) {
        jdbc.update(
            "update workflow_session_outbox set published_at = ? where event_id = ?",
            Timestamp.from(Instant.now()),
            eventId);
      }

      public void markAttempt(String eventId) {
        jdbc.update(
            "update workflow_session_outbox set attempt_count = attempt_count + 1 where event_id = ?",
            eventId);
      }

      private void insertSession(WorkflowSessionState state) {
        jdbc.update(
            "insert into workflow_session_state "
                + "(session_id, revision, sequence_no, state_json, closed, created_at, updated_at) "
                + "values (?, ?, ?, ?, false, ?, ?)",
            state.sessionId(),
            state.revision(),
            state.sequence(),
            stateCodec.encode(state),
            Timestamp.from(state.createdAt()),
            Timestamp.from(Instant.now()));
      }

      private void updateSession(Transition transition) {
        WorkflowSessionState previous = transition.previousState();
        WorkflowSessionState next = transition.nextState();
        boolean closed = transition.kind() == Kind.CLOSE;
        int updated =
            jdbc.update(
                "update workflow_session_state set revision = ?, sequence_no = ?, state_json = ?, "
                    + "closed = ?, updated_at = ? where session_id = ? and revision = ? and sequence_no = ?",
                next.revision(),
                next.sequence(),
                stateCodec.encode(next),
                closed,
                Timestamp.from(Instant.now()),
                next.sessionId(),
                previous.revision(),
                previous.sequence());
        if (updated != 1) {
          throw new WorkflowSessionException(
              WorkflowSessionException.Code.REVISION_CONFLICT,
              next.sessionId(),
              "Concurrent session transition detected");
        }
      }

      private OutboxRow mapOutbox(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxRow(
            rs.getString("event_id"),
            eventCodec.decode(rs.getString("event_json")),
            rs.getInt("attempt_count"));
      }

      private void initializeSchema() {
        jdbc.execute(
            "create table if not exists workflow_session_state ("
                + "session_id varchar(255) primary key,"
                + "revision bigint not null,"
                + "sequence_no bigint not null,"
                + "state_json clob not null,"
                + "closed boolean not null,"
                + "created_at timestamp not null,"
                + "updated_at timestamp not null) ");
        jdbc.execute(
            "create table if not exists workflow_session_operation ("
                + "session_id varchar(255) not null,"
                + "revision bigint not null,"
                + "operation_id varchar(255) not null unique,"
                + "author_id varchar(255) not null,"
                + "operation_json clob not null,"
                + "occurred_at timestamp not null,"
                + "primary key (session_id, revision)) ");
        jdbc.execute(
            "create table if not exists workflow_session_outbox ("
                + "event_id varchar(255) primary key,"
                + "session_id varchar(255) not null,"
                + "sequence_no bigint not null,"
                + "revision bigint not null,"
                + "event_json clob not null,"
                + "created_at timestamp not null,"
                + "published_at timestamp null,"
                + "attempt_count integer not null) ");
        jdbc.execute(
            "create index if not exists idx_workflow_outbox_pending "
                + "on workflow_session_outbox (published_at, created_at)");
      }

      public record OutboxRow(
          String eventId,
          org.hammer.audio.workflow.collaboration.WorkflowSessionEvent event,
          int attemptCount) {}
    }
    ''',
)

write(
    "audio-app/src/main/java/org/hammer/audio/infrastructure/workflow/collaboration/WorkflowOutboxDispatcher.java",
    r'''
    package org.hammer.audio.infrastructure.workflow.collaboration;

    import java.util.Objects;
    import java.util.logging.Level;
    import java.util.logging.Logger;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionEventSink;
    import org.springframework.scheduling.annotation.Scheduled;

    /** Independent retrying dispatcher for committed JDBC outbox rows. */
    public final class WorkflowOutboxDispatcher {

      private static final Logger LOG = Logger.getLogger(WorkflowOutboxDispatcher.class.getName());
      private final JdbcWorkflowSessionStateStore store;
      private final WorkflowSessionEventSink eventSink;

      public WorkflowOutboxDispatcher(
          JdbcWorkflowSessionStateStore store, WorkflowSessionEventSink eventSink) {
        this.store = Objects.requireNonNull(store, "store");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
      }

      @Scheduled(fixedDelayString = "${workbench.collaboration.outbox.delay-ms:100}")
      public void dispatch() {
        for (JdbcWorkflowSessionStateStore.OutboxRow row : store.pendingOutbox(100)) {
          try {
            eventSink.publish(row.event());
            store.markPublished(row.eventId());
          } catch (RuntimeException ex) {
            store.markAttempt(row.eventId());
            LOG.log(Level.WARNING, "Cannot dispatch workflow outbox event " + row.eventId(), ex);
            break;
          }
        }
      }
    }
    ''',
)

write(
    "audio-app/src/main/java/org/hammer/audio/app/CollaborationPlatformConfiguration.java",
    r'''
    package org.hammer.audio.app;

    import org.hammer.audio.infrastructure.workflow.collaboration.JdbcWorkflowSessionStateStore;
    import org.hammer.audio.infrastructure.workflow.collaboration.WorkflowOutboxDispatcher;
    import org.hammer.audio.workflow.collaboration.BoundedWorkflowSessionEventHub;
    import org.hammer.audio.workflow.collaboration.InMemoryWorkflowSessionStateStore;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionStateStore;
    import org.hammer.audio.workflow.editor.http.WorkflowOperationJsonCodec;
    import org.hammer.audio.workflow.editor.http.WorkflowSessionEventJsonCodec;
    import org.hammer.audio.workflow.editor.http.WorkflowSessionStateJsonCodec;
    import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
    import org.springframework.context.annotation.Bean;
    import org.springframework.context.annotation.Configuration;
    import org.springframework.context.annotation.Primary;
    import org.springframework.jdbc.core.JdbcTemplate;
    import org.springframework.scheduling.annotation.EnableScheduling;
    import org.springframework.transaction.PlatformTransactionManager;
    import org.springframework.transaction.support.TransactionTemplate;
    import tools.jackson.databind.ObjectMapper;

    /** Spring Boot 4.1 wiring for collaboration, SSE replay and transactional outbox delivery. */
    @Configuration
    @EnableScheduling
    public class CollaborationPlatformConfiguration {

      @Bean
      public BoundedWorkflowSessionEventHub workflowSessionEventHub() {
        return new BoundedWorkflowSessionEventHub(512);
      }

      @Bean
      public WorkflowOperationJsonCodec workflowOperationJsonCodec(ObjectMapper mapper) {
        return new WorkflowOperationJsonCodec(mapper);
      }

      @Bean
      public WorkflowSessionStateJsonCodec workflowSessionStateJsonCodec(
          ObjectMapper mapper, WorkflowOperationJsonCodec operationCodec) {
        return new WorkflowSessionStateJsonCodec(mapper, operationCodec);
      }

      @Bean
      public WorkflowSessionEventJsonCodec workflowSessionEventJsonCodec(
          ObjectMapper mapper, WorkflowSessionStateJsonCodec stateCodec) {
        return new WorkflowSessionEventJsonCodec(mapper, stateCodec);
      }

      @Bean
      @ConditionalOnProperty(
          name = "workbench.collaboration.persistence",
          havingValue = "memory",
          matchIfMissing = true)
      public WorkflowSessionStateStore inMemoryWorkflowSessionStateStore(
          BoundedWorkflowSessionEventHub eventHub) {
        return new InMemoryWorkflowSessionStateStore(eventHub);
      }

      @Bean
      @ConditionalOnProperty(
          name = "workbench.collaboration.persistence",
          havingValue = "jdbc")
      public JdbcWorkflowSessionStateStore jdbcWorkflowSessionStateStore(
          JdbcTemplate jdbc,
          PlatformTransactionManager transactionManager,
          WorkflowSessionStateJsonCodec stateCodec,
          WorkflowSessionEventJsonCodec eventCodec,
          WorkflowOperationJsonCodec operationCodec) {
        return new JdbcWorkflowSessionStateStore(
            jdbc,
            new TransactionTemplate(transactionManager),
            stateCodec,
            eventCodec,
            operationCodec);
      }

      @Bean
      @ConditionalOnProperty(
          name = "workbench.collaboration.persistence",
          havingValue = "jdbc")
      public WorkflowOutboxDispatcher workflowOutboxDispatcher(
          JdbcWorkflowSessionStateStore store, BoundedWorkflowSessionEventHub eventHub) {
        return new WorkflowOutboxDispatcher(store, eventHub);
      }

      @Bean
      @Primary
      public WorkflowSessionRegistry collaborativeWorkflowSessionRegistry(
          WorkflowSessionStateStore stateStore) {
        return new WorkflowSessionRegistry(stateStore);
      }
    }
    ''',
)

write(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowCollaborationHttpAdapter.java",
    r'''
    package org.hammer.audio.workflow.editor.http;

    import jakarta.validation.Valid;
    import jakarta.validation.constraints.NotBlank;
    import jakarta.validation.constraints.NotNull;
    import jakarta.validation.constraints.Positive;
    import java.time.Instant;
    import java.util.List;
    import java.util.Map;
    import java.util.Objects;
    import org.hammer.audio.workflow.WorkflowOperation;
    import org.hammer.audio.workflow.collaboration.CollaborationMode;
    import org.hammer.audio.workflow.collaboration.OperationActor;
    import org.hammer.audio.workflow.collaboration.WorkflowPresence;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionState;
    import org.hammer.audio.workflow.editor.WorkflowProjection;
    import org.springframework.http.ResponseEntity;
    import org.springframework.web.bind.annotation.DeleteMapping;
    import org.springframework.web.bind.annotation.GetMapping;
    import org.springframework.web.bind.annotation.PathVariable;
    import org.springframework.web.bind.annotation.PostMapping;
    import org.springframework.web.bind.annotation.PutMapping;
    import org.springframework.web.bind.annotation.RequestBody;
    import org.springframework.web.bind.annotation.RequestMapping;
    import org.springframework.web.bind.annotation.RestController;
    import tools.jackson.databind.JsonNode;

    /** Server-authoritative REST command API for collaborative editing, presence and undo/redo. */
    @RestController
    @RequestMapping("/workflow/sessions/{sessionId}")
    public final class WorkflowCollaborationHttpAdapter {

      private final WorkflowSessionRegistry registry;
      private final WorkflowOperationJsonCodec operationCodec;

      public WorkflowCollaborationHttpAdapter(
          WorkflowSessionRegistry registry, WorkflowOperationJsonCodec operationCodec) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.operationCodec = Objects.requireNonNull(operationCodec, "operationCodec");
      }

      @GetMapping("/state")
      public SessionStateResponse state(@PathVariable String sessionId) {
        return SessionStateResponse.from(registry.state(sessionId));
      }

      @GetMapping("/operations")
      public List<WorkflowOperation> operations(@PathVariable String sessionId) {
        return registry.operations(sessionId);
      }

      @PostMapping("/operations")
      public SessionStateResponse apply(
          @PathVariable String sessionId, @Valid @RequestBody OperationRequest request) {
        WorkflowOperation operation = operationCodec.decodeApi(request.operation());
        return SessionStateResponse.from(
            registry
                .applyOperation(
                    sessionId,
                    request.mode(),
                    request.actor().toDomain(),
                    request.expectedRevision(),
                    operation)
                .event()
                .state());
      }

      @PutMapping("/presence")
      public SessionStateResponse presence(
          @PathVariable String sessionId, @Valid @RequestBody PresenceRequest request) {
        OperationActor actor = request.actor().toDomain();
        WorkflowPresence presence =
            new WorkflowPresence(
                actor.actorId(),
                request.cursorX(),
                request.cursorY(),
                request.selectedObjectIds(),
                request.viewportX(),
                request.viewportY(),
                request.viewportZoom(),
                Instant.now());
        return SessionStateResponse.from(
            registry.updatePresence(sessionId, actor, presence).event().state());
      }

      @DeleteMapping("/presence")
      public ResponseEntity<Void> clearPresence(
          @PathVariable String sessionId, @Valid @RequestBody ActorRequest actor) {
        registry.clearPresence(sessionId, actor.toDomain());
        return ResponseEntity.noContent().build();
      }

      @PostMapping("/undo")
      public SessionStateResponse undo(
          @PathVariable String sessionId, @Valid @RequestBody UndoRequest request) {
        return SessionStateResponse.from(
            registry
                .undo(
                    sessionId,
                    request.actor().toDomain(),
                    request.expectedRevision(),
                    request.targetOperationId())
                .event()
                .state());
      }

      @PostMapping("/redo")
      public SessionStateResponse redo(
          @PathVariable String sessionId, @Valid @RequestBody RedoRequest request) {
        return SessionStateResponse.from(
            registry
                .redo(sessionId, request.actor().toDomain(), request.expectedRevision())
                .event()
                .state());
      }

      public record ActorRequest(
          @NotBlank String actorId, @NotBlank String userId, @NotBlank String displayName) {
        OperationActor toDomain() {
          return new OperationActor(actorId, userId, displayName);
        }
      }

      public record OperationRequest(
          @NotNull CollaborationMode mode,
          @Valid @NotNull ActorRequest actor,
          long expectedRevision,
          @NotNull JsonNode operation) {}

      public record PresenceRequest(
          @Valid @NotNull ActorRequest actor,
          double cursorX,
          double cursorY,
          @NotNull List<String> selectedObjectIds,
          double viewportX,
          double viewportY,
          @Positive double viewportZoom) {
        public PresenceRequest {
          selectedObjectIds = List.copyOf(selectedObjectIds);
        }
      }

      public record UndoRequest(
          @Valid @NotNull ActorRequest actor, long expectedRevision, String targetOperationId) {}

      public record RedoRequest(@Valid @NotNull ActorRequest actor, long expectedRevision) {}

      public record SessionStateResponse(
          WorkflowSessionRegistry.SessionSnapshot session,
          WorkflowProjection projection,
          Map<String, WorkflowPresence> presence) {
        static SessionStateResponse from(WorkflowSessionState state) {
          WorkflowSessionRegistry.SessionSnapshot snapshot =
              new WorkflowSessionRegistry.SessionSnapshot(
                  state.sessionId(),
                  state.mode(),
                  state.owner(),
                  state.createdAt(),
                  state.participants(),
                  state.operations().size(),
                  state.workflow().id(),
                  state.revision(),
                  state.sequence());
          return new SessionStateResponse(
              snapshot, WorkflowProjection.fromWorkflow(state.workflow()), state.presence());
        }
      }
    }
    ''',
)

write(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowSessionSseAdapter.java",
    r'''
    package org.hammer.audio.workflow.editor.http;

    import java.io.IOException;
    import java.util.Locale;
    import java.util.Map;
    import java.util.Objects;
    import org.hammer.audio.workflow.collaboration.BoundedWorkflowSessionEventHub;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionEvent;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionState;
    import org.hammer.audio.workflow.editor.WorkflowProjection;
    import org.springframework.http.MediaType;
    import org.springframework.web.bind.annotation.GetMapping;
    import org.springframework.web.bind.annotation.PathVariable;
    import org.springframework.web.bind.annotation.RequestHeader;
    import org.springframework.web.bind.annotation.RequestMapping;
    import org.springframework.web.bind.annotation.RequestParam;
    import org.springframework.web.bind.annotation.RestController;
    import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

    /** Spring MVC SSE adapter with bounded replay and canonical snapshot fallback. */
    @RestController
    @RequestMapping("/workflow/sessions/{sessionId}")
    public final class WorkflowSessionSseAdapter {

      private final BoundedWorkflowSessionEventHub eventHub;
      private final WorkflowSessionRegistry registry;

      public WorkflowSessionSseAdapter(
          BoundedWorkflowSessionEventHub eventHub, WorkflowSessionRegistry registry) {
        this.eventHub = Objects.requireNonNull(eventHub, "eventHub");
        this.registry = Objects.requireNonNull(registry, "registry");
      }

      @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
      public SseEmitter events(
          @PathVariable String sessionId,
          @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
          @RequestParam(required = false) Long afterSequence) {
        long after = parseAfter(lastEventId, afterSequence);
        registry.inspect(sessionId);
        SseEmitter emitter = new SseEmitter(0L);
        Object sendLock = new Object();
        BoundedWorkflowSessionEventHub.Subscription subscription =
            eventHub.subscribe(sessionId, event -> send(emitter, event, sendLock));
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(ignored -> subscription.close());

        BoundedWorkflowSessionEventHub.Replay replay = eventHub.replay(sessionId, after);
        if (replay.gap()) {
          WorkflowSessionState state = registry.state(sessionId);
          WorkflowSessionEvent snapshot =
              WorkflowSessionEvent.create(
                  sessionId,
                  state.sequence(),
                  state.revision(),
                  WorkflowSessionEvent.Type.SNAPSHOT,
                  null,
                  null,
                  state,
                  Map.of("reason", "replay-gap"));
          send(emitter, snapshot, sendLock);
        } else {
          replay.events().forEach(event -> send(emitter, event, sendLock));
        }
        return emitter;
      }

      private static void send(SseEmitter emitter, WorkflowSessionEvent event, Object sendLock) {
        try {
          synchronized (sendLock) {
            emitter.send(
                SseEmitter.event()
                    .id(Long.toString(event.sequence()))
                    .name(event.type().name().toLowerCase(Locale.ROOT))
                    .data(EventResponse.from(event)));
          }
        } catch (IOException | IllegalStateException ex) {
          emitter.completeWithError(ex);
          throw new IllegalStateException("SSE client is no longer writable", ex);
        }
      }

      private static long parseAfter(String lastEventId, Long afterSequence) {
        if (afterSequence != null) {
          return Math.max(0L, afterSequence);
        }
        if (lastEventId == null || lastEventId.isBlank()) {
          return 0L;
        }
        try {
          return Math.max(0L, Long.parseLong(lastEventId));
        } catch (NumberFormatException ex) {
          throw new IllegalArgumentException("Last-Event-ID must be a non-negative sequence", ex);
        }
      }

      public record EventResponse(
          String eventId,
          String sessionId,
          long sequence,
          long revision,
          WorkflowSessionEvent.Type type,
          String actorId,
          String operationId,
          WorkflowProjection projection,
          WorkflowSessionRegistry.SessionSnapshot session,
          Map<String, org.hammer.audio.workflow.collaboration.WorkflowPresence> presence,
          Map<String, String> details) {

        static EventResponse from(WorkflowSessionEvent event) {
          WorkflowSessionState state = event.state();
          WorkflowSessionRegistry.SessionSnapshot snapshot =
              state == null
                  ? null
                  : new WorkflowSessionRegistry.SessionSnapshot(
                      state.sessionId(),
                      state.mode(),
                      state.owner(),
                      state.createdAt(),
                      state.participants(),
                      state.operations().size(),
                      state.workflow().id(),
                      state.revision(),
                      state.sequence());
          return new EventResponse(
              event.eventId(),
              event.sessionId(),
              event.sequence(),
              event.revision(),
              event.type(),
              event.actor() == null ? null : event.actor().actorId(),
              event.operationId(),
              state == null ? null : WorkflowProjection.fromWorkflow(state.workflow()),
              snapshot,
              state == null ? Map.of() : state.presence(),
              event.details());
        }
      }
    }
    ''',
)

write(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowApiExceptionHandler.java",
    r'''
    package org.hammer.audio.workflow.editor.http;

    import jakarta.servlet.http.HttpServletRequest;
    import java.net.URI;
    import java.util.List;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionException;
    import org.springframework.http.HttpStatus;
    import org.springframework.http.ProblemDetail;
    import org.springframework.http.converter.HttpMessageNotReadableException;
    import org.springframework.web.bind.MethodArgumentNotValidException;
    import org.springframework.web.bind.annotation.ExceptionHandler;
    import org.springframework.web.bind.annotation.RestControllerAdvice;

    /** Central RFC 9457 error mapping for workflow REST controllers. */
    @RestControllerAdvice
    public final class WorkflowApiExceptionHandler {

      private static final String PROBLEM_BASE = "https://audio-analyzer.dev/problems/";

      @ExceptionHandler(WorkflowSessionException.class)
      public ProblemDetail handleSessionException(
          WorkflowSessionException exception, HttpServletRequest request) {
        HttpStatus status = statusFor(exception.code());
        ProblemDetail problem =
            problem(status, problemName(exception.code()), exception.getMessage(), request);
        problem.setProperty("code", exception.code().name());
        if (exception.sessionId() != null) {
          problem.setProperty("sessionId", exception.sessionId());
        }
        return problem;
      }

      @ExceptionHandler(MethodArgumentNotValidException.class)
      public ProblemDetail handleValidation(
          MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem =
            problem(
                HttpStatus.BAD_REQUEST,
                "invalid-request",
                "The request body contains invalid values.",
                request);
        List<FieldViolation> violations =
            exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        problem.setProperty("violations", violations);
        return problem;
      }

      @ExceptionHandler(HttpMessageNotReadableException.class)
      public ProblemDetail handleUnreadableBody(
          HttpMessageNotReadableException exception, HttpServletRequest request) {
        return problem(
            HttpStatus.BAD_REQUEST,
            "invalid-json",
            "The request body is not valid JSON for this endpoint.",
            request);
      }

      @ExceptionHandler(IllegalArgumentException.class)
      public ProblemDetail handleIllegalArgument(
          IllegalArgumentException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", exception.getMessage(), request);
      }

      private static ProblemDetail problem(
          HttpStatus status, String name, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title(name));
        problem.setType(URI.create(PROBLEM_BASE + name));
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
      }

      private static HttpStatus statusFor(WorkflowSessionException.Code code) {
        return switch (code) {
          case SESSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
          case INVALID_OPERATION_AUTHOR, INVALID_WORKFLOW_OPERATION ->
              HttpStatus.UNPROCESSABLE_ENTITY;
          case SESSION_ALREADY_EXISTS,
                  PRIVATE_WORKSPACE_ACCESS_DENIED,
                  ACTOR_METADATA_MISMATCH,
                  ACTOR_NOT_JOINED,
                  SESSION_MODE_MISMATCH,
                  SESSION_CLOSE_FORBIDDEN,
                  REVISION_CONFLICT,
                  NOTHING_TO_UNDO,
                  NOTHING_TO_REDO ->
              HttpStatus.CONFLICT;
        };
      }

      private static String problemName(WorkflowSessionException.Code code) {
        return code.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
      }

      private static String title(String problemName) {
        String[] words = problemName.split("-");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
          if (!result.isEmpty()) {
            result.append(' ');
          }
          result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
      }

      public record FieldViolation(String field, String message) {}
    }
    ''',
)

write(
    "audio-app/src/test/java/org/hammer/audio/infrastructure/workflow/collaboration/JdbcWorkflowSessionStateStoreTest.java",
    r'''
    package org.hammer.audio.infrastructure.workflow.collaboration;

    import static org.junit.jupiter.api.Assertions.assertEquals;
    import static org.junit.jupiter.api.Assertions.assertFalse;

    import java.time.Instant;
    import java.util.List;
    import java.util.UUID;
    import org.hammer.audio.workflow.Metadata;
    import org.hammer.audio.workflow.Node;
    import org.hammer.audio.workflow.Workflow;
    import org.hammer.audio.workflow.WorkflowOperation;
    import org.hammer.audio.workflow.collaboration.BoundedWorkflowSessionEventHub;
    import org.hammer.audio.workflow.collaboration.CollaborationMode;
    import org.hammer.audio.workflow.collaboration.OperationActor;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
    import org.hammer.audio.workflow.editor.http.WorkflowOperationJsonCodec;
    import org.hammer.audio.workflow.editor.http.WorkflowSessionEventJsonCodec;
    import org.hammer.audio.workflow.editor.http.WorkflowSessionStateJsonCodec;
    import org.junit.jupiter.api.Test;
    import org.springframework.jdbc.core.JdbcTemplate;
    import org.springframework.jdbc.datasource.DataSourceTransactionManager;
    import org.springframework.jdbc.datasource.DriverManagerDataSource;
    import org.springframework.transaction.support.TransactionTemplate;
    import tools.jackson.databind.ObjectMapper;

    class JdbcWorkflowSessionStateStoreTest {

      @Test
      void sessionAndPendingOutboxRecoverAfterStoreRestart() {
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
        Node node = new Node("node", "input", "Input", List.of(), List.of(), Metadata.empty());
        registry.applyOperation(
            "session",
            CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
            owner,
            0L,
            new WorkflowOperation.CreateNode("operation", Instant.now(), owner.actorId(), node));

        WorkflowSessionRegistry recovered = new WorkflowSessionRegistry(store);
        assertEquals(1L, recovered.inspect("session").revision());
        assertEquals(1, recovered.workflow("session").nodes().size());
        assertFalse(store.pendingOutbox(10).isEmpty());
      }
    }
    ''',
)

# Add Spring JDBC and H2 without changing the existing Boot-4 starter choices.
pom_path = ROOT / "audio-app/pom.xml"
pom = pom_path.read_text(encoding="utf-8")
if "spring-boot-starter-jdbc" not in pom:
    marker = """    <dependency>\n      <groupId>org.springframework.boot</groupId>\n      <artifactId>spring-boot-starter-validation</artifactId>\n    </dependency>\n"""
    addition = marker + """    <dependency>\n      <groupId>org.springframework.boot</groupId>\n      <artifactId>spring-boot-starter-jdbc</artifactId>\n    </dependency>\n"""
    if marker not in pom:
        raise SystemExit("Cannot locate validation starter in audio-app/pom.xml")
    pom = pom.replace(marker, addition, 1)
if "com.h2database" not in pom:
    marker = """    <dependency>\n      <groupId>com.formdev</groupId>\n      <artifactId>flatlaf</artifactId>\n"""
    addition = """    <dependency>\n      <groupId>com.h2database</groupId>\n      <artifactId>h2</artifactId>\n      <scope>runtime</scope>\n    </dependency>\n""" + marker
    if marker not in pom:
        raise SystemExit("Cannot locate FlatLaf dependency in audio-app/pom.xml")
    pom = pom.replace(marker, addition, 1)
pom_path.write_text(pom, encoding="utf-8")

props_path = ROOT / "audio-app/src/main/resources/application.properties"
props = props_path.read_text(encoding="utf-8") if props_path.exists() else ""
settings = """

# Collaboration defaults. Set persistence=jdbc and a file-backed datasource URL for recovery.
workbench.collaboration.persistence=memory
workbench.collaboration.outbox.delay-ms=100
spring.datasource.url=jdbc:h2:mem:audio-analyzer;DB_CLOSE_DELAY=-1
spring.datasource.username=sa
spring.datasource.password=
"""
if "workbench.collaboration.persistence" not in props:
    props += settings
props_path.parent.mkdir(parents=True, exist_ok=True)
props_path.write_text(props, encoding="utf-8")

print("Generated collaboration Spring/JDBC/SSE layer")
