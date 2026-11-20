package org.hammer.audio.app;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.hammer.audio.experiment.operations.MicrophoneExperimentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExperimentOperationsHttpAdapterTest {

  @Test
  void acknowledgementDoesNotPublishTheAuthenticatedAccountHandle() throws Exception {
    var service = mock(MicrophoneExperimentService.class);
    var mvc = MockMvcBuilders.standaloneSetup(new ExperimentOperationsHttpAdapter(service)).build();
    mvc.perform(
            post("/experiment-operations/acknowledge")
                .principal(() -> "private-account@example.invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"signal.clipping\"}"))
        .andExpect(status().isOk());
    verify(service).acknowledge("signal.clipping", "Authenticated operator (identity withheld)");
  }

  @Test
  void actualCatalogueAndBlockedPreflightSerializeThroughHttp(@TempDir Path directory)
      throws Exception {
    try (var service = new MicrophoneExperimentService(directory, Clock.systemUTC(), List::of)) {
      var mvc =
          MockMvcBuilders.standaloneSetup(new ExperimentOperationsHttpAdapter(service)).build();
      mvc.perform(get("/experiment-operations/devices"))
          .andExpect(status().isOk())
          .andExpect(content().json("[]"));
      mvc.perform(get("/experiment-operations/catalogue"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.profiles[0].id").value("rectangular-example"))
          .andExpect(jsonPath("$.profiles[0].definition").isString());
      mvc.perform(
              post("/experiment-operations/preflight")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
          {"name":"Unavailable array","sourceMode":"LIVE","profileId":"rectangular-example",
           "scenario":"single-source","recordingId":"","durationSeconds":1,"recordOutput":true,
           "outputFilename":"capture.aarec"}
          """))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.health.state").value("BLOCKED"))
          .andExpect(jsonPath("$.token").isString())
          .andExpect(jsonPath("$.storage.usableBytes").isNumber());
    }
  }

  @Test
  void evidenceCannotClaimZipBeforeFinalization(@TempDir Path directory) throws Exception {
    try (var service = new MicrophoneExperimentService(directory, Clock.systemUTC(), List::of)) {
      var mvc =
          MockMvcBuilders.standaloneSetup(new ExperimentOperationsHttpAdapter(service)).build();
      mvc.perform(get("/experiment-operations/evidence"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("experiment-operation"))
          .andExpect(
              jsonPath("$.detail").value("Finalize the experiment before exporting evidence."));
    }
  }
}
