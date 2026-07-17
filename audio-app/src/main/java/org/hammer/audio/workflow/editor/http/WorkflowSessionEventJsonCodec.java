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
