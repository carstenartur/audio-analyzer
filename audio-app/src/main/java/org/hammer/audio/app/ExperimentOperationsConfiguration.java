package org.hammer.audio.app;

import java.nio.file.Path;
import org.hammer.audio.experiment.operations.MicrophoneExperimentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Host configuration for reusable local microphone experiment operations. */
@Configuration
public class ExperimentOperationsConfiguration {

  /** Persist profiles, imports and complete run evidence under one configurable host directory. */
  @Bean(destroyMethod = "close")
  public MicrophoneExperimentService microphoneExperimentService(
      @Value("${workbench.experiments.directory:}") String configuredDirectory) {
    Path directory =
        configuredDirectory.isBlank()
            ? Path.of(System.getProperty("user.home"), ".audio-analyzer", "experiments")
            : Path.of(configuredDirectory);
    return new MicrophoneExperimentService(directory);
  }
}
