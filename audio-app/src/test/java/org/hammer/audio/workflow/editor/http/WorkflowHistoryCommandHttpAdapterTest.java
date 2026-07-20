package org.hammer.audio.workflow.editor.http;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.history.RestoreWorkflowVersionCommand;
import org.hammer.audio.workflow.history.WorkflowDiff;
import org.hammer.audio.workflow.history.WorkflowHistoryCommandService;
import org.hammer.audio.workflow.history.WorkflowHistoryComparison;
import org.hammer.audio.workflow.history.WorkflowRestoreResult;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkflowHistoryCommandHttpAdapterTest {

  private static final CommitId BEFORE =
      new CommitId("1111111111111111111111111111111111111111");
  private static final CommitId AFTER =
      new CommitId("2222222222222222222222222222222222222222");
  private static final CommitId RESTORED =
      new CommitId("3333333333333333333333333333333333333333");
  private static final Instant RESTORE_TIME = Instant.parse("2026-07-20T08:00:00Z");

  @Test
  void compareReturnsBothGraphsAndStableSemanticChangeAtoms() throws Exception {
    WorkflowHistoryCommandService service = mock(WorkflowHistoryCommandService.class);
    Workflow before = new Workflow("workflow.compare", "Before", List.of(), List.of());
    Workflow after =
        new Workflow(
            "workflow.compare",
            "After",
            List.of(new Node("node.gain", "gain", "Gain", List.of(), List.of())),
            List.of());
    when(service.compare("main", BEFORE, AFTER))
        .thenReturn(
            new WorkflowHistoryComparison(
                BEFORE, AFTER, before, after, WorkflowDiff.compute(before, after)));
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowHistoryCommandHttpAdapter(service)).build();

    mvc.perform(
            post("/workflow/history/compare")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"branch":"main","beforeCommitId":"%s","afterCommitId":"%s"}
                    """
                        .formatted(BEFORE.value(), AFTER.value())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.beforeCommitId").value(BEFORE.value()))
        .andExpect(jsonPath("$.afterCommitId").value(AFTER.value()))
        .andExpect(jsonPath("$.before.nodes.length()").value(0))
        .andExpect(jsonPath("$.after.nodes[0].id").value("node.gain"))
        .andExpect(jsonPath("$.changes[0].kind").value("NODE_ADDED"));

    verify(service).compare("main", BEFORE, AFTER);
  }

  @Test
  void restoreForwardsExpectedHeadAndReturnsAuditCommitIdentity() throws Exception {
    WorkflowHistoryCommandService service = mock(WorkflowHistoryCommandService.class);
    CommitMetadata metadata = new CommitMetadata("Restorer", "Restore baseline", RESTORE_TIME);
    RestoreWorkflowVersionCommand command =
        new RestoreWorkflowVersionCommand("main", BEFORE, AFTER, metadata);
    when(service.restore(command))
        .thenReturn(new WorkflowRestoreResult("main", BEFORE, AFTER, RESTORED));
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowHistoryCommandHttpAdapter(service)).build();

    mvc.perform(
            post("/workflow/history/restore")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "branch":"main",
                      "targetCommitId":"%s",
                      "expectedHeadCommitId":"%s",
                      "author":"Restorer",
                      "message":"Restore baseline",
                      "timestamp":"2026-07-20T08:00:00Z"
                    }
                    """
                        .formatted(BEFORE.value(), AFTER.value())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.branch").value("main"))
        .andExpect(jsonPath("$.targetCommitId").value(BEFORE.value()))
        .andExpect(jsonPath("$.previousHeadCommitId").value(AFTER.value()))
        .andExpect(jsonPath("$.restoredCommitId").value(RESTORED.value()));

    verify(service).restore(command);
  }
}
