package org.hammer.audio.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.execution.ExecutionResult;
import org.hammer.audio.workflow.execution.ExecutionSnapshot;
import org.hammer.audio.workflow.execution.ExecutionStatus;
import org.hammer.audio.workflow.execution.ReproducibilityBundle;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReproducibilityBundleExporterTest {

  private static final Instant SNAP_TIME = Instant.parse("2024-06-01T09:00:00Z");
  private static final Instant START_TIME = Instant.parse("2024-06-01T10:00:00Z");
  private static final Instant END_TIME = Instant.parse("2024-06-01T10:00:30Z");

  private static ReproducibilityBundle buildBundle(CommitId commitId, CommitInfo commitInfo) {
    ExecutionSnapshot snapshot =
        new ExecutionSnapshot(
            "snap.export", "workflow.export", List.of(), List.of(), null, SNAP_TIME);
    ExecutionResult result =
        new ExecutionResult("exec.export", "plan.export", Map.of(), START_TIME, END_TIME);
    return new ReproducibilityBundle(snapshot, result, commitId, commitInfo);
  }

  @Test
  void exportCreatesDirectoryWithAllArtifacts(@TempDir Path tmp) throws Exception {
    ReproducibilityBundle bundle = buildBundle(null, null);

    Path bundleDir = new ReproducibilityBundleExporter(ZoneId.of("UTC")).export(tmp, bundle);

    assertTrue(Files.isDirectory(bundleDir));
    assertTrue(bundleDir.getFileName().toString().startsWith("run-"));
    assertTrue(Files.exists(bundleDir.resolve("metadata.json")));
    assertTrue(Files.exists(bundleDir.resolve("execution-result.json")));
    assertTrue(Files.exists(bundleDir.resolve("workflow-nodes.csv")));
  }

  @Test
  void exportMetadataJsonContainsSnapshotId(@TempDir Path tmp) throws Exception {
    ReproducibilityBundle bundle = buildBundle(null, null);

    Path bundleDir = new ReproducibilityBundleExporter(ZoneId.of("UTC")).export(tmp, bundle);

    String metadata = Files.readString(bundleDir.resolve("metadata.json"));
    assertTrue(metadata.contains("\"snapshotId\": \"snap.export\""), metadata);
    assertTrue(metadata.contains("\"workflowId\": \"workflow.export\""), metadata);
    assertTrue(metadata.contains("\"overallStatus\": \"COMPLETED\""), metadata);
    assertTrue(metadata.contains("\"nodeCount\": 0"), metadata);
  }

  @Test
  void exportMetadataJsonContainsCommitProvenanceWhenPresent(@TempDir Path tmp) throws Exception {
    CommitId commitId = new CommitId("commit-abc123");
    CommitInfo commitInfo =
        new CommitInfo(
            commitId,
            new CommitMetadata("alice", "Checkpoint for run", SNAP_TIME),
            "workflow.export");
    ReproducibilityBundle bundle = buildBundle(commitId, commitInfo);

    Path bundleDir = new ReproducibilityBundleExporter(ZoneId.of("UTC")).export(tmp, bundle);

    String metadata = Files.readString(bundleDir.resolve("metadata.json"));
    assertTrue(metadata.contains("\"commitId\": \"commit-abc123\""), metadata);
    assertTrue(metadata.contains("\"commitAuthor\": \"alice\""), metadata);
    assertTrue(metadata.contains("\"commitMessage\": \"Checkpoint for run\""), metadata);
  }

  @Test
  void exportMetadataJsonContainsNullCommitIdWhenAbsent(@TempDir Path tmp) throws Exception {
    ReproducibilityBundle bundle = buildBundle(null, null);

    Path bundleDir = new ReproducibilityBundleExporter(ZoneId.of("UTC")).export(tmp, bundle);

    String metadata = Files.readString(bundleDir.resolve("metadata.json"));
    assertTrue(metadata.contains("\"commitId\": null"), metadata);
  }

  @Test
  void exportExecutionResultJsonContainsExecutionDetails(@TempDir Path tmp) throws Exception {
    ReproducibilityBundle bundle = buildBundle(null, null);

    Path bundleDir = new ReproducibilityBundleExporter(ZoneId.of("UTC")).export(tmp, bundle);

    String result = Files.readString(bundleDir.resolve("execution-result.json"));
    assertTrue(result.contains("\"executionId\": \"exec.export\""), result);
    assertTrue(result.contains("\"planId\": \"plan.export\""), result);
    assertTrue(result.contains("\"overallStatus\": \"COMPLETED\""), result);
  }

  @Test
  void exportWorkflowNodesCsvHasHeaderRow(@TempDir Path tmp) throws Exception {
    ReproducibilityBundle bundle = buildBundle(null, null);

    Path bundleDir = new ReproducibilityBundleExporter(ZoneId.of("UTC")).export(tmp, bundle);

    String csv = Files.readString(bundleDir.resolve("workflow-nodes.csv"));
    assertTrue(csv.startsWith("nodeId,type,label"), csv);
  }

  @Test
  void exportDirectoryNameStartsWithRunAndTimestamp(@TempDir Path tmp) throws Exception {
    ReproducibilityBundle bundle = buildBundle(null, null);

    Path bundleDir = new ReproducibilityBundleExporter(ZoneId.of("UTC")).export(tmp, bundle);

    // END_TIME = 2024-06-01T10:00:30Z → 20240601-100030
    assertTrue(
        bundleDir.getFileName().toString().startsWith("run-20240601-100030"),
        bundleDir.getFileName().toString());
  }

  @Test
  void duplicateBundleNamesGetNumericSuffix(@TempDir Path tmp) throws Exception {
    ReproducibilityBundle bundle = buildBundle(null, null);
    ReproducibilityBundleExporter exporter = new ReproducibilityBundleExporter(ZoneId.of("UTC"));

    Path first = exporter.export(tmp, bundle);
    Path second = exporter.export(tmp, bundle);

    assertTrue(Files.isDirectory(first));
    assertTrue(Files.isDirectory(second));
    assertTrue(
        second.getFileName().toString().endsWith("-1"),
        "Second export should have -1 suffix: " + second.getFileName());
  }

  @Test
  void rejectsNullBundle(@TempDir Path tmp) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReproducibilityBundleExporter(ZoneId.of("UTC")).export(tmp, null));
  }

  @Test
  void rejectsNullParentDirectory() {
    ReproducibilityBundle bundle = buildBundle(null, null);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReproducibilityBundleExporter(ZoneId.of("UTC")).export(null, bundle));
  }

  @Test
  void rejectsNullZoneId() {
    assertThrows(IllegalArgumentException.class, () -> new ReproducibilityBundleExporter(null));
  }

  @Test
  void defaultConstructorUsesSystemTimeZone(@TempDir Path tmp) throws Exception {
    ReproducibilityBundle bundle = buildBundle(null, null);
    // Should not throw
    Path bundleDir = new ReproducibilityBundleExporter().export(tmp, bundle);
    assertTrue(Files.isDirectory(bundleDir));
  }

  @Test
  void exportWithNodeStatusesWritesThemToResultJson(@TempDir Path tmp) throws Exception {
    ExecutionSnapshot snapshot =
        new ExecutionSnapshot(
            "snap.nodes", "workflow.nodes", List.of(), List.of(), null, SNAP_TIME);
    ExecutionResult result =
        new ExecutionResult(
            "exec.nodes",
            "plan.nodes",
            Map.of(
                "node.a", ExecutionStatus.COMPLETED,
                "node.b", ExecutionStatus.FAILED),
            START_TIME,
            END_TIME);
    ReproducibilityBundle bundle = new ReproducibilityBundle(snapshot, result, null, null);

    Path bundleDir = new ReproducibilityBundleExporter(ZoneId.of("UTC")).export(tmp, bundle);

    String resultJson = Files.readString(bundleDir.resolve("execution-result.json"));
    assertTrue(resultJson.contains("\"node.a\": \"COMPLETED\""), resultJson);
    assertTrue(resultJson.contains("\"node.b\": \"FAILED\""), resultJson);
    assertEquals("FAILED", result.overallStatus().name());
  }
}
