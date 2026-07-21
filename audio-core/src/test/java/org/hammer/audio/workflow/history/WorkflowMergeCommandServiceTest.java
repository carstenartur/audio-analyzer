package org.hammer.audio.workflow.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Conflict;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.ResolutionChoice;
import org.hammer.audio.workflow.merge.WorkflowMergeResolution;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.InMemoryVersionedWorkflowStore;
import org.hammer.audio.workflow.store.RefUpdateResult;
import org.hammer.audio.workflow.store.StaleWorkflowHeadException;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.junit.jupiter.api.Test;

class WorkflowMergeCommandServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-21T08:00:00Z");
  private static final String TARGET = "main";
  private static final String REMOTE = "feature";

  @Test
  void previewsAndCommitsIndependentStoredBranchChangesWithAuditFooter() {
    MergeRepository repository = repository(independentLocal(), independentRemote());
    WorkflowMergeCommandService service = service(repository.store());
    PreviewWorkflowMergeCommand previewCommand = repository.command();

    WorkflowMergePreview preview = service.preview(previewCommand);
    WorkflowMergeCommitResult committed =
        service.resolveAndCommit(
            new ResolveWorkflowMergeCommand(
                previewCommand,
                repository.localCommit(),
                List.of(),
                metadata("Merge independent changes")));

    assertTrue(preview.merge().readyToCommit());
    assertEquals("Local signal", node(committed.workflow(), "node.generator").label());
    assertEquals(
        "0.75", node(committed.workflow(), "node.gain").metadata().entries().get("gain.factor"));
    assertEquals(
        committed.workflow(), parse(repository.store().loadAtCommit(committed.mergedCommit())));
    assertTrue(committed.auditMessage().contains("[workflow-merge]"));
    assertTrue(committed.auditMessage().contains("base=" + repository.baseCommit().value()));
    assertTrue(committed.auditMessage().contains("remoteBranch=feature"));
    assertEquals(
        committed.auditMessage(),
        repository.store().history(TARGET, 1).getFirst().metadata().message());
  }

  @Test
  void commitsExplicitConflictDecisionAndOrdersResolutionAuditByConflictId() {
    Workflow local = replaceGeneratorLabel(baseWorkflow(), "Local signal");
    Workflow remote = replaceGeneratorLabel(baseWorkflow(), "Remote signal");
    MergeRepository repository = repository(local, remote);
    WorkflowMergeCommandService service = service(repository.store());
    WorkflowMergePreview preview = service.preview(repository.command());
    Conflict conflict = preview.merge().conflicts().getFirst();
    WorkflowMergeResolution resolution =
        new WorkflowMergeResolution(
            conflict.conflictId(), ResolutionChoice.CUSTOM, "Resolved signal");

    WorkflowMergeCommitResult committed =
        service.resolveAndCommit(
            new ResolveWorkflowMergeCommand(
                repository.command(),
                repository.localCommit(),
                List.of(resolution),
                metadata("Resolve signal conflict")));

    assertEquals("Resolved signal", node(committed.workflow(), "node.generator").label());
    assertTrue(committed.auditMessage().contains("resolution.0.conflict="));
    assertTrue(committed.auditMessage().contains("resolution.0.choice=CUSTOM"));
    assertTrue(committed.auditMessage().contains("resolution.0.custom=Resolved signal"));
  }

  @Test
  void rejectsUnresolvedConflictsBeforeWritingACommit() {
    MergeRepository repository =
        repository(
            replaceGeneratorLabel(baseWorkflow(), "Local signal"),
            replaceGeneratorLabel(baseWorkflow(), "Remote signal"));
    WorkflowMergeCommandService service = service(repository.store());
    int historySize = repository.store().history(TARGET, 100).size();

    WorkflowMergeRejectedException failure =
        assertThrows(
            WorkflowMergeRejectedException.class,
            () ->
                service.resolveAndCommit(
                    new ResolveWorkflowMergeCommand(
                        repository.command(),
                        repository.localCommit(),
                        List.of(),
                        metadata("Invalid unresolved merge"))));

    assertEquals(1, failure.unresolvedConflicts().size());
    assertEquals(historySize, repository.store().history(TARGET, 100).size());
  }

  @Test
  void protectsTheTargetBranchHeadBetweenPreviewAndCommit() {
    MergeRepository repository = repository(independentLocal(), independentRemote());
    WorkflowMergeCommandService service = service(repository.store());
    service.preview(repository.command());
    repository.store().commit(TARGET, snapshot(baseWorkflow()), metadata("Concurrent advance"));

    assertThrows(
        StaleWorkflowHeadException.class,
        () ->
            service.resolveAndCommit(
                new ResolveWorkflowMergeCommand(
                    repository.command(),
                    repository.localCommit(),
                    List.of(),
                    metadata("Stale merge"))));
  }

  @Test
  void requiresTheBaseToBeReachableFromBothBranches() {
    InMemoryVersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    CommitId base = store.commit(TARGET, snapshot(baseWorkflow()), metadata("Base"));
    CommitId local = store.commit(TARGET, snapshot(independentLocal()), metadata("Local"));
    CommitId unrelatedRemote =
        store.commit(REMOTE, snapshot(independentRemote()), metadata("Remote without base"));
    WorkflowMergeCommandService service = service(store);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.preview(
                new PreviewWorkflowMergeCommand(TARGET, REMOTE, base, local, unrelatedRemote)));
  }

  private static MergeRepository repository(Workflow local, Workflow remote) {
    InMemoryVersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    CommitId base = store.commit(TARGET, snapshot(baseWorkflow()), metadata("Base"));
    assertEquals(RefUpdateResult.SUCCESS, store.updateRef(REMOTE, null, base));
    CommitId localCommit = store.commit(TARGET, snapshot(local), metadata("Local"));
    CommitId remoteCommit = store.commit(REMOTE, snapshot(remote), metadata("Remote"));
    return new MergeRepository(store, base, localCommit, remoteCommit);
  }

  private static WorkflowMergeCommandService service(InMemoryVersionedWorkflowStore store) {
    return new WorkflowMergeCommandService(store, WorkflowHistoryAccessPolicy.allowAll());
  }

  private static Workflow baseWorkflow() {
    return new Workflow(
        "workflow.merge",
        "Merge workflow",
        List.of(
            ExperimentNodeCatalog.syntheticSignalGenerator("node.generator"),
            ExperimentNodeCatalog.gain("node.gain")),
        List.of(),
        new Metadata(Map.of("owner", "team")));
  }

  private static Workflow independentLocal() {
    return replaceGeneratorLabel(baseWorkflow(), "Local signal");
  }

  private static Workflow independentRemote() {
    Workflow base = baseWorkflow();
    Node gain = node(base, "node.gain");
    Node changedGain =
        new Node(
            gain.id(),
            gain.type(),
            gain.label(),
            gain.inputPorts(),
            gain.outputPorts(),
            new Metadata(Map.of("gain.factor", "0.75")));
    return replaceNode(base, changedGain);
  }

  private static Workflow replaceGeneratorLabel(Workflow workflow, String label) {
    Node generator = node(workflow, "node.generator");
    return replaceNode(
        workflow,
        new Node(
            generator.id(),
            generator.type(),
            label,
            generator.inputPorts(),
            generator.outputPorts(),
            generator.metadata()));
  }

  private static Workflow replaceNode(Workflow workflow, Node replacement) {
    List<Node> nodes =
        workflow.nodes().stream()
            .map(node -> node.id().equals(replacement.id()) ? replacement : node)
            .toList();
    return new Workflow(
        workflow.id(), workflow.name(), nodes, workflow.edges(), workflow.metadata());
  }

  private static Node node(Workflow workflow, String id) {
    return workflow.nodes().stream().filter(node -> node.id().equals(id)).findFirst().orElseThrow();
  }

  private static WorkflowSnapshot snapshot(Workflow workflow) {
    return new WorkflowSnapshot(workflow.id(), new WorkflowDslSerializer().serialize(workflow));
  }

  private static Workflow parse(WorkflowSnapshot snapshot) {
    return new org.hammer.audio.workflow.dsl.WorkflowDslParser().parse(snapshot.dslText());
  }

  private static CommitMetadata metadata(String message) {
    return new CommitMetadata("merge-test", message, NOW);
  }

  private record MergeRepository(
      InMemoryVersionedWorkflowStore store,
      CommitId baseCommit,
      CommitId localCommit,
      CommitId remoteCommit) {
    PreviewWorkflowMergeCommand command() {
      return new PreviewWorkflowMergeCommand(TARGET, REMOTE, baseCommit, localCommit, remoteCommit);
    }
  }
}
