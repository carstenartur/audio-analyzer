package org.hammer.audio.workflow.store;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable metadata attached to a workflow commit.
 *
 * <p>Owned by the persistence facade layer.
 *
 * @param author commit author identifier (non-blank)
 * @param message human-readable commit message (non-blank)
 * @param timestamp commit instant
 */
public record CommitMetadata(String author, String message, Instant timestamp) {

  public CommitMetadata {
    if (author == null || author.isBlank()) {
      throw new IllegalArgumentException("author must not be blank");
    }
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message must not be blank");
    }
    Objects.requireNonNull(timestamp, "timestamp");
  }
}
