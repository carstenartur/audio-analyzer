package org.hammer.audio.infrastructure.workflow.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.SearchEntities;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.hammer.audio.workflow.history.WorkflowHistoryTextQuery;
import org.hammer.audio.workflow.history.WorkflowHistoryTextResult;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.junit.jupiter.api.Test;

class HibernateWorkflowHistoryIndexTest {

  private static final Instant BASE_TIME = Instant.parse("2026-07-19T00:00:00Z");

  @Test
  void compoundProjectionQueryReturnsExactLoadableCommitAndRebuildIsIdempotent() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");

    try (HibernateSessionFactoryProvider provider =
            new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
        HibernateJGitVersionedWorkflowStore store =
            new HibernateJGitVersionedWorkflowStore(provider.getSessionFactory(), "search-test")) {
      WorkflowSnapshot baseline = new WorkflowSnapshot("workflow.search", "node baseline");
      WorkflowSnapshot matching =
          new WorkflowSnapshot("workflow.search", "node classifier label wingbeatneedle");
      store.commit("main", baseline, metadata("Baseline checkpoint", 1));
      CommitId matchingCommit = store.commit("main", matching, metadata("Add wingbeat needle", 2));
      Instant matchingTime = BASE_TIME.plusSeconds(2);

      var hits =
          store.search(
              new WorkflowHistoryTextQuery(
                  "wingbeatneedle",
                  "search-test@audio-analyzer.invalid",
                  "workflow.dsl",
                  matchingTime,
                  matchingTime,
                  10));
      assertEquals(1, hits.size());
      WorkflowHistoryTextResult hit = hits.getFirst();
      assertEquals(matchingCommit, hit.commitId());
      assertEquals(matching, store.loadAtCommit(hit.commitId()));
      assertTrue(hit.changedPaths().contains("workflow.dsl"));
      assertEquals(
          0,
          store
              .search(
                  new WorkflowHistoryTextQuery(
                      "wingbeatneedle", "nobody@example.org", "workflow.dsl", null, null, 10))
              .size());
      assertEquals(0, store.rebuild("main", -1));
    }
  }

  private static CommitMetadata metadata(String message, long seconds) {
    return new CommitMetadata("search-test", message, BASE_TIME.plusSeconds(seconds));
  }
}
