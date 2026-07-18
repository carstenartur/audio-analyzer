import { describe, expect, it, vi } from 'vitest';
import type { WorkflowProjection } from './model';
import { projectionSummary, stableOperationId, toFlowEdges, toFlowNodes } from './projection';

const projection: WorkflowProjection = {
  workflowId: 'workflow.demo',
  workflowName: 'Demo',
  nodes: [
    {
      id: 'source',
      type: 'SyntheticSignalGenerator',
      label: 'Source',
      inputHandles: [],
      outputHandles: [{ id: 'audio', name: 'Audio', dataType: 'AudioBlock' }],
      properties: {},
    },
    {
      id: 'gain',
      type: 'Gain',
      label: 'Gain',
      inputHandles: [{ id: 'input', name: 'Input', dataType: 'AudioBlock' }],
      outputHandles: [{ id: 'output', name: 'Output', dataType: 'AudioBlock' }],
      properties: { gain: '1.5' },
    },
  ],
  edges: [
    {
      id: 'source-to-gain',
      source: 'source',
      sourceHandle: 'audio',
      target: 'gain',
      targetHandle: 'input',
    },
  ],
};

describe('server projection adapter', () => {
  it('creates deterministic browser-only positions without changing semantic data', () => {
    const nodes = toFlowNodes(projection.nodes);
    expect(nodes.map((node) => node.id)).toEqual(['source', 'gain']);
    expect(nodes[0]?.position).toEqual({ x: 72, y: 96 });
    expect(nodes[1]?.position).toEqual({ x: 312, y: 96 });
    expect(nodes[1]?.data.projection.properties).toEqual({ gain: '1.5' });
  });

  it('preserves typed handle identities on edges', () => {
    expect(toFlowEdges(projection.edges)).toEqual([
      expect.objectContaining({
        id: 'source-to-gain',
        source: 'source',
        sourceHandle: 'audio',
        target: 'gain',
        targetHandle: 'input',
      }),
    ]);
  });

  it('summarizes the authoritative projection', () => {
    expect(projectionSummary(projection)).toBe('Demo · 2 nodes · 1 edges');
  });

  it('uses random UUIDs instead of timestamps for idempotency identities', () => {
    vi.stubGlobal('crypto', { randomUUID: () => '123e4567-e89b-12d3-a456-426614174000' });
    expect(stableOperationId('create')).toBe(
      'op.create.123e4567-e89b-12d3-a456-426614174000',
    );
    vi.unstubAllGlobals();
  });
});
