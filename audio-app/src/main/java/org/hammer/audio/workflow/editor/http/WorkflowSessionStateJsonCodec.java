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
