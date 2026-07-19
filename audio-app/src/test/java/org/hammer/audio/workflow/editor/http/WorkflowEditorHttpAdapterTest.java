package org.hammer.audio.workflow.editor.http;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkflowEditorHttpAdapterTest {

  @Test
  void historyBindsExplicitBranchAndLimitQueryParameters() throws Exception {
    WorkflowEditorService editorService = mock(WorkflowEditorService.class);
    when(editorService.history("feature/durable", 7)).thenReturn(List.of());
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowEditorHttpAdapter(editorService)).build();

    mvc.perform(get("/workflow/history").param("branch", "feature/durable").param("limit", "7"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json("[]"));

    verify(editorService).history("feature/durable", 7);
  }
}
