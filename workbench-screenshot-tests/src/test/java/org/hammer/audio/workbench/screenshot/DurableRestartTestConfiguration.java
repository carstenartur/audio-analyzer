package org.hammer.audio.workbench.screenshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxMessage;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;

/** Test-only transport adapter registered before production conditional configuration is evaluated. */
final class DurableRestartTestConfiguration {

  private static final String PUBLISHER_ENABLED_PROPERTY = "test.outbox.publisher.enabled";
  private static final String PUBLICATIONS_PATH_PROPERTY = "test.outbox.publications.path";

  private DurableRestartTestConfiguration() {}

  /** Registers the observer early enough for the production outbox dispatcher conditions to see it. */
  static void registerPublisher(ConfigurableApplicationContext context) {
    if (!(context instanceof GenericApplicationContext genericContext)) {
      throw new IllegalStateException(
          "Durable restart launcher requires a GenericApplicationContext but received "
              + context.getClass().getName());
    }
    Environment environment = genericContext.getEnvironment();
    if (!environment.getProperty(PUBLISHER_ENABLED_PROPERTY, Boolean.class, false)) {
      return;
    }
    Path output = Path.of(environment.getRequiredProperty(PUBLICATIONS_PATH_PROPERTY));
    genericContext.registerBean(
        "durableRestartOutboxPublisher",
        WorkflowOutboxPublisher.class,
        () -> message -> append(output, message));
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
    return value
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\r", "\\r")
        .replace("\n", "\\n");
  }
}
