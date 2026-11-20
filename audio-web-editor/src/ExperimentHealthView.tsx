import type { ExperimentHealth } from './experimentOperationsApi';
import './experiment-health.css';

interface Props {
  title: string;
  health: ExperimentHealth | null;
  error?: string | null;
}

const SUMMARY_FACTS: Array<[string, string]> = [
  ['document.name', 'Document'], ['document.format', 'Format / version'],
  ['document.dirty', 'Unsaved changes'], ['document.revision', 'Document revision'],
  ['bindings.status', 'Local bindings'], ['workflow.sessionId', 'Session'],
  ['collaboration.owner', 'Session owner'], ['operator.attribution', 'Operator'],
  ['workflow.revision', 'Semantic revision'], ['workflow.commit', 'Commit'],
  ['workflow.validation', 'Workflow validation'], ['backend.mode', 'Execution capability'],
  ['backend.version', 'Backend version'], ['run.progressPercent', 'Progress (%)'],
  ['evidence.complete', 'Complete scoped evidence'], ['evidence.scope', 'Evidence scope'],
];

function exportSnapshot(health: ExperimentHealth) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(health, null, 2)], { type: 'application/json' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = 'experiment-health.json';
  anchor.click();
  URL.revokeObjectURL(url);
}

/** All facts come from the shared immutable server projection, including explicit non-applicability. */
export function ExperimentHealthView({ title, health, error = null }: Props) {
  return <section className="experiment-health" aria-label={title} data-testid="shared-experiment-health">
    <h3>{title}</h3>
    <strong className={`experiment-health__state experiment-health__state--${health?.state.toLowerCase() ?? 'unknown'}`}
      aria-live="polite" aria-atomic="true" data-testid="shared-experiment-health-state">
      {error ? '⚠ STATUS UNAVAILABLE' : health ? `${health.state === 'BLOCKED' || health.state === 'FAILED' ? '⛔' : '●'} ${health.state.replaceAll('_', ' ')}` : 'Checking readiness…'}
    </strong>
    {error && <p role="alert">{error} A previous successful check does not permit a new run.</p>}
    {health && <>
      <p>{health.experimentName} · {health.stage}</p>
      <dl className="experiment-health__summary">{SUMMARY_FACTS.filter(([key]) => health.facts[key] !== undefined).map(([key, label]) =>
        <div key={key}><dt>{label}</dt><dd>{health.facts[key]}</dd></div>)}</dl>
      <ul className="experiment-health__findings">{health.findings.filter((finding) => finding.severity !== 'INFO').map((finding) =>
        <li key={finding.code}><strong>{finding.severity}: {finding.message}</strong>
          <details><summary>{finding.component} · {finding.code}</summary><p>{finding.detail}</p>
            <p>Observed <time data-screenshot-mask="true">{finding.observedAt}</time></p></details></li>)}</ul>
      {health.facts['document.execution'] && <p className="help-text">{health.facts['document.execution']}</p>}
      {health.facts['bindings.status'] === 'UNVERIFIED_FOR_CURRENT_SNAPSHOT' && <button type="button" className="action-button"
        onClick={() => document.querySelector('[data-testid="experiment-document"]')?.scrollIntoView({ block: 'start' })}>Review document and local bindings</button>}
      <details><summary>Hashes, plugin versions and full diagnostic snapshot</summary>
        <dl className="experiment-health__details">{Object.entries(health.facts).sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) =>
          <div key={key}><dt>{key}</dt><dd>{value}</dd></div>)}</dl>
        <p>Snapshot observed <time data-screenshot-mask="true">{health.observedAt}</time></p>
        <ol>{health.history.map((event, index) => <li key={index}>{event.observedAt} · {event.action} · {event.detail}</li>)}</ol>
      </details>
      <button type="button" className="action-button" onClick={() => exportSnapshot(health)}>Export health snapshot</button>
    </>}
  </section>;
}
