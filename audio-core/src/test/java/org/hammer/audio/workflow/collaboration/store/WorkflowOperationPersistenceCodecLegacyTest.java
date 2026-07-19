package org.hammer.audio.workflow.collaboration.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.WorkflowOperation;
import org.junit.jupiter.api.Test;

class WorkflowOperationPersistenceCodecLegacyTest {

  @Test
  void affectedObjectIdsRemainReadableWithoutOperationBody() {
    WorkflowOperation operation =
        new WorkflowOperation.RenameNode(
            "operation.rename",
            Instant.parse("2026-07-19T08:00:00Z"),
            "actor.owner",
            "node.input",
            "Before",
            "After");
    WorkflowOperationPersistenceData encoded = WorkflowOperationPersistenceCodec.encode(operation);

    assertEquals(
        List.of("node.input"),
        WorkflowOperationPersistenceCodec.decodeAffectedObjectIds(encoded.payload()));
  }

  @Test
  void malformedAffectedObjectPrefixIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkflowOperationPersistenceCodec.decodeAffectedObjectIds("not-a-count:"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkflowOperationPersistenceCodec.decodeAffectedObjectIds("1:20:truncated"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkflowOperationPersistenceCodec.decodeAffectedObjectIds("-1:"));
  }
}
