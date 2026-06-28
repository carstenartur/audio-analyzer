package org.hammer.audio.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowSerializationTest {

  private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

  @Test
  void roundTripsThroughJson() throws Exception {
    Workflow workflow =
        new Workflow(
            "workflow.demo",
            "Demo Workflow",
            List.of(
                new Node(
                    "node.import",
                    "dataset-import",
                    "Import Dataset",
                    List.of(),
                    List.of(
                        new Port(
                            "dataset-out",
                            "dataset",
                            PortDirection.OUTPUT,
                            "Dataset",
                            true,
                            PortMultiplicity.SINGLE,
                            new Metadata(Map.of("semanticRole", "primary-output")))),
                    new Metadata(Map.of("group", "input"))),
                new Node(
                    "node.report",
                    "report",
                    "Create Report",
                    List.of(
                        new Port(
                            "dataset-in",
                            "dataset",
                            PortDirection.INPUT,
                            "Dataset",
                            true,
                            PortMultiplicity.SINGLE)),
                    List.of(),
                    new Metadata(Map.of("group", "output")))),
            List.of(
                new Edge(
                    "edge.dataset", "node.import", "dataset-out", "node.report", "dataset-in")),
            new Metadata(Map.of("owner", "workflow-domain")));

    String json = OBJECT_MAPPER.writeValueAsString(workflow);
    Workflow restored = OBJECT_MAPPER.readValue(json, Workflow.class);

    assertEquals(workflow, restored);
  }
}
