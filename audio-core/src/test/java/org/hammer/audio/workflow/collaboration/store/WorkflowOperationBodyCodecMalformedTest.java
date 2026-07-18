package org.hammer.audio.workflow.collaboration.store;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.WorkflowOperation;
import org.junit.jupiter.api.Test;

class WorkflowOperationBodyCodecMalformedTest {

  @Test
  void truncatedFinalStringIsRejected() {
    WorkflowOperationBodyCodec.EncodedBody encoded =
        WorkflowOperationBodyCodec.encode(
            new WorkflowOperation.CreateNode(
                "operation.truncated",
                Instant.parse("2026-07-18T22:15:00Z"),
                "actor.one",
                new Node(
                    "node.one", "test", "One", List.of(), List.of(), Metadata.empty())));
    byte[] complete = Base64.getUrlDecoder().decode(encoded.body());
    String truncated =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(Arrays.copyOf(complete, complete.length - 1));

    assertThrows(
        IllegalArgumentException.class,
        () -> WorkflowOperationBodyCodec.decode(encoded.version(), truncated));
  }
}
