export interface HandleProjection {
  id: string;
  name: string;
  dataType: string;
}

export interface NodeProjection {
  id: string;
  type: string;
  label: string;
  inputHandles: HandleProjection[];
  outputHandles: HandleProjection[];
  properties: Record<string, string>;
}

export interface EdgeProjection {
  id: string;
  source: string;
  sourceHandle: string;
  target: string;
  targetHandle: string;
}

export interface WorkflowProjection {
  workflowId: string;
  workflowName: string;
  nodes: NodeProjection[];
  edges: EdgeProjection[];
}

export interface CatalogEntry {
  type: string;
  label: string;
  inputHandles: HandleProjection[];
  outputHandles: HandleProjection[];
}

export interface HistoryEntry {
  commitId: string;
  workflowId: string;
  author: string;
  message: string;
  timestamp: string;
}

export interface ValidationResponse {
  violations: string[];
}

export interface CheckpointResponse {
  commitId: string;
}

export interface WorkflowSnapshot {
  workflowId: string;
  dslText: string;
}

export interface CreateNodeOperation {
  type: 'CreateNode';
  operationId: string;
  author: string;
  nodeId: string;
  catalogType: string;
}

export interface ConnectPortsOperation {
  type: 'ConnectPorts';
  operationId: string;
  author: string;
  edge: {
    id: string;
    sourceNodeId: string;
    sourcePortId: string;
    targetNodeId: string;
    targetPortId: string;
  };
}

export interface DisconnectPortsOperation {
  type: 'DisconnectPorts';
  operationId: string;
  author: string;
  edgeId: string;
  disconnectedEdge: ConnectPortsOperation['edge'];
}

export interface UpdatePropertyOperation {
  type: 'UpdateProperty';
  operationId: string;
  author: string;
  target: 'NODE';
  targetId: string;
  propertyKey: string;
  previousValue: string | null;
  newValue: string;
}

export type WorkflowOperation =
  | CreateNodeOperation
  | ConnectPortsOperation
  | DisconnectPortsOperation
  | UpdatePropertyOperation;
