package org.hammer.audio.workflow.editor.http;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.history.WorkflowDiff;
import org.hammer.audio.workflow.history.WorkflowHistoryCommandService;
import org.hammer.audio.workflow.history.WorkflowHistoryComparison;
import org.hammer.audio.workflow.store.CommitId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkflowHistoryFieldChangeHttpAdapterTest {

  private static final CommitId BEFORE = new CommitId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  private static final CommitId AFTER = new CommitId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

  @Test
  void comparisonExposesCompleteFieldPathAndCanonicalValues() throws Exception {
    WorkflowHistoryCommandService service = mock(WorkflowHistoryCommandService.class);
    Workflow before = new Workflow("workflow.compare", "Before", List.of(), List.of());
    Workflow after = new Workflow("workflow.compare", "After", List.of(), List.of());
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
        .andExpect(jsonPath("$.changes[0].kind").value("WORKFLOW_FIELD_CHANGED"))
        .andExpect(jsonPath("$.changes[0].targetId").value("workflow.compare"))
        .andExpect(jsonPath("$.changes[0].propertyKey").value("name"))
        .andExpect(jsonPath("$.changes[0].oldValue").value("Before"))
        .andExpect(jsonPath("$.changes[0].newValue").value("After"));
  }
}
