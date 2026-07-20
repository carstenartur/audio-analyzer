package org.hammer.audio.infrastructure.workflow.search;

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
import org.hammer.audio.infrastructure.workflow.store.HibernateJGitVersionedWorkflowStore;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryResult;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.RefUpdateResult;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.junit.jupiter.api.Test;

class WorkflowSemanticHistoryIndexTest {

  private static final String REPOSITORY_NAME = "semantic-history-test";
  private static final String WORKFLOW_ID = "workflow.insect-observer";
  private static final String DELIMITER_RICH_VALUE = "line one\nline two:[]";
  private static final Instant BASE_TIME = Instant.parse("2026-07-20T00:00:00Z");

  @Test
  void projectsExactSemanticsPerBranchAndReconcilesAfterRefChanges() {
    List<Class<?>> entities = new ArrayList<>(SearchEntities.annotatedClasses());
    entities.addAll(WorkflowSemanticPersistenceEntities.annotatedClasses());

    try (HibernateSessionFactoryProvider provider =
            new HibernateSessionFactoryProvider(h2Properties(), entities);
        HibernateJGitVersionedWorkflowStore store =
            new HibernateJGitVersionedWorkflowStore(
                provider.getSessionFactory(), REPOSITORY_NAME)) {
      WorkflowSnapshot baseline =
          snapshot(
              "Insect observer baseline",
              node("node.source", "source", "Microphone source", Map.of()),
              Map.of("mode", "observe"));
      CommitId baselineCommit = store.commit("main", baseline, metadata("Baseline", 1));
      assertEquals(RefUpdateResult.SUCCESS, store.updateRef("experiment", null, baselineCommit));

      WorkflowSnapshot mainClassifier =
          snapshot(
              "Wingbeat classifier workflow",
              node(
                  "node.classifier",
                  "classifier",
                  "Wingbeat classifier",
                  Map.of("threshold", "high")),
              Map.of(
                  "empty", "",
                  "mode", "safe",
                  "notes", DELIMITER_RICH_VALUE));
      CommitId mainCommit =
          store.commit("main", mainClassifier, metadata("Add classifier", 2));

      WorkflowSnapshot experimentGain =
          snapshot(
              "Experimental gain workflow",
              node(
                  "node.gain",
                  "gain",
                  "Experimental gain",
                  Map.of("threshold", "high")),
              Map.of("mode", "research"));
      CommitId experimentCommit =
          store.commit("experiment", experimentGain, metadata("Tune experiment", 3));

      assertEquals(
          List.of(mainCommit, baselineCommit),
          store.searchSemantic(query("main", null, null, null, null, null, null)).stream()
              .map(WorkflowSemanticHistoryResult::commitId)
              .toList(),
          "incremental checkpoint indexing must keep newest-first branch positions");

      List<WorkflowSemanticHistoryResult> mainHits =
          store.searchSemantic(
              query("main", WORKFLOW_ID, null, "classifier", "wingbeat", "mode", "safe"));
      assertEquals(
          List.of(mainCommit),
          mainHits.stream().map(WorkflowSemanticHistoryResult::commitId).toList());
      assertEquals(mainClassifier, store.loadAtCommit(mainHits.getFirst().commitId()));
      assertEquals(List.of("node.classifier"), mainHits.getFirst().nodeIds());
      assertTrue(
          mainHits
              .getFirst()
              .propertyKeys()
              .containsAll(List.of("empty", "mode", "notes", "threshold")));
      assertTrue(mainHits.getFirst().propertyValues().contains(""));
      assertTrue(mainHits.getFirst().propertyValues().contains(DELIMITER_RICH_VALUE));
      assertEquals(
          List.of(mainCommit),
          store
              .searchSemantic(
                  query(
                      "main",
                      WORKFLOW_ID,
                      null,
                      null,
                      null,
                      "notes",
                      DELIMITER_RICH_VALUE))
              .stream()
              .map(WorkflowSemanticHistoryResult::commitId)
              .toList());

      List<WorkflowSemanticHistoryResult> experimentHits =
          store.searchSemantic(
              query("experiment", WORKFLOW_ID, "node.gain", "gain", null, null, null));
      assertEquals(
          List.of(experimentCommit),
          experimentHits.stream().map(WorkflowSemanticHistoryResult::commitId).toList());

      assertEquals(
          List.of(),
          store.searchSemantic(
              query("experiment", WORKFLOW_ID, null, null, null, "mode", "high")),
          "key and value must belong to the same metadata entry");
      assertEquals(
          List.of(),
          store.searchSemantic(
              query("main", WORKFLOW_ID, "node.gain", null, null, null, null)),
          "branch reachability must be enforced by the semantic projection");

      assertEquals(0, store.rebuild("main", -1));
      assertEquals(
          List.of(mainCommit, baselineCommit),
          store.searchSemantic(query("main", null, null, null, null, null, null)).stream()
              .map(WorkflowSemanticHistoryResult::commitId)
              .toList(),
          "a full rebuild must preserve the incremental projection result");

      assertEquals(
          RefUpdateResult.SUCCESS, store.updateRef("main", mainCommit, baselineCommit));
      assertEquals(
          List.of(),
          store.searchSemantic(
              query("main", WORKFLOW_ID, null, "classifier", null, null, null)),
          "moving a ref must remove commits that are no longer reachable on that branch");
      assertEquals(
          baselineCommit,
          store
              .searchSemantic(
                  query("main", WORKFLOW_ID, "node.source", null, null, null, null))
              .getFirst()
              .commitId());
    }
  }

  private static WorkflowSemanticHistoryQuery query(
      String branch,
      String workflowId,
      String nodeId,
      String nodeType,
      String label,
      String propertyKey,
      String propertyValue) {
    return new WorkflowSemanticHistoryQuery(
        branch, workflowId, nodeId, nodeType, label, propertyKey, propertyValue, 20);
  }

  private static WorkflowSnapshot snapshot(
      String name, Node node, Map<String, String> workflowMetadata) {
    Workflow workflow =
        new Workflow(
            WORKFLOW_ID,
            name,
            List.of(node),
            List.of(),
            new Metadata(workflowMetadata));
    return new WorkflowSnapshot(
        WORKFLOW_ID, new WorkflowDslSerializer().serialize(workflow));
  }

  private static Node node(
      String id, String type, String label, Map<String, String> metadata) {
    return new Node(id, type, label, List.of(), List.of(), new Metadata(metadata));
  }

  private static CommitMetadata metadata(String message, long seconds) {
    return new CommitMetadata("semantic-test", message, BASE_TIME.plusSeconds(seconds));
  }

  private static Properties h2Properties() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    return properties;
  }
}
