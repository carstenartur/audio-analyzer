package org.hammer.audio.dsp.workflow;

import java.util.List;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Control;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Violation;

/** Typed adapter for one executable workflow node type in the deterministic audio backend. */
interface DeterministicAudioNodeExecutor {

  /** Returns the stable workflow node type handled by this executor. */
  String nodeType();

  /** Returns parameter and input-cardinality violations for one node. */
  List<Violation> validate(Node node, List<Edge> incomingEdges);

  /** Executes one node against its optional upstream audio value. */
  AudioBlock execute(Node node, AudioBlock input, Control control);
}
