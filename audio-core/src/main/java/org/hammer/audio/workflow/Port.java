package org.hammer.audio.workflow;

import java.util.Objects;

/**
 * Pure workflow port definition independent of UI, execution and persistence.
 *
 * @param id stable port identifier
 * @param name human-readable port name
 * @param direction direction of data flow
 * @param dataType workflow-level data type identifier
 * @param required whether the port must be connected
 * @param multiplicity whether the port accepts one or many connections
 * @param metadata extensible metadata for visualization or persistence adapters
 */
public record Port(
    String id,
    String name,
    PortDirection direction,
    String dataType,
    boolean required,
    PortMultiplicity multiplicity,
    Metadata metadata) {

  public Port {
    StableIds.requireStable(id, "id");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Objects.requireNonNull(direction, "direction");
    if (dataType == null || dataType.isBlank()) {
      throw new IllegalArgumentException("dataType must not be blank");
    }
    Objects.requireNonNull(multiplicity, "multiplicity");
    metadata = metadata == null ? Metadata.empty() : metadata;
  }

  public Port(
      String id,
      String name,
      PortDirection direction,
      String dataType,
      boolean required,
      PortMultiplicity multiplicity) {
    this(id, name, direction, dataType, required, multiplicity, Metadata.empty());
  }
}
