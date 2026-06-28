package org.hammer.audio.workflow;

import java.util.List;
import java.util.Objects;

/**
 * Pure workflow aggregate root independent of UI, execution and persistence.
 *
 * @param id stable workflow identifier
 * @param name human-readable workflow name
 * @param nodes workflow nodes
 * @param edges workflow edges
 * @param metadata extensible metadata for adapters outside the core domain
 */
public record Workflow(
    String id, String name, List<Node> nodes, List<Edge> edges, Metadata metadata) {

  public Workflow {
    StableIds.requireStable(id, "id");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(edges, "edges");
    nodes = List.copyOf(nodes);
    edges = List.copyOf(edges);
    metadata = metadata == null ? Metadata.empty() : metadata;
  }

  public Workflow(String id, String name, List<Node> nodes, List<Edge> edges) {
    this(id, name, nodes, edges, Metadata.empty());
  }
}
