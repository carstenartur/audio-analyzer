package org.hammer.audio.infrastructure.workflow.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hammer.audio.workflow.DataTypes;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.PortDirection;
import org.hammer.audio.workflow.PortMultiplicity;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.RefUpdateResult;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.junit.jupiter.api.Test;

class JGitStorageHibernateWorkflowStoreAdapterTest {

  private static final WorkflowDslSerializer SERIALIZER = new WorkflowDslSerializer();
  private static final WorkflowDslParser PARSER = new WorkflowDslParser();

  @Test
  void commitAndLoadHeadRoundTrip() throws IOException {
    try (StoreHandle handle = createStore()) {
      WorkflowSnapshot snapshot = snapshot("workflow.alpha", "workflow workflow.alpha { node n1 }");
      CommitId commitId = handle.store().commit("main", snapshot, metadata("first", 1));

      assertEquals(snapshot, handle.store().loadAtCommit(commitId));
      assertEquals(snapshot, handle.store().loadHead("main"));
    }
  }

  @Test
  void loadAtCommitReturnsHistoricalSnapshot() throws IOException {
    try (StoreHandle handle = createStore()) {
      WorkflowSnapshot first = snapshot("workflow.alpha", "workflow workflow.alpha { node n1 }");
      WorkflowSnapshot second = snapshot("workflow.alpha", "workflow workflow.alpha { node n2 }");

      CommitId firstCommit = handle.store().commit("main", first, metadata("first", 1));
      handle.store().commit("main", second, metadata("second", 2));

      assertEquals(first, handle.store().loadAtCommit(firstCommit));
    }
  }

  @Test
  void historyIsReverseChronologicalAndLimited() throws IOException {
    try (StoreHandle handle = createStore()) {
      CommitId first =
          handle.store().commit("main", snapshot("workflow.alpha", "dsl-1"), metadata("first", 1));
      CommitId second =
          handle.store().commit("main", snapshot("workflow.alpha", "dsl-2"), metadata("second", 2));
      CommitId third =
          handle.store().commit("main", snapshot("workflow.alpha", "dsl-3"), metadata("third", 3));

      List<CommitInfo> history = handle.store().history("main", 2);
      assertEquals(2, history.size());
      assertEquals(third, history.get(0).commitId());
      assertEquals(second, history.get(1).commitId());
      assertEquals(
          List.of(third, second, first),
          handle.store().history("main", 10).stream().map(CommitInfo::commitId).toList());
    }
  }

  @Test
  void updateRefContractCases() throws IOException {
    try (StoreHandle handle = createStore()) {
      CommitId base =
          handle
              .store()
              .commit("main", snapshot("workflow.alpha", "dsl-base"), metadata("base", 1));
      CommitId candidate =
          handle
              .store()
              .commit(
                  "candidate",
                  snapshot("workflow.alpha", "dsl-candidate"),
                  metadata("candidate", 2));

      assertEquals(RefUpdateResult.SUCCESS, handle.store().updateRef("main", base, candidate));
      assertEquals(snapshot("workflow.alpha", "dsl-candidate"), handle.store().loadHead("main"));

      handle
          .store()
          .commit("main", snapshot("workflow.alpha", "dsl-main-new"), metadata("main-new", 3));
      assertEquals(RefUpdateResult.STALE, handle.store().updateRef("main", base, candidate));

      CommitId featureHead =
          handle
              .store()
              .commit("seed", snapshot("workflow.alpha", "dsl-feature"), metadata("seed", 4));
      assertEquals(
          RefUpdateResult.SUCCESS, handle.store().updateRef("feature/new", null, featureHead));
      assertEquals(
          snapshot("workflow.alpha", "dsl-feature"), handle.store().loadHead("feature/new"));
      assertEquals(
          RefUpdateResult.SUCCESS,
          handle.store().updateRef("feature/new", featureHead, featureHead));
    }
  }

  @Test
  void updateRefUnknownCommitThrows() throws IOException {
    try (StoreHandle handle = createStore()) {
      handle.store().commit("main", snapshot("workflow.alpha", "dsl"), metadata("base", 1));
      assertThrows(
          NoSuchElementException.class,
          () -> handle.store().updateRef("main", new CommitId("unknown"), new CommitId("unknown")));
    }
  }

  @Test
  void concurrentRefUpdateDetectsConflict() throws Exception {
    Path repositoryPath =
        Files.createTempDirectory("audio-analyzer-jgit-storage-hibernate-conflict-");
    CommitId base;
    CommitId candidateA;
    CommitId candidateB;
    try (JGitStorageHibernateWorkflowStoreAdapter seedStore =
        new JGitStorageHibernateWorkflowStoreAdapter(repositoryPath)) {
      base = seedStore.commit("main", snapshot("workflow.alpha", "dsl-base"), metadata("base", 1));
      candidateA =
          seedStore.commit("candidateA", snapshot("workflow.alpha", "dsl-A"), metadata("A", 2));
      candidateB =
          seedStore.commit("candidateB", snapshot("workflow.alpha", "dsl-B"), metadata("B", 3));
    }

    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<RefUpdateResult> left =
          pool.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                try (JGitStorageHibernateWorkflowStoreAdapter store =
                    new JGitStorageHibernateWorkflowStoreAdapter(repositoryPath)) {
                  return store.updateRef("main", base, candidateA);
                }
              });
      Future<RefUpdateResult> right =
          pool.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                try (JGitStorageHibernateWorkflowStoreAdapter store =
                    new JGitStorageHibernateWorkflowStoreAdapter(repositoryPath)) {
                  return store.updateRef("main", base, candidateB);
                }
              });

      start.countDown();
      List<RefUpdateResult> results = List.of(get(left), get(right));
      long successCount =
          results.stream().filter(result -> result == RefUpdateResult.SUCCESS).count();
      long staleCount = results.stream().filter(result -> result == RefUpdateResult.STALE).count();
      assertEquals(1, successCount);
      assertEquals(1, staleCount);
    }
  }

  @Test
  void realDslRoundTripThroughStoreCommitLoadAndHistory() throws IOException {
    Workflow workflow = buildMinimalWorkflow();
    String originalDsl = SERIALIZER.serialize(workflow);
    Workflow parsedBeforeCommit = PARSER.parse(originalDsl);

    try (StoreHandle handle = createStore()) {
      CommitId commitId =
          handle.store().commit("main", snapshot(workflow.id(), originalDsl), metadata("dsl", 1));

      WorkflowSnapshot loadedHead = handle.store().loadHead("main");
      WorkflowSnapshot loadedAtCommit = handle.store().loadAtCommit(commitId);
      List<CommitInfo> history = handle.store().history("main", 10);

      assertEquals(originalDsl, loadedHead.dslText());
      assertEquals(originalDsl, loadedAtCommit.dslText());
      assertEquals(parsedBeforeCommit, PARSER.parse(loadedHead.dslText()));
      assertEquals(1, history.size());
      assertEquals(commitId, history.get(0).commitId());
      assertNotNull(history.get(0).metadata());
      assertEquals(workflow.id(), history.get(0).workflowId());
      assertTrue(history.get(0).metadata().message().contains("dsl"));
    }
  }

  private static RefUpdateResult get(Future<RefUpdateResult> future)
      throws InterruptedException, ExecutionException {
    return future.get();
  }

  private static StoreHandle createStore() throws IOException {
    Path repositoryPath = Files.createTempDirectory("audio-analyzer-jgit-storage-hibernate-");
    return new StoreHandle(
        new JGitStorageHibernateWorkflowStoreAdapter(repositoryPath), repositoryPath);
  }

  private static WorkflowSnapshot snapshot(String workflowId, String dsl) {
    return new WorkflowSnapshot(workflowId, dsl);
  }

  private static CommitMetadata metadata(String message, long secondOffset) {
    return new CommitMetadata(
        "tester", message, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(secondOffset));
  }

  private static Workflow buildMinimalWorkflow() {
    Node gain =
        new Node(
            "node.gain",
            "gain",
            "Gain",
            List.of(
                new Port(
                    "audio-in",
                    "Audio In",
                    PortDirection.INPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)),
            List.of(
                new Port(
                    "audio-out",
                    "Audio Out",
                    PortDirection.OUTPUT,
                    DataTypes.AUDIO_BLOCK,
                    false,
                    PortMultiplicity.SINGLE)));
    Node input =
        new Node(
            "node.input",
            "audio-source",
            "Audio Source",
            List.of(),
            List.of(
                new Port(
                    "audio-out",
                    "Audio Out",
                    PortDirection.OUTPUT,
                    DataTypes.AUDIO_BLOCK,
                    false,
                    PortMultiplicity.SINGLE)));
    Node output =
        new Node(
            "node.output",
            "audio-sink",
            "Audio Sink",
            List.of(
                new Port(
                    "audio-in",
                    "Audio In",
                    PortDirection.INPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)),
            List.of());
    return new Workflow(
        "workflow.minimal",
        "Input -> Gain -> Output",
        List.of(gain, input, output),
        List.of(
            new Edge("edge.gain-output", "node.gain", "audio-out", "node.output", "audio-in"),
            new Edge("edge.input-gain", "node.input", "audio-out", "node.gain", "audio-in")));
  }

  private record StoreHandle(JGitStorageHibernateWorkflowStoreAdapter store, Path repositoryPath)
      implements AutoCloseable {
    @Override
    public void close() throws IOException {
      store.close();
      if (Files.exists(repositoryPath)) {
        try (var paths = Files.walk(repositoryPath)) {
          paths.sorted(Comparator.reverseOrder()).forEach(StoreHandle::deletePath);
        }
      }
    }

    private static void deletePath(Path path) {
      try {
        Files.deleteIfExists(path);
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to delete temporary store path: " + path, ex);
      }
    }
  }
}
