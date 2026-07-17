package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Non-semantic, disposable presence information for one actor. */
public record WorkflowPresence(
    String actorId,
    double cursorX,
    double cursorY,
    List<String> selectedObjectIds,
    double viewportX,
    double viewportY,
    double viewportZoom,
    Instant updatedAt) {

  public WorkflowPresence {
    Objects.requireNonNull(actorId, "actorId");
    if (actorId.isBlank()) {
      throw new IllegalArgumentException("actorId must not be blank");
    }
    selectedObjectIds = List.copyOf(Objects.requireNonNull(selectedObjectIds, "selectedObjectIds"));
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (!Double.isFinite(cursorX)
        || !Double.isFinite(cursorY)
        || !Double.isFinite(viewportX)
        || !Double.isFinite(viewportY)
        || !Double.isFinite(viewportZoom)
        || viewportZoom <= 0.0) {
      throw new IllegalArgumentException("presence coordinates and zoom must be finite; zoom > 0");
    }
  }
}
