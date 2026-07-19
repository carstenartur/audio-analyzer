package org.hammer.audio.workbench.screenshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxMessage;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Test-only transport adapter used to observe durable outbox publication after process restart. */
@Configuration(proxyBeanMethods = false)
public class DurableRestartTestConfiguration {

  private static final String PUBLICATIONS_PATH_PROPERTY =
      "test.outbox.publications.path";

  /** Writes published envelopes to one append-only mounted file when explicitly enabled. */
  @Bean
  @ConditionalOnProperty(name = "test.outbox.publisher.enabled", havingValue = "true")
  WorkflowOutboxPublisher durableRestartOutboxPublisher(Environment environment) {
    Path output = Path.of(environment.getRequiredProperty(PUBLICATIONS_PATH_PROPERTY));
    return message -> append(output, message);
  }

  private static void append(Path output, WorkflowOutboxMessage message) {
    try {
      Path parent = output.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(
          output,
          line(message) + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.APPEND);
    } catch (IOException exception) {
      throw new UncheckedIOException("Could not record published workflow outbox event", exception);
    }
  }

  private static String line(WorkflowOutboxMessage message) {
    return String.join(
        "\t",
        field(message.eventId()),
        field(message.sessionId()),
        Long.toString(message.sequence()),
        Long.toString(message.revision()),
        field(message.eventType()),
        message.occurredAt().toString(),
        field(message.payload()));
  }

  private static String field(String value) {
    return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
  }
}
