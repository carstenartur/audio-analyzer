package org.hammer.audio.infrastructure.workflow.search;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.store.WorkflowSnapshot;

/** Deterministically extracted workflow-domain values used by the disposable semantic index. */
record WorkflowSemanticProjectionValues(
    String workflowId,
    String workflowName,
    List<String> nodeIds,
    List<String> nodeTypes,
    List<String> nodeLabels,
    List<String> propertyKeys,
    List<String> propertyValues,
    List<String> propertyPairs) {

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  WorkflowSemanticProjectionValues {
    workflowId = requireNotBlank(workflowId, "workflowId");
    workflowName = requireNotBlank(workflowName, "workflowName");
    nodeIds = normalizedValues(nodeIds);
    nodeTypes = normalizedValues(nodeTypes);
    nodeLabels = normalizedValues(nodeLabels);
    propertyKeys = normalizedValues(propertyKeys);
    propertyValues = normalizedValues(propertyValues);
    propertyPairs = normalizedValues(propertyPairs);
  }

  static WorkflowSemanticProjectionValues from(
      WorkflowSnapshot snapshot, WorkflowDslParser parser) {
    Objects.requireNonNull(snapshot, "snapshot");
    Workflow workflow = Objects.requireNonNull(parser, "parser").parse(snapshot.dslText());
    if (!snapshot.workflowId().equals(workflow.id())) {
      throw new IllegalArgumentException(
          "workflow.id and workflow DSL id differ: "
              + snapshot.workflowId()
              + " != "
              + workflow.id());
    }

    List<Node> nodes = workflow.nodes().stream().sorted(Comparator.comparing(Node::id)).toList();
    List<String> propertyKeys = new ArrayList<>();
    List<String> propertyValues = new ArrayList<>();
    List<String> propertyPairs = new ArrayList<>();
    addMetadata(workflow.metadata().entries(), propertyKeys, propertyValues, propertyPairs);
    for (Node node : nodes) {
      addMetadata(node.metadata().entries(), propertyKeys, propertyValues, propertyPairs);
    }

    return new WorkflowSemanticProjectionValues(
        workflow.id(),
        workflow.name(),
        nodes.stream().map(Node::id).toList(),
        nodes.stream().map(Node::type).toList(),
        nodes.stream().map(Node::label).toList(),
        propertyKeys,
        propertyValues,
        propertyPairs);
  }

  static String encodeValues(Collection<String> values) {
    return normalizedValues(values).stream()
        .map(WorkflowSemanticProjectionValues::encodeValue)
        .reduce((left, right) -> left + "\n" + right)
        .orElse("");
  }

  static List<String> decodeValues(String encodedValues) {
    if (encodedValues == null || encodedValues.isBlank()) {
      return List.of();
    }
    return encodedValues.lines().filter(value -> !value.isBlank()).map(WorkflowSemanticProjectionValues::decodeValue).toList();
  }

  static String encodePair(String key, String value) {
    return encodeValue(requireNotBlank(key, "propertyKey"))
        + "."
        + encodeValue(requireNotBlank(value, "propertyValue"));
  }

  private static void addMetadata(
      Map<String, String> entries,
      List<String> keys,
      List<String> values,
      List<String> pairs) {
    entries.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              String key = requireNotBlank(entry.getKey(), "propertyKey");
              String value = Objects.toString(entry.getValue(), "");
              keys.add(key);
              values.add(value);
              pairs.add(encodePair(key, value));
            });
  }

  private static List<String> normalizedValues(Collection<String> values) {
    Objects.requireNonNull(values, "values");
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    values.stream().map(value -> Objects.requireNonNull(value, "value")).sorted().forEach(normalized::add);
    return List.copyOf(normalized);
  }

  private static String encodeValue(String value) {
    return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decodeValue(String value) {
    return new String(DECODER.decode(value), StandardCharsets.UTF_8);
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    String normalized = value.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return normalized;
  }
}
