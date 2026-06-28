package org.hammer.audio.workflow;

/**
 * Pure workflow edge definition independent of UI, execution and persistence.
 *
 * @param id stable edge identifier
 * @param sourceNodeId source node identifier
 * @param sourcePortId source port identifier
 * @param targetNodeId target node identifier
 * @param targetPortId target port identifier
 * @param metadata extensible metadata for visualization or persistence adapters
 */
public record Edge(
    String id,
    String sourceNodeId,
    String sourcePortId,
    String targetNodeId,
    String targetPortId,
    Metadata metadata) {

  public Edge {
    StableIds.requireStable(id, "id");
    StableIds.requireStable(sourceNodeId, "sourceNodeId");
    StableIds.requireStable(sourcePortId, "sourcePortId");
    StableIds.requireStable(targetNodeId, "targetNodeId");
    StableIds.requireStable(targetPortId, "targetPortId");
    metadata = metadata == null ? Metadata.empty() : metadata;
  }

  public Edge(
      String id,
      String sourceNodeId,
      String sourcePortId,
      String targetNodeId,
      String targetPortId) {
    this(id, sourceNodeId, sourcePortId, targetNodeId, targetPortId, Metadata.empty());
  }
}
