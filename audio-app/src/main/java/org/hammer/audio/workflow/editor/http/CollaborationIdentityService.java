package org.hammer.audio.workflow.editor.http;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.identity.CollaborationIdentity;
import org.hammer.audio.workflow.collaboration.identity.CollaborationIdentityStore;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.ActorResponse;

/** Resolves all collaboration authority from server-issued cookies and servlet principals. */
public final class CollaborationIdentityService {
  public static final String ACTOR_HEADER = "X-Workflow-Actor";
  private static final String COOKIE_PREFIX = "aa_collab_";
  private static final String AUTHENTICATED = "AUTHENTICATED";
  private static final int COOKIE_MAX_AGE = 30 * 24 * 60 * 60;
  private final CollaborationIdentityStore store;
  private final boolean discloseHandles;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Instant> activity = new ConcurrentHashMap<>();

  public CollaborationIdentityService(
      CollaborationIdentityStore store, boolean discloseHandles, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.discloseHandles = discloseHandles;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Issues a new client credential or resumes the exact existing immutable identity. */
  public ActorResponse issue(
      HttpServletRequest request,
      HttpServletResponse response,
      String actorId,
      String displayName) {
    if (actorId != null && !actorId.isBlank()) {
      OperationActor actor = requireActor(request, actorId);
      return project(actor, "EDITOR");
    }
    String id = "actor." + UUID.randomUUID();
    Principal principal = request.getUserPrincipal();
    String subject = principal == null ? "anonymous-" + UUID.randomUUID() : subject(principal);
    String name = displayName == null ? "" : displayName.trim();
    if (name.length() > 120 || name.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          "Display name must contain at most 120 printable characters");
    }
    String provenance =
        principal == null ? (name.isEmpty() ? "ANONYMOUS" : "SELF_ASSERTED") : AUTHENTICATED;
    if (name.isEmpty()) {
      name = (principal == null ? "Guest " : "Participant ") + participantCode(id);
    }
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    String credential = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    CollaborationIdentity identity =
        new CollaborationIdentity(
            new OperationActor(id, subject, name),
            provenance,
            principal == null ? null : accountHandle(principal),
            hash(credential),
            clock.instant());
    store.insert(identity);
    Cookie cookie = new Cookie(cookieName(id), credential);
    cookie.setHttpOnly(true);
    cookie.setSecure(request.isSecure());
    cookie.setAttribute("SameSite", "Strict");
    cookie.setPath(request.getContextPath() + "/workflow");
    cookie.setMaxAge(COOKIE_MAX_AGE);
    response.addCookie(cookie);
    activity.put(id, clock.instant());
    return project(identity.actor(), "EDITOR");
  }

  /** Verifies proof of possession and account binding before returning server-owned attribution. */
  public OperationActor requireActor(HttpServletRequest request, String requestedActorId) {
    String actorId = selectActorId(request, requestedActorId);
    CollaborationIdentity identity =
        store.find(actorId).orElseThrow(CollaborationIdentityException::new);
    if (!unexpired(identity)) {
      throw new CollaborationIdentityException();
    }
    String credential = credential(request, actorId);
    if (credential == null
        || credential.length() > 128
        || !MessageDigest.isEqual(
            identity.credentialHash().getBytes(StandardCharsets.US_ASCII),
            hash(credential).getBytes(StandardCharsets.US_ASCII))) {
      throw new CollaborationIdentityException();
    }
    Principal principal = request.getUserPrincipal();
    boolean authenticated = AUTHENTICATED.equals(identity.provenance());
    if (authenticated != (principal != null)
        || authenticated && !identity.actor().userId().equals(subject(principal))) {
      throw new CollaborationIdentityException();
    }
    activity.put(actorId, clock.instant());
    return identity.actor();
  }

  private static String selectActorId(HttpServletRequest request, String requestedActorId) {
    String header = request.getHeader(ACTOR_HEADER);
    if (header != null && requestedActorId != null && !header.equals(requestedActorId)) {
      throw new CollaborationIdentityException();
    }
    String actorId = requestedActorId != null ? requestedActorId : header;
    if (actorId == null) {
      actorId = request.getParameter("actorId");
    }
    if (actorId == null) {
      actorId = onlyActorCookie(request);
    }
    if (actorId == null || !actorId.matches("[A-Za-z0-9._-]{1,255}")) {
      throw new CollaborationIdentityException();
    }
    return actorId;
  }

  /** Checks the lifetime of an already authenticated stream without recording receive activity. */
  boolean isActive(OperationActor actor) {
    return store
        .find(actor.actorId())
        .filter(identity -> identity.actor().equals(actor))
        .filter(this::unexpired)
        .isPresent();
  }

  private boolean unexpired(CollaborationIdentity identity) {
    return clock.instant().isBefore(identity.createdAt().plusSeconds(COOKIE_MAX_AGE));
  }

  /** Projects public attribution without exposing subjects or credential material. */
  public ActorResponse project(OperationActor actor, String role) {
    CollaborationIdentity identity = store.find(actor.actorId()).orElse(null);
    String provenance = identity == null ? "SELF_ASSERTED" : identity.provenance();
    boolean verified = AUTHENTICATED.equals(provenance);
    return new ActorResponse(
        actor.actorId(),
        actor.displayName(),
        provenance,
        verified,
        verified && discloseHandles ? identity.accountHandle() : null,
        role,
        participantCode(actor.actorId()),
        activity.get(actor.actorId()));
  }

  /** Returns durable public attribution for a history actor, including after restart. */
  public ActorResponse historyActor(String actorId) {
    return store.find(actorId).map(identity -> project(identity.actor(), "EDITOR")).orElse(null);
  }

  /** Hashes credential material; the original credential is never retained by the server. */
  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  /** Cookie names distinguish concurrent tabs without replacing any other client's credential. */
  public static String cookieName(String actorId) {
    return COOKIE_PREFIX + actorId;
  }

  private static String subject(Principal principal) {
    return "account-" + hash(accountHandle(principal));
  }

  private static String accountHandle(Principal principal) {
    String name = principal.getName();
    if (name == null || name.isBlank() || name.length() > 255) {
      throw new CollaborationIdentityException();
    }
    return name;
  }

  private static String participantCode(String actorId) {
    return hash(actorId).substring(0, 6).toUpperCase(java.util.Locale.ROOT);
  }

  private static String onlyActorCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    String selected = null;
    for (Cookie cookie : cookies) {
      if (!cookie.getName().startsWith(COOKIE_PREFIX)) {
        continue;
      }
      if (selected != null) {
        return null;
      }
      selected = cookie.getName().substring(COOKIE_PREFIX.length());
    }
    return selected;
  }

  private static String credential(HttpServletRequest request, String actorId) {
    Cookie[] cookies = request.getCookies();
    if (cookies != null) {
      for (Cookie cookie : cookies) {
        if (cookieName(actorId).equals(cookie.getName())) {
          return cookie.getValue();
        }
      }
    }
    return null;
  }
}
