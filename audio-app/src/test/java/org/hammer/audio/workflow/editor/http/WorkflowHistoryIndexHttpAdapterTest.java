package org.hammer.audio.workflow.editor.http;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.history.IndexedWorkflowHistorySearch;
import org.hammer.audio.workflow.history.WorkflowHistoryTextQuery;
import org.hammer.audio.workflow.history.WorkflowHistoryTextResult;
import org.hammer.audio.workflow.store.CommitId;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkflowHistoryIndexHttpAdapterTest {

  @Test
  void searchReturnsExactCommitIdentityWithoutStorageTypes() throws Exception {
    IndexedWorkflowHistorySearch search = mock(IndexedWorkflowHistorySearch.class);
    WorkflowHistoryTextQuery query = new WorkflowHistoryTextQuery("wingbeat", 7);
    when(search.search(query))
        .thenReturn(
            List.of(
                new WorkflowHistoryTextResult(
                    new CommitId("0123456789012345678901234567890123456789"),
                    "Add classifier",
                    "Researcher",
                    "researcher@example.org",
                    Instant.parse("2026-07-19T00:00:00Z"),
                    List.of("workflow.dsl"))));
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowHistoryIndexHttpAdapter(search)).build();

    mvc.perform(get("/workflow/history/index").param("q", "wingbeat").param("limit", "7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].commitId").value("0123456789012345678901234567890123456789"))
        .andExpect(jsonPath("$[0].changedPaths[0]").value("workflow.dsl"));

    verify(search).search(query);
  }
}
