package org.hammer.audio.infrastructure.workflow.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.SearchEntities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.hammer.audio.infrastructure.workflow.search.WorkflowSemanticPersistenceEntities;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.history.WorkflowCombinedHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowCombinedHistoryResult;
import org.hammer.audio.workflow.history.WorkflowHistoryTextQuery;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryFilter;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.junit.jupiter.api.Test;

class HibernateWorkflowCombinedHistorySearchTest {

  private static final String REPOSITORY_NAME = "combined-history-test";
  private static final String WORKFLOW_ID = "workflow.combined";
  private static final Instant BASE_TIME = Instant.parse("2026-07-20T00:00:00Z");

  @Test
  void appliesSemanticCandidatesBeforeGenericNewestFirstLimit() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    List<Class<?>> entities = new ArrayList<>(SearchEntities.annotatedClasses());
    entities.addAll(WorkflowSemanticPersistenceEntities.annotatedClasses());

    try (HibernateSessionFactoryProvider provider =
            new HibernateSessionFactoryProvider(properties, entities);
        HibernateJGitVersionedWorkflowStore store =
            new HibernateJGitVersionedWorkflowStore(
                provider.getSessionFactory(), REPOSITORY_NAME)) {
      store.commit("main", snapshot("source", "observe"), metadata("Baseline", 1));
      CommitId olderCandidate =
          store.commit(
              "main", snapshot("classifier", "safe"), metadata("Wingbeat candidate old", 2));
      CommitId expected =
          store.commit(
              "main", snapshot("classifier", "safe"), metadata("Wingbeat candidate final", 3));
      store.commit(
          "main",
          snapshot("source", "unsafe"),
          metadata("Wingbeat newer nonsemantic result", 4));
      store.commit(
          "experiment",
          snapshot("classifier", "safe"),
          metadata("Wingbeat other branch", 5));

      WorkflowCombinedHistoryQuery query =
          new WorkflowCombinedHistoryQuery(
              new WorkflowHistoryTextQuery(
                  "",
                  "combined-test@audio-analyzer.invalid",
                  "workflow",
                  null,
                  null,
                  1),
              new WorkflowSemanticHistoryFilter(
                  "main", WORKFLOW_ID, null, "classifier", null, "mode", "safe"));

      List<WorkflowCombinedHistoryResult> hits = store.searchCombined(query);

      assertEquals(1, hits.size());
      assertEquals(expected, hits.getFirst().commit().commitId());
      assertEquals(expected, hits.getFirst().semantics().commitId());
      assertEquals(List.of("classifier"), hits.getFirst().semantics().nodeTypes());
      assertTrue(hits.getFirst().semantics().propertyValues().contains("safe"));

      List<CommitId> fullTextIds =
          store
              .searchCombined(
                  new WorkflowCombinedHistoryQuery(
                      new WorkflowHistoryTextQuery(
                          "wingbeat",
                          "combined-test@audio-analyzer.invalid",
                          "workflow",
                          null,
                          null,
                          10),
                      query.semanticFilter()))
              .stream()
              .map(hit -> hit.commit().commitId())
              .toList();
      assertEquals(2, fullTextIds.size());
      assertTrue(fullTextIds.containsAll(List.of(expected, olderCandidate)));

      assertEquals(
          List.of(),
          store.searchCombined(
              new WorkflowCombinedHistoryQuery(
                  query.genericQuery(),
                  new WorkflowSemanticHistoryFilter(
                      "main", WORKFLOW_ID, null, "missing-type", null, null, null))));
    }
  }

  private static WorkflowSnapshot snapshot(String nodeType, String mode) {
    Node node =
        new Node(
            "node.primary",
            nodeType,
            "Wingbeat " + nodeType,
            List.of(),
            List.of(),
            new Metadata(Map.of("mode", mode)));
    Workflow workflow =
        new Workflow(WORKFLOW_ID, "Combined history workflow", List.of(node), List.of());
    return new WorkflowSnapshot(
        workflow.id(), new WorkflowDslSerializer().serialize(workflow));
  }

  private static CommitMetadata metadata(String message, long seconds) {
    return new CommitMetadata("combined-test", message, BASE_TIME.plusSeconds(seconds));
  }
}
