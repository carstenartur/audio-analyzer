package org.hammer.audio.workflow.editor.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.identity.InMemoryCollaborationIdentityStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class CollaborationIdentitySecurityTest {
  private MockMvc mvc;
  private WorkflowSessionRegistry registry;
  private CollaborationIdentityService identities;
  private String ownerId;
  private Cookie[] ownerCookies;

  @BeforeEach
  void configure() throws Exception {
    registry = new WorkflowSessionRegistry();
    identities =
        new CollaborationIdentityService(
            new InMemoryCollaborationIdentityStore(), false, Clock.systemUTC());
    mvc =
        MockMvcBuilders.standaloneSetup(
                new CollaborationIdentityHttpAdapter(identities),
                new WorkflowSessionHttpAdapter(registry, identities),
                new WorkflowSessionEventHttpAdapter(registry, identities))
            .setControllerAdvice(new WorkflowApiExceptionHandler())
            .build();
    MvcResult issued =
        mvc.perform(
                post("/workflow/identity")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"displayName\":\"Pat\",\"verified\":true,\"subjectId\":\"administrator\",\"role\":\"OWNER\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verified").value(false))
            .andExpect(jsonPath("$.userId").doesNotExist())
            .andExpect(jsonPath("$.subjectId").doesNotExist())
            .andReturn();
    ownerId =
        new JsonMapper()
            .readTree(issued.getResponse().getContentAsString())
            .get("actorId")
            .asText();
    ownerCookies = issued.getResponse().getCookies();
    mvc.perform(
            post("/workflow/sessions")
                .cookie(ownerCookies)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
            {"sessionId":"protected","mode":"SHARED_SESSION_PERSONAL_UNDO",
             "actor":{"actorId":"%s","userId":"administrator","displayName":"Forged"}}
            """
                        .formatted(ownerId)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.owner.displayName").value("Pat"))
        .andExpect(jsonPath("$.owner.role").value("OWNER"));
    assertFalse(registry.inspect("protected").owner().userId().equals("administrator"));
  }

  @Test
  void copiedOwnerActorIdWithoutCredentialCannotCloseLeaveOperateReadOrStream() throws Exception {
    mvc.perform(
            delete("/workflow/sessions/protected")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actorId\":\"" + ownerId + "\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/workflow/sessions/protected/leave")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actorId\":\"" + ownerId + "\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/workflow/sessions/protected/history/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actor\":{\"actorId\":\"" + ownerId + "\"}}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/workflow/sessions/protected/projection").param("actorId", ownerId))
        .andExpect(status().isForbidden());
    mvc.perform(get("/workflow/sessions/protected/events").param("actorId", ownerId))
        .andExpect(status().isForbidden());
  }

  @Test
  void anotherClientsCookieCannotTakeOverOwnerAndRejoinDoesNotDuplicateParticipant()
      throws Exception {
    MvcResult other =
        mvc.perform(
                post("/workflow/identity")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\":\"Pat\"}"))
            .andExpect(status().isOk())
            .andReturn();
    mvc.perform(
            delete("/workflow/sessions/protected")
                .cookie(other.getResponse().getCookies())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actorId\":\"" + ownerId + "\"}"))
        .andExpect(status().isForbidden());
    for (int i = 0; i < 2; i++) {
      mvc.perform(
              post("/workflow/sessions/protected/join")
                  .cookie(ownerCookies)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"actorId\":\"" + ownerId + "\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.participants.length()").value(1));
    }
  }

  @Test
  void sessionProjectionCannotReadReplacementAfterMembershipCheck() throws Exception {
    assertReplacementCannotLeak("/projection");
  }

  @Test
  void sessionMetadataCannotReadReplacementAfterMembershipCheck() throws Exception {
    assertReplacementCannotLeak("");
  }

  private void assertReplacementCannotLeak(String suffix) throws Exception {
    WorkflowSessionRegistry original = registry;
    String expectedWorkflowId = original.inspect("protected").workflowId();
    AtomicBoolean replaced = new AtomicBoolean();
    registry =
        mock(
            WorkflowSessionRegistry.class,
            withSettings()
                .spiedInstance(original)
                .defaultAnswer(
                    invocation -> {
                      Object result = invocation.callRealMethod();
                      String method = invocation.getMethod().getName();
                      if ((method.equals("inspect") || method.equals("authorizedWorkflow"))
                          && replaced.compareAndSet(false, true)) {
                        original.close("protected", ownerId);
                        original.create(
                            "protected",
                            CollaborationMode.PRIVATE_WORKSPACE,
                            new OperationActor(
                                "actor.replacement", "user.replacement", "Replacement"),
                            new Workflow(
                                "workflow.replacement-secret", "Private", List.of(), List.of()));
                      }
                      return result;
                    }));
    mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowSessionHttpAdapter(registry, identities))
            .setControllerAdvice(new WorkflowApiExceptionHandler())
            .build();

    mvc.perform(get("/workflow/sessions/protected" + suffix).cookie(ownerCookies))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowId").value(expectedWorkflowId));
    assertTrue(replaced.get());
  }

  @Test
  void presenceUsesServerTimeAndAllowListWithoutChangingSemanticHistory() throws Exception {
    Instant before = Instant.now().minusSeconds(1);
    MvcResult presence =
        mvc.perform(
                put("/workflow/sessions/protected/presence")
                    .cookie(ownerCookies)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
            {"actor":{"actorId":"%s"},"observedAt":"2999-01-01T00:00:00Z",
            "attributes":{"selection":"node.1","secret":"hidden","role":"OWNER","cursor.x":"NaN"}}
            """
                            .formatted(ownerId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.attributes.selection").value("node.1"))
            .andExpect(jsonPath("$.attributes.secret").doesNotExist())
            .andExpect(jsonPath("$.attributes.role").doesNotExist())
            .andExpect(jsonPath("$.attributes['cursor.x']").doesNotExist())
            .andReturn();
    Instant observed =
        Instant.parse(
            new JsonMapper()
                .readTree(presence.getResponse().getContentAsString())
                .get("observedAt")
                .asText());
    assertTrue(!observed.isBefore(before) && !observed.isAfter(Instant.now()));
    assertEquals(0, registry.inspect("protected").operationCount());
    assertEquals(1, registry.inspect("protected").participants().size());
  }
}
