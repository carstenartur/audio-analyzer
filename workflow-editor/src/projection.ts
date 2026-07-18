import type { Edge, Node } from '@xyflow/react';
import type { EdgeProjection, NodeProjection, WorkflowProjection } from './model';

export interface AudioNodeData extends Record<string, unknown> {
  projection: NodeProjection;
}

export type AudioFlowNode = Node<AudioNodeData, 'audioNode'>;
export type AudioFlowEdge = Edge;

export function toFlowNodes(nodeProjections: NodeProjection[]): AudioFlowNode[] {
  return nodeProjections.map((projection, index) => ({
    id: projection.id,
    type: 'audioNode',
    position: {
      x: 72 + (index % 4) * 240,
      y: 96 + Math.floor(index / 4) * 180,
    },
    data: { projection },
  }));
}

export function toFlowEdges(edgeProjections: EdgeProjection[]): AudioFlowEdge[] {
  return edgeProjections.map((projection) => ({
    id: projection.id,
    source: projection.source,
    sourceHandle: projection.sourceHandle,
    target: projection.target,
    targetHandle: projection.targetHandle,
    animated: false,
  }));
}

export function projectionSummary(projection: WorkflowProjection): string {
  return `${projection.workflowName} · ${projection.nodes.length} nodes · ${projection.edges.length} edges`;
}

export function stableOperationId(prefix: string): string {
  return `op.${prefix}.${crypto.randomUUID()}`;
}
