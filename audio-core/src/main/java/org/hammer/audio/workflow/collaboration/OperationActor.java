package org.hammer.audio.workflow.collaboration;

import java.util.Objects;

/**
 * Actor/user metadata attached to collaboration operations and events.
 *
 * @param actorId stable collaboration actor identifier
 * @param userId stable user identifier
 * @param displayName user-facing display name
 */
public record OperationActor(String actorId, String userId, String displayName) {

  public OperationActor {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(displayName, "displayName");
    if (actorId.isBlank()) {
      throw new IllegalArgumentException("actorId must not be blank");
    }
    if (userId.isBlank()) {
      throw new IllegalArgumentException("userId must not be blank");
    }
    if (displayName.isBlank()) {
      throw new IllegalArgumentException("displayName must not be blank");
    }
  }

  public static OperationActor forAuthor(String author) {
    return new OperationActor(author, author, author);
  }
}
