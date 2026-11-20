package org.hammer.audio.workflow.editor.http;

import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.identity.CollaborationIdentity;
import org.hammer.audio.workflow.collaboration.identity.InMemoryCollaborationIdentityStore;

/**
 * Server-owned identity fixtures for adapter tests; cookies represent explicit credential proof.
 */
final class CollaborationIdentityTestSupport {
  private static final String SECRET = "adapter-test-credential";

  private CollaborationIdentityTestSupport() {}

  static CollaborationIdentityService identities() {
    var store = new InMemoryCollaborationIdentityStore();
    for (String name : new String[] {"owner", "guest"}) {
      String label = Character.toUpperCase(name.charAt(0)) + name.substring(1);
      store.insert(
          new CollaborationIdentity(
              new OperationActor("actor." + name, "user." + name, label),
              "SELF_ASSERTED",
              null,
              CollaborationIdentityService.hash(SECRET),
              Instant.now()));
    }
    return new CollaborationIdentityService(store, false, Clock.systemUTC());
  }

  static Cookie[] cookies() {
    return new Cookie[] {
      new Cookie(CollaborationIdentityService.cookieName("actor.owner"), SECRET),
      new Cookie(CollaborationIdentityService.cookieName("actor.guest"), SECRET)
    };
  }
}
