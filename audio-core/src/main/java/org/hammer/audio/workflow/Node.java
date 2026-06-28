package org.hammer.audio.workflow;

import java.util.List;
import java.util.Objects;

/**
 * Pure workflow node definition independent of UI, execution and persistence.
 *
 * @param id stable node identifier
 * @param type logical node type
 * @param label human-readable node label
 * @param inputPorts declared input ports
 * @param outputPorts declared output ports
 * @param metadata extensible metadata for visualization or persistence adapters
 */
public record Node(
    String id,
    String type,
    String label,
    List<Port> inputPorts,
    List<Port> outputPorts,
    Metadata metadata) {

  public Node {
    StableIds.requireStable(id, "id");
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("type must not be blank");
    }
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("label must not be blank");
    }
    Objects.requireNonNull(inputPorts, "inputPorts");
    Objects.requireNonNull(outputPorts, "outputPorts");
    inputPorts = List.copyOf(inputPorts);
    outputPorts = List.copyOf(outputPorts);
    metadata = metadata == null ? Metadata.empty() : metadata;
  }

  public Node(String id, String type, String label, List<Port> inputPorts, List<Port> outputPorts) {
    this(id, type, label, inputPorts, outputPorts, Metadata.empty());
  }
}
