package org.hammer.audio.workflow.editor.http;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.history.IndexedWorkflowCombinedHistorySearch;
import org.hammer.audio.workflow.history.WorkflowCombinedHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowCombinedHistoryResult;
import org.hammer.audio.workflow.history.WorkflowHistoryTextQuery;
import org.hammer.audio.workflow.history.WorkflowHistoryTextResult;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryFilter;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryResult;
import org.hammer.audio.workflow.history.WorkflowSemanticProperty;
import org.hammer.audio.workflow.store.CommitId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkflowCombinedHistoryHttpAdapterTest {

  @Test
  void forwardsNestedFiltersAndReturnsGenericAndSemanticEvidence() throws Exception {
    IndexedWorkflowCombinedHistorySearch search =
        mock(IndexedWorkflowCombinedHistorySearch.class);
    Instant timestamp = Instant.parse("2026-07-20T10:00:00Z");
    WorkflowCombinedHistoryQuery query =
        new WorkflowCombinedHistoryQuery(
            new WorkflowHistoryTextQuery(
                "wingbeat",
                "researcher@example.org",
                "workflow",
                timestamp,
                timestamp,
                7),
            new WorkflowSemanticHistoryFilter(
                "experiment",
                "workflow.insect",
                "node.classifier",
                "classifier",
                "wingbeat",
                "mode",
                "safe"));
    CommitId commitId = new CommitId("0123456789012345678901234567890123456789");
    when(search.searchCombined(query))
        .thenReturn(
            List.of(
                new WorkflowCombinedHistoryResult(
                    new WorkflowHistoryTextResult(
                        commitId,
                        "Tune wingbeat classifier",
                        "Researcher",
                        "researcher@example.org",
                        timestamp,
                        List.of("workflow.dsl")),
                    new WorkflowSemanticHistoryResult(
                        commitId,
                        "experiment",
                        "workflow.insect",
                        "Insect observer",
                        List.of("node.classifier"),
                        List.of("classifier"),
                        List.of("Wingbeat classifier"),
                        List.of(new WorkflowSemanticProperty("mode", "safe"))))));
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowCombinedHistoryHttpAdapter(search)).build();

    mvc.perform(
            post("/workflow/history/combined/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "generic": {
                        "text": "wingbeat",
                        "authorEmail": "researcher@example.org",
                        "pathText": "workflow",
                        "from": "2026-07-20T10:00:00Z",
                        "to": "2026-07-20T10:00:00Z",
                        "limit": 7
                      },
                      "semantic": {
                        "branch": "experiment",
                        "workflowId": "workflow.insect",
                        "nodeId": "node.classifier",
                        "nodeType": "classifier",
                        "labelText": "wingbeat",
                        "propertyKey": "mode",
                        "propertyValue": "safe"
                      }
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[0].commit.commitId")
                .value("0123456789012345678901234567890123456789"))
        .andExpect(jsonPath("$[0].commit.changedPaths[0]").value("workflow.dsl"))
        .andExpect(jsonPath("$[0].semantics.branch").value("experiment"))
        .andExpect(jsonPath("$[0].semantics.workflowId").value("workflow.insect"))
        .andExpect(jsonPath("$[0].semantics.nodeTypes[0]").value("classifier"))
        .andExpect(jsonPath("$[0].semantics.properties[0].key").value("mode"))
        .andExpect(jsonPath("$[0].semantics.properties[0].value").value("safe"));

    verify(search).searchCombined(query);
  }
}
