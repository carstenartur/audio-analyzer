package org.hammer.audio.infrastructure.workflow.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class HibernateJGitVersionedWorkflowStoreTest {

  @Test
  void checkpointHistoryAndApplicationEntitySurviveCompleteRestart() throws IOException {
    Path databaseDirectory = Files.createTempDirectory("audio-analyzer-hibernate-jgit-");
    Properties properties = h2FileProperties(databaseDirectory.resolve("workflow-store"));
    WorkflowSnapshot first = new WorkflowSnapshot("workflow.alpha", "dsl-first");
    WorkflowSnapshot second = new WorkflowSnapshot("workflow.alpha", "dsl-second");
    CommitId firstCommit;
    CommitId secondCommit;

    try {
      try (HibernateSessionFactoryProvider provider = provider(properties)) {
        persistProbe(provider.getSessionFactory());
        try (HibernateJGitVersionedWorkflowStore store =
            new HibernateJGitVersionedWorkflowStore(
                provider.getSessionFactory(), "audio-analyzer-workflows")) {
          firstCommit = store.commit("main", first, metadata("first", 1));
          secondCommit = store.commit("main", second, metadata("second", 2));
        }
      }

      try (HibernateSessionFactoryProvider provider = provider(properties)) {
        WorkflowPersistenceProbeEntity probe =
            loadProbe(provider.getSessionFactory(), "session-probe");
        assertNotNull(probe);
        assertEquals("ACTIVE", probe.getStatus());

        try (HibernateJGitVersionedWorkflowStore store =
            new HibernateJGitVersionedWorkflowStore(
                provider.getSessionFactory(), "audio-analyzer-workflows")) {
          assertEquals(second, store.loadHead("main"));
          assertEquals(first, store.loadAtCommit(firstCommit));
          List<CommitInfo> history = store.history("main", 10);
          assertEquals(List.of(secondCommit, firstCommit), commitIds(history));
        }
      }
    } finally {
      deleteRecursively(databaseDirectory);
    }
  }

  private static HibernateSessionFactoryProvider provider(Properties properties) {
    return SearchableWorkflowTestSessionFactory.provider(
        properties, WorkflowPersistenceProbeEntity.class);
  }

  private static void persistProbe(SessionFactory sessionFactory) {
    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      try {
        session.persist(new WorkflowPersistenceProbeEntity("session-probe", "ACTIVE"));
        transaction.commit();
      } catch (RuntimeException exception) {
        if (transaction.isActive()) {
          transaction.rollback();
        }
        throw exception;
      }
    }
  }

  private static WorkflowPersistenceProbeEntity loadProbe(
      SessionFactory sessionFactory, String id) {
    try (Session session = sessionFactory.openSession()) {
      return session.find(WorkflowPersistenceProbeEntity.class, id);
    }
  }

  private static List<CommitId> commitIds(List<CommitInfo> history) {
    return history.stream().map(CommitInfo::commitId).toList();
  }

  private static CommitMetadata metadata(String message, long secondOffset) {
    return new CommitMetadata(
        "tester", message, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(secondOffset));
  }

  private static Properties h2FileProperties(Path databasePath) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:file:" + databasePath.toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "update");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }

  private static void deleteRecursively(Path directory) throws IOException {
    if (Files.notExists(directory)) {
      return;
    }
    try (var paths = Files.walk(directory)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(HibernateJGitVersionedWorkflowStoreTest::delete);
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
