package org.hammer.audio.workflow.collaboration.identity;

import java.util.Optional;

/** Stores immutable server-issued identities independently from transient transport connections. */
public interface CollaborationIdentityStore {
  /** Looks up attribution by its non-secret actor id. */
  Optional<CollaborationIdentity> find(String actorId);

  /** Inserts a new identity; existing attribution must never be overwritten. */
  void insert(CollaborationIdentity identity);
}
