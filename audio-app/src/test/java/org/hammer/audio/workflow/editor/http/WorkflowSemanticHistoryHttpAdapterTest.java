package org.hammer.audio.workflow.editor.http;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.hammer.audio.workflow.history.IndexedWorkflowSemanticHistorySearch;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryResult;
import org.hammer.audio.workflow.history.WorkflowSemanticProperty;
import org.hammer.audio.workflow.store.CommitId;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkflowSemanticHistoryHttpAdapterTest {

  @Test
  void returnsExactCommitAndForwardsBranchAwareSemanticFilters() throws Exception {
    IndexedWorkflowSemanticHistorySearch search =
        mock(IndexedWorkflowSemanticHistorySearch.class);
    WorkflowSemanticHistoryQuery query =
        new WorkflowSemanticHistoryQuery(
            "experiment",
            "workflow.insect-observer",
            "node.classifier",
            "classifier",
            "wingbeat",
            "threshold",
            "high",
            7);
    when(search.searchSemantic(query))
        .thenReturn(
            List.of(
                new WorkflowSemanticHistoryResult(
                    new CommitId("0123456789012345678901234567890123456789"),
                    "experiment",
                    "workflow.insect-observer",
                    "Wingbeat classifier",
                    List.of("node.classifier"),
                    List.of("classifier"),
                    List.of("Wingbeat classifier"),
                    List.of(new WorkflowSemanticProperty("threshold", "high")))));
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowSemanticHistoryHttpAdapter(search)).build();

    mvc.perform(
            get("/workflow/history/semantic")
                .param("branch", "experiment")
                .param("workflow", "workflow.insect-observer")
                .param("node", "node.classifier")
                .param("type", "classifier")
                .param("label", "wingbeat")
                .param("propertyKey", "threshold")
                .param("propertyValue", "high")
                .param("limit", "7"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[0].commitId")
                .value("0123456789012345678901234567890123456789"))
        .andExpect(jsonPath("$[0].branch").value("experiment"))
        .andExpect(jsonPath("$[0].nodeIds[0]").value("node.classifier"))
        .andExpect(jsonPath("$[0].properties[0].key").value("threshold"))
        .andExpect(jsonPath("$[0].properties[0].value").value("high"));

    verify(search).searchSemantic(query);
  }
}
