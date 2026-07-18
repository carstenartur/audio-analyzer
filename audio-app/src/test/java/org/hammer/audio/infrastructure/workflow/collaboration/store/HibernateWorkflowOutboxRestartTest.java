package org.hammer.audio.infrastructure.workflow.collaboration.store;

import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.BASE_TIME;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.appendPendingEvent;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.fileProperties;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.provider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxBackoffPolicy;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxDispatcher;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxDispatcherSettings;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxMessage;
import org.junit.jupiter.api.Test;

class HibernateWorkflowOutboxRestartTest {

  @Test
  void committedPendingEventIsPublishedAfterCompleteRestart() throws IOException {
    Path databaseDirectory = Files.createTempDirectory("audio-analyzer-outbox-restart-");
    Properties properties = fileProperties(databaseDirectory.resolve("collaboration"));
    try {
      try (HibernateSessionFactoryProvider provider = provider(properties)) {
        HibernateWorkflowSessionStateStore sessionStore =
            new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
        appendPendingEvent(
            sessionStore, "session.restart-outbox", "event.restart", BASE_TIME.plusSeconds(1));
      }

      List<WorkflowOutboxMessage> published = new ArrayList<>();
      try (HibernateSessionFactoryProvider provider = provider(properties)) {
        HibernateWorkflowOutboxStore outboxStore =
            new HibernateWorkflowOutboxStore(provider.getSessionFactory());
        WorkflowOutboxDispatcher dispatcher =
            new WorkflowOutboxDispatcher(
                outboxStore,
                published::add,
                Clock.fixed(BASE_TIME.plusSeconds(10), ZoneOffset.UTC),
                new WorkflowOutboxDispatcherSettings(
                    "dispatcher.restart",
                    10,
                    Duration.ofSeconds(30),
                    new WorkflowOutboxBackoffPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1))));

        assertEquals(List.of("event.restart"), dispatcher.dispatchBatch().publishedEventIds());
        assertEquals(1, published.size());
        assertEquals("event.restart", published.getFirst().eventId());
        assertFalse(outboxStore.find("event.restart").orElseThrow().pending());
        assertTrue(
            outboxStore
                .claimDue(
                    "dispatcher.after", BASE_TIME.plusSeconds(100), BASE_TIME.plusSeconds(130), 10)
                .isEmpty());
      }
    } finally {
      deleteRecursively(databaseDirectory);
    }
  }

  private static void deleteRecursively(Path directory) throws IOException {
    if (Files.notExists(directory)) {
      return;
    }
    try (var paths = Files.walk(directory)) {
      paths.sorted(Comparator.reverseOrder()).forEach(HibernateWorkflowOutboxRestartTest::delete);
    }
  }

  private static void delete(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to delete test database path " + path, exception);
    }
  }
}
