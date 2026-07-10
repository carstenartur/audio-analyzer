package org.hammer.audio.export;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.execution.ExecutionResult;
import org.hammer.audio.workflow.execution.ExecutionSnapshot;
import org.hammer.audio.workflow.execution.ExecutionStatus;
import org.hammer.audio.workflow.execution.ReproducibilityBundle;

/**
 * Writes a {@link ReproducibilityBundle} as a versioned directory of plain-text artifacts.
 *
 * <p>Given a parent directory and an immutable {@link ReproducibilityBundle} payload, creates a
 * {@code run-YYYYMMDD-HHMMSS} sub-directory containing:
 *
 * <ul>
 *   <li>{@code metadata.json} — bundle-level metadata: snapshot ID, workflow ID, commit ID,
 *       creation timestamp, overall execution status and node count.
 *   <li>{@code execution-result.json} — per-run outcome: execution ID, plan ID, started/completed
 *       instants, overall status and per-node terminal statuses.
 *   <li>{@code workflow-nodes.csv} — one row per workflow node: node ID, type and label.
 * </ul>
 *
 * <p>All methods are pure I/O functions. No workflow DSL serialisation, no Swing/UI code and no
 * JGit internals are used here. The class is intentionally kept narrow; new artifact types can be
 * added without changing the exporter contract.
 *
 * <p>Duplicate bundle names in the same parent directory are resolved by appending a numeric suffix
 * (e.g. {@code run-20240101-120000-1}), matching the behaviour of {@link EvidenceBundleExporter}.
 */
public final class ReproducibilityBundleExporter {

  private static final DateTimeFormatter DIR_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

  private final ZoneId zoneId;

  /** Creates an exporter using the system default time zone. */
  public ReproducibilityBundleExporter() {
    this(ZoneId.systemDefault());
  }

  /**
   * Creates an exporter with a fixed time zone used to format the bundle directory name.
   *
   * @param zoneId time zone for the directory timestamp
   */
  public ReproducibilityBundleExporter(ZoneId zoneId) {
    if (zoneId == null) {
      throw new IllegalArgumentException("zoneId must not be null");
    }
    this.zoneId = zoneId;
  }

  /**
   * Writes a reproducibility bundle to a new sub-directory under {@code parentDirectory}.
   *
   * @param parentDirectory directory under which the bundle directory is created
   * @param bundle reproducibility evidence to export
   * @return the path to the created bundle directory
   * @throws IOException if writing any artifact fails
   * @throws IllegalArgumentException if bundle is null
   */
  public Path export(Path parentDirectory, ReproducibilityBundle bundle) throws IOException {
    if (parentDirectory == null) {
      throw new IllegalArgumentException("parentDirectory must not be null");
    }
    if (bundle == null) {
      throw new IllegalArgumentException("bundle must not be null");
    }

    Files.createDirectories(parentDirectory);
    Instant timestamp = bundle.result().completedAt();
    String baseName = "run-" + LocalDateTime.ofInstant(timestamp, zoneId).format(DIR_TIMESTAMP);
    Path bundleDir = parentDirectory.resolve(baseName);
    int suffix = 1;
    while (Files.exists(bundleDir)) {
      bundleDir = parentDirectory.resolve(baseName + "-" + suffix);
      suffix++;
    }
    Files.createDirectory(bundleDir);

    writeMetadata(bundleDir.resolve("metadata.json"), bundle);
    writeExecutionResult(bundleDir.resolve("execution-result.json"), bundle.result());
    writeWorkflowNodes(bundleDir.resolve("workflow-nodes.csv"), bundle.snapshot());

    return bundleDir;
  }

  private static void writeMetadata(Path file, ReproducibilityBundle bundle) throws IOException {
    ExecutionSnapshot snapshot = bundle.snapshot();
    ExecutionResult result = bundle.result();
    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
      w.println("{");
      w.printf(Locale.ROOT, "  \"snapshotId\": \"%s\",%n", escape(snapshot.snapshotId()));
      w.printf(Locale.ROOT, "  \"workflowId\": \"%s\",%n", escape(snapshot.workflowId()));
      w.printf(Locale.ROOT, "  \"snapshotCreatedAt\": \"%s\",%n", snapshot.createdAt());
      if (bundle.commitId() != null) {
        w.printf(Locale.ROOT, "  \"commitId\": \"%s\",%n", escape(bundle.commitId().value()));
      } else {
        w.printf(Locale.ROOT, "  \"commitId\": null,%n");
      }
      if (bundle.commitInfo() != null) {
        w.printf(
            Locale.ROOT,
            "  \"commitAuthor\": \"%s\",%n",
            escape(bundle.commitInfo().metadata().author()));
        w.printf(
            Locale.ROOT,
            "  \"commitMessage\": \"%s\",%n",
            escape(bundle.commitInfo().metadata().message()));
        w.printf(
            Locale.ROOT,
            "  \"commitTimestamp\": \"%s\",%n",
            bundle.commitInfo().metadata().timestamp());
      }
      w.printf(Locale.ROOT, "  \"overallStatus\": \"%s\",%n", result.overallStatus());
      w.printf(Locale.ROOT, "  \"nodeCount\": %d%n", snapshot.nodes().size());
      w.println("}");
    }
  }

  private static void writeExecutionResult(Path file, ExecutionResult result) throws IOException {
    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
      w.println("{");
      w.printf(Locale.ROOT, "  \"executionId\": \"%s\",%n", escape(result.executionId()));
      w.printf(Locale.ROOT, "  \"planId\": \"%s\",%n", escape(result.planId()));
      w.printf(Locale.ROOT, "  \"startedAt\": \"%s\",%n", result.startedAt());
      w.printf(Locale.ROOT, "  \"completedAt\": \"%s\",%n", result.completedAt());
      w.printf(Locale.ROOT, "  \"overallStatus\": \"%s\",%n", result.overallStatus());
      w.println("  \"nodeStatuses\": {");
      List<Map.Entry<String, ExecutionStatus>> entries =
          result.nodeStatuses().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
      for (int i = 0; i < entries.size(); i++) {
        Map.Entry<String, ExecutionStatus> entry = entries.get(i);
        String comma = (i < entries.size() - 1) ? "," : "";
        w.printf(
            Locale.ROOT, "    \"%s\": \"%s\"%s%n", escape(entry.getKey()), entry.getValue(), comma);
      }
      w.println("  }");
      w.println("}");
    }
  }

  private static void writeWorkflowNodes(Path file, ExecutionSnapshot snapshot) throws IOException {
    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
      w.println("nodeId,type,label");
      for (Node node : snapshot.nodes()) {
        w.printf(
            Locale.ROOT,
            "%s,%s,%s%n",
            escapeCsv(node.id()),
            escapeCsv(node.type()),
            escapeCsv(node.label()));
      }
    }
  }

  private static String escape(String s) {
    StringBuilder out = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  private static String escapeCsv(String s) {
    if (s == null) {
      return "";
    }
    if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
      return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    return s;
  }
}
