package org.hammer.audio.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkbenchRunEndpointWiringTest {

  @Test
  void productionApplicationExposesRunsWithTheIdentityBoundary() {
    new WebApplicationContextRunner()
        .withUserConfiguration(WorkbenchApplication.class)
        .withPropertyValues("spring.flyway.enabled=false", "workbench.persistence.mode=memory")
        .run(
            context -> {
              MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
              mvc.perform(get("/workflow/runs"))
                  .andExpect(status().isOk())
                  .andExpect(content().json("[]"));
              mvc.perform(
                      post("/workflow/runs")
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(
                              """
                              {"startCommandId":"command.unauthenticated", "sourceKind":"LIVE_SESSION",
                               "sessionId":"session.private", "expectedRevision":0}
                              """))
                  .andExpect(status().isForbidden())
                  .andExpect(jsonPath("$.code").value("COLLABORATION_IDENTITY_REQUIRED"));
            });
  }
}
