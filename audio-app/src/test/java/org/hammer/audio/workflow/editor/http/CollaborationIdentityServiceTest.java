package org.hammer.audio.workflow.editor.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.hammer.audio.workflow.collaboration.identity.InMemoryCollaborationIdentityStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CollaborationIdentityServiceTest {

  @Test
  void identicalAnonymousNamesHaveDistinctServerBoundActorsAndResumeKeepsAttribution() {
    CollaborationIdentityService service = service(false);
    MockHttpServletResponse firstResponse = new MockHttpServletResponse();
    var first = service.issue(new MockHttpServletRequest(), firstResponse, null, "Pat");
    var second =
        service.issue(new MockHttpServletRequest(), new MockHttpServletResponse(), null, "Pat");
    assertNotEquals(first.actorId(), second.actorId());
    assertNotEquals(first.participantCode(), second.participantCode());
    assertFalse(first.verified());
    assertEquals("SELF_ASSERTED", first.provenance());
    MockHttpServletRequest resumed = new MockHttpServletRequest();
    resumed.setCookies(firstResponse.getCookies());
    assertEquals(
        first.actorId(),
        service
            .issue(resumed, new MockHttpServletResponse(), first.actorId(), "Forged name")
            .actorId());
    assertEquals("Pat", service.requireActor(resumed, first.actorId()).displayName());
    assertThrows(
        CollaborationIdentityException.class,
        () -> service.requireActor(resumed, second.actorId()));
  }

  @Test
  void sameAccountTabsHaveSeparateActorsAndPrincipalChangesCannotReuseCredential() {
    CollaborationIdentityService service = service(false);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setUserPrincipal(() -> "account/alice");
    MockHttpServletResponse response = new MockHttpServletResponse();
    var first = service.issue(request, response, null, "Alice");
    MockHttpServletResponse secondResponse = new MockHttpServletResponse();
    var second = service.issue(request, secondResponse, null, "Alice");
    request.setCookies(response.getCookies());
    assertNotEquals(first.actorId(), second.actorId());
    request.setCookies(response.getCookies()[0], secondResponse.getCookies()[0]);
    assertEquals(
        service.requireActor(request, first.actorId()).userId(),
        service.requireActor(request, second.actorId()).userId());
    assertTrue(first.verified());
    assertEquals("AUTHENTICATED", first.provenance());
    assertEquals(null, first.accountHandle());
    assertTrue(service.requireActor(request, first.actorId()).userId().startsWith("account-"));
    request.setUserPrincipal(() -> "account/bob");
    assertThrows(
        CollaborationIdentityException.class, () -> service.requireActor(request, first.actorId()));
    request.setUserPrincipal(null);
    assertThrows(
        CollaborationIdentityException.class, () -> service.requireActor(request, first.actorId()));
  }

  @Test
  void handlesAreDisclosedOnlyByExplicitServerPolicyAndCookiesStayHttpOnly() {
    CollaborationIdentityService service = service(true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSecure(true);
    request.setUserPrincipal(() -> "alice");
    MockHttpServletResponse response = new MockHttpServletResponse();
    var actor = service.issue(request, response, null, null);
    assertEquals("alice", actor.accountHandle());
    assertFalse(actor.displayName().contains("alice"));
    var cookie = response.getCookies()[0];
    assertTrue(cookie.isHttpOnly());
    assertTrue(cookie.getSecure());
    assertEquals("Strict", cookie.getAttribute("SameSite"));
    assertFalse(actor.toString().contains(cookie.getValue()));
    assertFalse(actor.toString().contains("account-"));
  }

  @Test
  void credentialExpiresOnTheServerAtThirtyDaysEvenWhenTheCookieIsCopied() {
    var store = new InMemoryCollaborationIdentityStore();
    Instant issuedAt = Instant.parse("2026-11-20T00:00:00Z");
    var service =
        new CollaborationIdentityService(store, false, Clock.fixed(issuedAt, ZoneOffset.UTC));
    var response = new MockHttpServletResponse();
    var actor = service.issue(new MockHttpServletRequest(), response, null, "Pat");
    var request = new MockHttpServletRequest();
    request.setCookies(response.getCookies());
    Instant expiresAt = issuedAt.plus(Duration.ofDays(30));
    var beforeExpiry =
        new CollaborationIdentityService(
            store, false, Clock.fixed(expiresAt.minusNanos(1), ZoneOffset.UTC));
    assertEquals(actor.actorId(), beforeExpiry.requireActor(request, actor.actorId()).actorId());
    var expired =
        new CollaborationIdentityService(store, false, Clock.fixed(expiresAt, ZoneOffset.UTC));
    assertThrows(
        CollaborationIdentityException.class, () -> expired.requireActor(request, actor.actorId()));
    assertThrows(
        CollaborationIdentityException.class,
        () -> expired.issue(request, new MockHttpServletResponse(), actor.actorId(), "Pat"));
    assertEquals("Pat", expired.historyActor(actor.actorId()).displayName());
  }

  private static CollaborationIdentityService service(boolean handles) {
    return new CollaborationIdentityService(
        new InMemoryCollaborationIdentityStore(), handles, Clock.systemUTC());
  }
}
