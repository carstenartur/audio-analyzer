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
  void searchReturnsExactCommitIdentityAndForwardsStructuredFilters() throws Exception {
    IndexedWorkflowHistorySearch search = mock(IndexedWorkflowHistorySearch.class);
    WorkflowHistoryTextQuery query =
        new WorkflowHistoryTextQuery(
            "wingbeat",
            "researcher@example.org",
            "workflows insect",
            Instant.parse("2026-07-01T00:00:00Z"),
            Instant.parse("2026-07-19T23:59:59Z"),
            7);
    when(search.search(query))
        .thenReturn(
            List.of(
                new WorkflowHistoryTextResult(
                    new CommitId("0123456789012345678901234567890123456789"),
                    "Add classifier",
                    "Researcher",
                    "researcher@example.org",
                    Instant.parse("2026-07-19T00:00:00Z"),
                    List.of("workflows/insect/workflow.dsl"))));
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowHistoryIndexHttpAdapter(search)).build();

    mvc.perform(
            get("/workflow/history/index")
                .param("q", "wingbeat")
                .param("author", "researcher@example.org")
                .param("path", "workflows insect")
                .param("from", "2026-07-01T00:00:00Z")
                .param("to", "2026-07-19T23:59:59Z")
                .param("limit", "7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].commitId").value("0123456789012345678901234567890123456789"))
        .andExpect(jsonPath("$[0].changedPaths[0]").value("workflows/insect/workflow.dsl"));

    verify(search).search(query);
  }
}
