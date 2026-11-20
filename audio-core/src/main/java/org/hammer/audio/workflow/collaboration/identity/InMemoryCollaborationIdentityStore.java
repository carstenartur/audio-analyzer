package org.hammer.audio.workflow.collaboration.identity;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Identity storage for the explicitly non-durable memory and filesystem collaboration modes. */
public final class InMemoryCollaborationIdentityStore implements CollaborationIdentityStore {
  private final Map<String, CollaborationIdentity> identities = new ConcurrentHashMap<>();

  @Override
  public Optional<CollaborationIdentity> find(String actorId) {
    return Optional.ofNullable(identities.get(actorId));
  }

  @Override
  public void insert(CollaborationIdentity identity) {
    if (identities.putIfAbsent(identity.actor().actorId(), identity) != null) {
      throw new IllegalStateException("Collaboration identity already exists");
    }
  }
}
