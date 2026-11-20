package org.hammer.audio.workflow.collaboration.identity;

import java.time.Instant;
import java.util.Objects;
import org.hammer.audio.workflow.collaboration.OperationActor;

/**
 * Immutable server-owned attribution and the one-way hash of a client's credential.
 *
 * @param actor immutable actor and account subject
 * @param provenance server-assigned anonymous, self-asserted or authenticated provenance
 * @param accountHandle authenticated account handle, absent for anonymous identities
 * @param credentialHash SHA-256 digest of the random bearer credential
 * @param createdAt server issuance time
 */
public record CollaborationIdentity(
    OperationActor actor,
    String provenance,
    String accountHandle,
    String credentialHash,
    Instant createdAt) {
  public CollaborationIdentity {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(credentialHash, "credentialHash");
    Objects.requireNonNull(createdAt, "createdAt");
    if (!java.util.Set.of("ANONYMOUS", "SELF_ASSERTED", "AUTHENTICATED").contains(provenance)) {
      throw new IllegalArgumentException("Invalid identity provenance");
    }
    if (!"AUTHENTICATED".equals(provenance) && accountHandle != null) {
      throw new IllegalArgumentException("Only authenticated identities have account handles");
    }
  }
}
