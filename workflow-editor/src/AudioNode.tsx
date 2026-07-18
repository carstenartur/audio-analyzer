import { Handle, Position, type NodeProps } from '@xyflow/react';
import type { AudioFlowNode } from './projection';

const DATA_TYPE_COLORS: Record<string, string> = {
  AudioBlock: '#89b4fa',
  Dataset: '#cba6f7',
  Spectrum: '#fab387',
  FeatureSet: '#a6e3a1',
  ClassificationResult: '#f38ba8',
  LocalizationResult: '#94e2d5',
  BenchmarkResult: '#f9e2af',
  Report: '#bac2de',
};

function handleColor(dataType: string): string {
  return DATA_TYPE_COLORS[dataType] ?? '#9399b2';
}

export function AudioNode({ data, selected }: NodeProps<AudioFlowNode>) {
  const node = data.projection;
  return (
    <article
      className={`audio-node${selected ? ' audio-node--selected' : ''}`}
      data-testid={`node-${node.id}`}
    >
      {node.inputHandles.map((handle, index) => (
        <Handle
          key={handle.id}
          type="target"
          position={Position.Left}
          id={handle.id}
          title={`${handle.name} (${handle.dataType})`}
          style={{
            background: handleColor(handle.dataType),
            top: 42 + index * 22,
          }}
        />
      ))}
      <header>
        <strong>{node.label}</strong>
        <span>{node.type}</span>
      </header>
      <dl>
        {Object.entries(node.properties).map(([key, value]) => (
          <div key={key}>
            <dt>{key}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      {node.outputHandles.map((handle, index) => (
        <Handle
          key={handle.id}
          type="source"
          position={Position.Right}
          id={handle.id}
          title={`${handle.name} (${handle.dataType})`}
          style={{
            background: handleColor(handle.dataType),
            top: 42 + index * 22,
          }}
        />
      ))}
    </article>
  );
}
