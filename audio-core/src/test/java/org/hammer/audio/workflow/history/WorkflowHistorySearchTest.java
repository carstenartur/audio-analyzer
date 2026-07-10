package org.hammer.audio.workflow.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.InMemoryVersionedWorkflowStore;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link WorkflowHistorySearch}. */
class WorkflowHistorySearchTest {

  private static final WorkflowDslSerializer SERIALIZER = new WorkflowDslSerializer();
  private static final Instant T1 = Instant.parse("2026-01-01T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-01-02T10:00:00Z");
  private static final Instant T3 = Instant.parse("2026-01-03T10:00:00Z");

  private VersionedWorkflowStore store;
  private WorkflowHistorySearch search;

  @BeforeEach
  void setUp() {
    store = new InMemoryVersionedWorkflowStore();
    search = new WorkflowHistorySearch();
  }

  // -------------------------------------------------------------------------
  // findByNodeType
  // -------------------------------------------------------------------------

  @Test
  void findByNodeType_returnsCommitsContainingMatchingNodeType() {
    // commit 1: workflow with gain node
    Workflow w1 = workflow("workflow.w1", "W1", List.of(node("node.gain1", "gain", "Gain 1")));
    commitWorkflow(store, "main", w1, "alice", "add gain", T1);

    // commit 2: workflow with audio-input only
    Workflow w2 =
        workflow("workflow.w2", "W2", List.of(node("node.input1", "audio-input", "Input")));
    commitWorkflow(store, "main", w2, "bob", "add input", T2);

    List<WorkflowHistorySearch.CommitMatch> matches =
        search.findByNodeType(store, "main", 10, "gain");

    assertEquals(1, matches.size(), "only one commit should match node type 'gain'");
    assertEquals("node.gain1", matches.get(0).matchedObjectIds().get(0));
  }

  @Test
  void findByNodeType_returnsEmptyListWhenNoMatch() {
    Workflow w = workflow("workflow.w1", "W1", List.of(node("node.a", "audio-input", "Input")));
    commitWorkflow(store, "main", w, "alice", "first commit", T1);

    List<WorkflowHistorySearch.CommitMatch> matches =
        search.findByNodeType(store, "main", 10, "unknown-type");

    assertTrue(matches.isEmpty());
  }

  @Test
  void findByNodeType_historyLimitIsRespected() {
    for (int i = 1; i <= 5; i++) {
      Workflow w =
          workflow("workflow.w" + i, "W" + i, List.of(node("node.gain" + i, "gain", "Gain " + i)));
      commitWorkflow(store, "main", w, "alice", "commit " + i, T1.plusSeconds(i));
    }

    List<WorkflowHistorySearch.CommitMatch> matches =
        search.findByNodeType(store, "main", 3, "gain");

    assertEquals(3, matches.size(), "historyLimit=3 should cap results");
  }

  // -------------------------------------------------------------------------
  // findByParameter
  // -------------------------------------------------------------------------

  @Test
  void findByParameter_returnsCommitsWhereNodeHasMatchingPropertyValue() {
    Node gainNode =
        new Node(
            "node.b", "gain", "Gain", List.of(), List.of(), new Metadata(Map.of("gain", "2.5")));
    Workflow w = workflow("workflow.w1", "W1", List.of(gainNode));
    commitWorkflow(store, "main", w, "alice", "gain commit", T1);

    List<WorkflowHistorySearch.CommitMatch> matches =
        search.findByParameter(store, "main", 10, "gain", "2.5");

    assertEquals(1, matches.size());
    assertEquals("node.b", matches.get(0).matchedObjectIds().get(0));
  }

  @Test
  void findByParameter_doesNotMatchDifferentValue() {
    Node gainNode =
        new Node(
            "node.b", "gain", "Gain", List.of(), List.of(), new Metadata(Map.of("gain", "1.0")));
    Workflow w = workflow("workflow.w1", "W1", List.of(gainNode));
    commitWorkflow(store, "main", w, "alice", "gain commit", T1);

    List<WorkflowHistorySearch.CommitMatch> matches =
        search.findByParameter(store, "main", 10, "gain", "9.9");

    assertTrue(matches.isEmpty());
  }

  // -------------------------------------------------------------------------
  // findByAuthor
  // -------------------------------------------------------------------------

  @Test
  void findByAuthor_returnsOnlyCommitsByMatchingAuthor() {
    Workflow w1 = workflow("workflow.w1", "W1", List.of(node("node.a", "gain", "Gain")));
    Workflow w2 = workflow("workflow.w2", "W2", List.of(node("node.b", "gain", "Gain")));
    Workflow w3 = workflow("workflow.w3", "W3", List.of(node("node.c", "gain", "Gain")));
    commitWorkflow(store, "main", w1, "alice", "first", T1);
    commitWorkflow(store, "main", w2, "bob", "second", T2);
    commitWorkflow(store, "main", w3, "alice", "third", T3);

    List<WorkflowHistorySearch.CommitMatch> matches =
        search.findByAuthor(store, "main", 10, "alice");

    assertEquals(2, matches.size(), "exactly two commits by alice should be returned");
    assertTrue(matches.stream().allMatch(m -> "alice".equals(m.commitInfo().metadata().author())));
  }

  @Test
  void findByAuthor_returnsEmptyListWhenNoAuthorMatch() {
    Workflow w = workflow("workflow.w1", "W1", List.of(node("node.a", "gain", "Gain")));
    commitWorkflow(store, "main", w, "bob", "commit", T1);

    List<WorkflowHistorySearch.CommitMatch> matches =
        search.findByAuthor(store, "main", 10, "nobody");

    assertTrue(matches.isEmpty());
  }

  // -------------------------------------------------------------------------
  // Input validation
  // -------------------------------------------------------------------------

  @Test
  void blankRefNameThrows_findByNodeType() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> search.findByNodeType(store, "", 10, "gain"));
  }

  @Test
  void negativeHistoryLimitThrows_findByParameter() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> search.findByParameter(store, "main", -1, "gain", "1.0"));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static Workflow workflow(String id, String name, List<Node> nodes) {
    return new Workflow(id, name, nodes, List.of());
  }

  private static Node node(String id, String type, String label) {
    return new Node(id, type, label, List.of(), List.of());
  }

  private static void commitWorkflow(
      VersionedWorkflowStore store,
      String branch,
      Workflow workflow,
      String author,
      String message,
      Instant timestamp) {
    String dsl = SERIALIZER.serialize(workflow);
    WorkflowSnapshot snapshot = new WorkflowSnapshot(workflow.id(), dsl);
    CommitMetadata meta = new CommitMetadata(author, message, timestamp);
    store.commit(branch, snapshot, meta);
  }
}
