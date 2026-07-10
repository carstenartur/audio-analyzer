package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Non-semantic collaboration presence state (cursor/viewport/selection hints).
 *
 * <p>This data is intentionally not part of {@code Workflow} and not persisted in semantic
 * operation history.
 *
 * @param actorId actor owning the presence update
 * @param observedAt timestamp of the presence sample
 * @param attributes transport/UI attributes (cursor, viewport, selection, ...)
 */
public record PresenceState(String actorId, Instant observedAt, Map<String, String> attributes) {

  public PresenceState {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(observedAt, "observedAt");
    Objects.requireNonNull(attributes, "attributes");
    if (actorId.isBlank()) {
      throw new IllegalArgumentException("actorId must not be blank");
    }
    attributes = Map.copyOf(attributes);
  }
}
