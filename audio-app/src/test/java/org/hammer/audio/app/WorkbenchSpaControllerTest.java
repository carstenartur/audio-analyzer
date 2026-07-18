package org.hammer.audio.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkbenchSpaControllerTest {

  private MockMvc mvc;

  @BeforeEach
  void configureSpringMvc() {
    mvc = MockMvcBuilders.standaloneSetup(new WorkbenchSpaController()).build();
  }

  @Test
  void forwardsRootAndNestedClientRoutesToPackagedApplicationShell() throws Exception {
    mvc.perform(get("/workbench"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/index.html"));
    mvc.perform(get("/workbench/history"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/index.html"));
    mvc.perform(get("/workbench/history/commit/abc123"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/index.html"));
  }

  @Test
  void doesNotCaptureWorkflowApiOrAssetRoutes() throws Exception {
    mvc.perform(get("/workflow/projection")).andExpect(status().isNotFound());
    mvc.perform(get("/assets/application.js")).andExpect(status().isNotFound());
  }
}
