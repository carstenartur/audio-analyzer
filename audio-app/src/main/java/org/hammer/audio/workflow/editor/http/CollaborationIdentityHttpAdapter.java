package org.hammer.audio.workflow.editor.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.ActorResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Boots or resumes a browser's collaboration identity using an HttpOnly credential cookie. */
@RestController
public final class CollaborationIdentityHttpAdapter {
  private final CollaborationIdentityService identities;

  public CollaborationIdentityHttpAdapter(CollaborationIdentityService identities) {
    this.identities = Objects.requireNonNull(identities, "identities");
  }

  /** Client input is limited to a pseudonym and an optional credential-backed resume selector. */
  @PostMapping("/workflow/identity")
  public ActorResponse identity(
      @RequestBody IdentityRequest input,
      HttpServletRequest request,
      HttpServletResponse response) {
    return identities.issue(request, response, input.actorId(), input.displayName());
  }

  /**
   * Optional pseudonym and existing credential-backed client selector.
   *
   * @param actorId optional existing client selector
   * @param displayName optional pseudonym
   */
  public record IdentityRequest(String actorId, String displayName) {
    // Immutable transport projection; authority is resolved separately.
  }
}
