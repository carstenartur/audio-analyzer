package org.hammer.audio.workflow.history;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;

/**
 * Read-only query API for searching workflow history stored in a {@link VersionedWorkflowStore}.
 *
 * <p>This service walks a reverse-chronological commit list, parses each stored snapshot back into
 * a domain {@link Workflow} and applies a predicate to decide whether the version should be
 * included in the result. It is a <em>derived view</em> only; it never mutates the store or any
 * editor state.
 *
 * <p>Callers must supply a {@link VersionedWorkflowStore} and a ref name (branch). The number of
 * commits examined is bounded by the {@code historyLimit} parameter passed to each search method.
 *
 * <p><b>Performance note</b>: this implementation parses every snapshot in the examined history
 * window. For production usage at scale, a dedicated search index should be used instead; this
 * class exists to satisfy the read-only query API requirement without introducing an index
 * dependency in the domain layer.
 *
 * <p>Owned by the semantic-analysis/history-projection layer. Must not depend on UI, JGit or
 * execution internals.
 */
public final class WorkflowHistorySearch {

  private final WorkflowDslParser parser;

  /** Creates a search service using a default {@link WorkflowDslParser}. */
  public WorkflowHistorySearch() {
    this.parser = new WorkflowDslParser();
  }

  /**
   * A commit that matched a search query, together with the identifiers of the matching objects
   * inside the workflow at that commit.
   *
   * @param commitInfo commit summary from the store
   * @param matchedObjectIds stable identifiers of nodes or edges that matched the query; may be
   *     empty when the match is on commit-level metadata only
   */
  public record CommitMatch(CommitInfo commitInfo, List<String> matchedObjectIds) {
    public CommitMatch {
      Objects.requireNonNull(commitInfo, "commitInfo");
      matchedObjectIds = List.copyOf(Objects.requireNonNull(matchedObjectIds, "matchedObjectIds"));
    }
  }

  /**
   * Returns the commits (up to {@code historyLimit}) on {@code refName} whose workflow snapshot
   * contains at least one node with the given {@code nodeType}.
   *
   * @param store versioned workflow store; must not be {@code null}
   * @param refName branch or ref name; must not be blank
   * @param historyLimit maximum number of commits to examine; must be &ge; 0
   * @param nodeType node type string to search for; must not be blank
   * @return matching commits, most recent first
   */
  public List<CommitMatch> findByNodeType(
      VersionedWorkflowStore store, String refName, int historyLimit, String nodeType) {
    Objects.requireNonNull(store, "store");
    requireNonBlank(refName, "refName");
    requireNonNegative(historyLimit, "historyLimit");
    requireNonBlank(nodeType, "nodeType");

    List<CommitMatch> results = new ArrayList<>();
    for (CommitInfo info : store.history(refName, historyLimit)) {
      Workflow workflow = parseWorkflow(store, info);
      List<String> matched = new ArrayList<>();
      for (Node node : workflow.nodes()) {
        if (nodeType.equals(node.type())) {
          matched.add(node.id());
        }
      }
      if (!matched.isEmpty()) {
        results.add(new CommitMatch(info, matched));
      }
    }
    return List.copyOf(results);
  }

  /**
   * Returns the commits (up to {@code historyLimit}) on {@code refName} whose workflow snapshot
   * contains at least one node or edge with a metadata property matching the given key and value.
   *
   * @param store versioned workflow store; must not be {@code null}
   * @param refName branch or ref name; must not be blank
   * @param historyLimit maximum number of commits to examine; must be &ge; 0
   * @param paramKey metadata property key to search for; must not be blank
   * @param paramValue expected metadata property value; must not be {@code null}
   * @return matching commits, most recent first
   */
  public List<CommitMatch> findByParameter(
      VersionedWorkflowStore store,
      String refName,
      int historyLimit,
      String paramKey,
      String paramValue) {
    Objects.requireNonNull(store, "store");
    requireNonBlank(refName, "refName");
    requireNonNegative(historyLimit, "historyLimit");
    requireNonBlank(paramKey, "paramKey");
    Objects.requireNonNull(paramValue, "paramValue");

    List<CommitMatch> results = new ArrayList<>();
    for (CommitInfo info : store.history(refName, historyLimit)) {
      Workflow workflow = parseWorkflow(store, info);
      List<String> matched = new ArrayList<>();
      for (Node node : workflow.nodes()) {
        String v = node.metadata().entries().get(paramKey);
        if (paramValue.equals(v)) {
          matched.add(node.id());
        }
      }
      workflow
          .edges()
          .forEach(
              edge -> {
                String v = edge.metadata().entries().get(paramKey);
                if (paramValue.equals(v)) {
                  matched.add(edge.id());
                }
              });
      if (!matched.isEmpty()) {
        results.add(new CommitMatch(info, matched));
      }
    }
    return List.copyOf(results);
  }

  /**
   * Returns the commits (up to {@code historyLimit}) on {@code refName} whose commit author equals
   * the given {@code author}.
   *
   * @param store versioned workflow store; must not be {@code null}
   * @param refName branch or ref name; must not be blank
   * @param historyLimit maximum number of commits to examine; must be &ge; 0
   * @param author author string to match exactly; must not be blank
   * @return matching commits, most recent first
   */
  public List<CommitMatch> findByAuthor(
      VersionedWorkflowStore store, String refName, int historyLimit, String author) {
    Objects.requireNonNull(store, "store");
    requireNonBlank(refName, "refName");
    requireNonNegative(historyLimit, "historyLimit");
    requireNonBlank(author, "author");

    List<CommitMatch> results = new ArrayList<>();
    for (CommitInfo info : store.history(refName, historyLimit)) {
      if (author.equals(info.metadata().author())) {
        results.add(new CommitMatch(info, List.of()));
      }
    }
    return List.copyOf(results);
  }

  private Workflow parseWorkflow(VersionedWorkflowStore store, CommitInfo info) {
    WorkflowSnapshot snapshot = store.loadAtCommit(info.commitId());
    return parser.parse(snapshot.dslText());
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  private static void requireNonNegative(int value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be >= 0");
    }
  }
}
