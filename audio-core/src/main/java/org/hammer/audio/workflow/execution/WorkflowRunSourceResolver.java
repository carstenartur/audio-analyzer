package org.hammer.audio.workflow.execution;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry.SessionSnapshot;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionRevisionConflictException;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.execution.WorkflowRunException.Code;
import org.hammer.audio.workflow.execution.WorkflowRunModels.LiveSessionSource;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Source;
import org.hammer.audio.workflow.execution.WorkflowRunModels.StoredCommitSource;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;

/** Resolves one exact live revision or stored commit into immutable canonical workflow DSL. */
final class WorkflowRunSourceResolver {

  private final WorkflowSessionRegistry sessionRegistry;
  private final VersionedWorkflowStore versionStore;
  private final WorkflowDslParser dslParser = new WorkflowDslParser();
  private final WorkflowDslSerializer dslSerializer = new WorkflowDslSerializer();

  WorkflowRunSourceResolver(
      WorkflowSessionRegistry sessionRegistry, VersionedWorkflowStore versionStore) {
    this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
    this.versionStore = versionStore;
  }

  ResolvedWorkflow resolve(Source source) {
    Objects.requireNonNull(source, "source");
    if (source instanceof LiveSessionSource liveSource) {
      return resolveLive(liveSource);
    }
    if (versionStore == null) {
      throw new WorkflowRunException(
          Code.SOURCE_UNAVAILABLE,
          "Stored workflow execution requires a configured VersionedWorkflowStore",
          null,
          null,
          List.of());
    }
    return resolveStored((StoredCommitSource) source);
  }

  private ResolvedWorkflow resolveLive(LiveSessionSource source) {
    SessionSnapshot before = sessionRegistry.inspect(source.sessionId());
    requireExpectedRevision(source, before.revision());
    Workflow immutableWorkflow = sessionRegistry.workflow(source.sessionId());
    SessionSnapshot after = sessionRegistry.inspect(source.sessionId());
    requireExpectedRevision(source, after.revision());
    String dslText = dslSerializer.serialize(immutableWorkflow);
    Workflow parsedWorkflow = dslParser.parse(dslText);
    return new ResolvedWorkflow(
        parsedWorkflow.id(), parsedWorkflow, dslText, after.revision(), null);
  }

  private ResolvedWorkflow resolveStored(StoredCommitSource source) {
    try {
      WorkflowSnapshot storedSnapshot = versionStore.loadAtCommit(source.commitId());
      Workflow parsedWorkflow = dslParser.parse(storedSnapshot.dslText());
      return new ResolvedWorkflow(
          storedSnapshot.workflowId(),
          parsedWorkflow,
          storedSnapshot.dslText(),
          null,
          source.commitId());
    } catch (NoSuchElementException exception) {
      throw new WorkflowRunException(
          Code.SOURCE_UNAVAILABLE,
          "Unknown workflow commit: " + source.commitId().value(),
          null,
          null,
          List.of(),
          exception);
    }
  }

  private static void requireExpectedRevision(LiveSessionSource source, long actualRevision) {
    if (source.expectedRevision() != actualRevision) {
      throw new WorkflowSessionRevisionConflictException(
          source.sessionId(), source.expectedRevision(), actualRevision);
    }
  }

  record ResolvedWorkflow(
      String workflowId,
      Workflow workflow,
      String dslText,
      Long semanticRevision,
      CommitId commitId) {
    ResolvedWorkflow {
      StableExecutionIds.requireStable(workflowId, "workflowId");
      Objects.requireNonNull(workflow, "workflow");
      Objects.requireNonNull(dslText, "dslText");
      if (semanticRevision != null && semanticRevision < 0) {
        throw new IllegalArgumentException("semanticRevision must be null or >= 0");
      }
      if ((semanticRevision == null) == (commitId == null)) {
        throw new IllegalArgumentException(
            "Resolved workflow requires exactly one of semanticRevision or commitId");
      }
    }
  }
}
