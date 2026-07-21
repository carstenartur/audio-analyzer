package org.hammer.audio.workflow.editor.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.history.PreviewWorkflowMergeCommand;
import org.hammer.audio.workflow.history.ResolveWorkflowMergeCommand;
import org.hammer.audio.workflow.history.WorkflowMergeCommandService;
import org.hammer.audio.workflow.history.WorkflowMergeCommitResult;
import org.hammer.audio.workflow.history.WorkflowMergePreview;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Conflict;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.ConflictKind;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.ElementKind;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Preview;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.ResolutionChoice;
import org.hammer.audio.workflow.store.CommitId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkflowMergeHttpAdapterTest {

  private static final CommitId BASE = new CommitId("1111111111111111111111111111111111111111");
  private static final CommitId LOCAL = new CommitId("2222222222222222222222222222222222222222");
  private static final CommitId REMOTE = new CommitId("3333333333333333333333333333333333333333");
  private static final CommitId MERGED = new CommitId("4444444444444444444444444444444444444444");

  @Test
  void previewReturnsExactGraphStatesAndOrderedConflictChoices() throws Exception {
    WorkflowMergeCommandService service = mock(WorkflowMergeCommandService.class);
    Workflow base = workflow("Base");
    Workflow local = workflow("Local");
    Workflow remote = workflow("Remote");
    Conflict conflict =
        new Conflict(
            "DIVERGENT_VALUE:NODE:4:node:5:label",
            ConflictKind.DIVERGENT_VALUE,
            ElementKind.NODE,
            "node",
            "label",
            "Base",
            "Local",
            "Remote",
            Set.of(
                ResolutionChoice.BASE,
                ResolutionChoice.LOCAL,
                ResolutionChoice.REMOTE,
                ResolutionChoice.CUSTOM));
    when(service.preview(any()))
        .thenReturn(
            new WorkflowMergePreview(
                "main",
                "feature",
                BASE,
                LOCAL,
                REMOTE,
                base,
                local,
                remote,
                new Preview(base, List.of(conflict), List.of())));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkflowMergeHttpAdapter(service)).build();

    mvc.perform(
            post("/workflow/history/merge/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(previewJson()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.baseCommitId").value(BASE.value()))
        .andExpect(jsonPath("$.local.workflowName").value("Local"))
        .andExpect(jsonPath("$.remote.workflowName").value("Remote"))
        .andExpect(jsonPath("$.conflicts[0].kind").value("DIVERGENT_VALUE"))
        .andExpect(jsonPath("$.conflicts[0].fieldPath").value("label"))
        .andExpect(jsonPath("$.readyToCommit").value(false));

    ArgumentCaptor<PreviewWorkflowMergeCommand> command =
        ArgumentCaptor.forClass(PreviewWorkflowMergeCommand.class);
    verify(service).preview(command.capture());
    org.junit.jupiter.api.Assertions.assertEquals("feature", command.getValue().remoteBranch());
  }

  @Test
  void resolveForwardsExplicitDecisionAndReturnsReloadedCommitIdentity() throws Exception {
    WorkflowMergeCommandService service = mock(WorkflowMergeCommandService.class);
    Workflow merged = workflow("Resolved");
    when(service.resolveAndCommit(any()))
        .thenReturn(
            new WorkflowMergeCommitResult(
                "main", BASE, LOCAL, REMOTE, MERGED, merged, "Merge\n\n[workflow-merge]\n"));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkflowMergeHttpAdapter(service)).build();

    mvc.perform(
            post("/workflow/history/merge/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "targetBranch":"main",
                      "remoteBranch":"feature",
                      "baseCommitId":"%s",
                      "localCommitId":"%s",
                      "remoteCommitId":"%s",
                      "expectedHeadCommitId":"%s",
                      "resolutions":[{
                        "conflictId":"conflict.node.label",
                        "choice":"CUSTOM",
                        "customValue":"Resolved"
                      }],
                      "author":"Merger",
                      "message":"Resolve workflow",
                      "timestamp":"2026-07-21T08:00:00Z"
                    }
                    """
                        .formatted(BASE.value(), LOCAL.value(), REMOTE.value(), LOCAL.value())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mergedCommitId").value(MERGED.value()))
        .andExpect(jsonPath("$.workflow.workflowName").value("Resolved"))
        .andExpect(jsonPath("$.auditMessage").exists());

    ArgumentCaptor<ResolveWorkflowMergeCommand> command =
        ArgumentCaptor.forClass(ResolveWorkflowMergeCommand.class);
    verify(service).resolveAndCommit(command.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        "Resolved", command.getValue().resolutions().getFirst().customValue());
    org.junit.jupiter.api.Assertions.assertEquals(
        Instant.parse("2026-07-21T08:00:00Z"), command.getValue().metadata().timestamp());
  }

  private static Workflow workflow(String name) {
    return new Workflow("workflow.merge", name, List.of(), List.of());
  }

  private static String previewJson() {
    return """
        {
          "targetBranch":"main",
          "remoteBranch":"feature",
          "baseCommitId":"%s",
          "localCommitId":"%s",
          "remoteCommitId":"%s"
        }
        """
        .formatted(BASE.value(), LOCAL.value(), REMOTE.value());
  }
}
