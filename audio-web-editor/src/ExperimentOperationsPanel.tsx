import { useCallback, useEffect, useMemo, useState } from 'react';
import { getJson, postJson } from './api';
import type {
  ExperimentCatalogue, ExperimentConfiguration, ExperimentPreflight, ExperimentRun,
  MicrophoneProfileView,
} from './experimentOperationsApi';
import {
  applyMeasuredCalibration, canStartExperiment, editProfileDefinition, formatCapacity,
  formatSafeDuration, profileValues, resizeProfileDefinition,
} from './experimentOperationsState.mjs';
import './experiment-operations.css';

const API = '/experiment-operations';
const INITIAL: ExperimentConfiguration = {
  name: 'Microphone-array experiment', sourceMode: 'SIMULATION', profileId: '', scenario: 'single-source',
  recordingId: '', durationSeconds: 5, recordOutput: true, outputFilename: 'capture.aarec',
};

function failureText(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure);
}

function downloadDiagnostic(value: unknown): void {
  const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = 'experiment-diagnostics.json';
  link.click();
  URL.revokeObjectURL(url);
}

/** Persistent source/profile/readiness workflow backed by the shared host application service. */
export function ExperimentOperationsPanel() {
  const [catalogue, setCatalogue] = useState<ExperimentCatalogue | null>(null);
  const [configuration, setConfiguration] = useState(INITIAL);
  const [definition, setDefinition] = useState('');
  const [preflight, setPreflight] = useState<ExperimentPreflight | null>(null);
  const [run, setRun] = useState<ExperimentRun | null>(null);
  const [reviewed, setReviewed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [storageAcknowledged, setStorageAcknowledged] = useState(false);
  const [selectedDevice, setSelectedDevice] = useState('');
  const [calibrationId, setCalibrationId] = useState('');
  const [calibratedAt, setCalibratedAt] = useState('');
  const [validUntil, setValidUntil] = useState('');
  const [calibrationRows, setCalibrationRows] = useState('');

  const loadCatalogue = useCallback(async () => {
    const value = await getJson<ExperimentCatalogue>(`${API}/catalogue`);
    setCatalogue(value);
    setConfiguration((current) => current.profileId ? current : { ...current, profileId: value.profiles[0]?.id ?? '' });
    setDefinition((current) => current || value.profiles[0]?.definition || '');
  }, []);

  useEffect(() => { void loadCatalogue().catch((failure) => setError(failureText(failure))); }, [loadCatalogue]);
  useEffect(() => {
    let active = true;
    const poll = () => { void getJson<ExperimentRun>(`${API}/status`).then((value) => {
      if (active) setRun(value);
    }).catch((failure) => { if (active) setError(failureText(failure)); }); };
    poll();
    const interval = window.setInterval(poll, 750);
    return () => { active = false; window.clearInterval(interval); };
  }, []);
  useEffect(() => { if (run?.exportAvailable) void loadCatalogue().catch((failure) => setError(failureText(failure))); }, [run?.exportAvailable, loadCatalogue]);

  const profile = catalogue?.profiles.find((value) => value.id === configuration.profileId);
  const dirtyProfile = profile !== undefined && definition !== profile.definition;
  const values = useMemo(() => {
    try { return profileValues(definition); } catch { return {} as Record<string, string>; }
  }, [definition]);
  const channels = Math.min(64, Math.max(0, Number(values['array.channels']) || 0));
  const running = run !== null && !run.exportAvailable && ['RUNNING', 'DEGRADED', 'STOPPING'].includes(run.health.state);
  const health = preflight?.health ?? run?.health;
  const storage = running ? run?.recording?.storage : preflight?.storage ?? run?.recording?.storage;
  const recording = run?.recording;
  const storageNeedsAcknowledgement = preflight?.health.findings.some((finding) => finding.code === 'storage.margin') ?? false;
  const startAllowed = canStartExperiment(preflight?.health ?? null, reviewed) && !dirtyProfile && !busy && !running
    && (!storageNeedsAcknowledgement || storageAcknowledged);

  function changed(update: Partial<ExperimentConfiguration>) {
    setConfiguration((current) => ({ ...current, ...update }));
    setReviewed(false);
    setPreflight(null);
    setStorageAcknowledged(false);
  }

  function editDefinition(next: string) {
    setDefinition(next);
    setReviewed(false);
    setPreflight(null);
  }

  async function command(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try { await action(); } catch (failure) { setError(failureText(failure)); }
    finally { setBusy(false); }
  }

  async function inspect() {
    const value = await postJson<ExperimentPreflight>(`${API}/preflight`, configuration);
    setPreflight(value);
    setReviewed(true);
  }

  async function saveProfile() {
    const saved = await postJson<MicrophoneProfileView>(`${API}/profiles`, { definition });
    await loadCatalogue();
    setDefinition(saved.definition);
    changed({ profileId: saved.id });
  }

  async function start() {
    if (preflight === null) return;
    const value = await postJson<ExperimentRun>(`${API}/start`, {
      configuration, token: preflight.token, acknowledgeStorageWarning: storageAcknowledged,
    });
    setRun(value);
    setPreflight(null);
    setReviewed(false);
  }

  function bindDevice() {
    const device = catalogue?.devices.find((item) => item.deviceId === selectedDevice);
    if (!device) return;
    editDefinition(editProfileDefinition(definition, {
      'capture.present': 'true', 'capture.device.id': device.deviceId, 'capture.device.name': device.name,
      'capture.device.vendor': device.vendor, 'capture.device.description': device.description,
      'capture.device.version': device.version, 'capture.sampleRate': values['capture.sampleRate'] || '48000',
      'capture.channels': String(channels), 'capture.sampleBits': values['capture.sampleBits'] || '16',
      'capture.signed': 'true', 'capture.bigEndian': 'false', 'profile.modes': 'SIMULATION,REPLAY,LIVE',
    }, true));
  }

  async function upload(file: File) {
    const response = await fetch(`${API}/recordings`, { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: file });
    if (!response.ok) { const problem = await response.json() as { detail?: string }; throw new Error(problem.detail ?? 'Recording import failed.'); }
    const imported = await response.json() as { id: string };
    await loadCatalogue();
    changed({ recordingId: imported.id, sourceMode: 'REPLAY' });
  }

  const microphones = Array.from({ length: channels }, (_, index) => ({
    id: values[`array.microphone.${index}.id`] || `mic-${index}`,
    x: Number(values[`array.microphone.${index}.x`]) || 0,
    y: Number(values[`array.microphone.${index}.y`]) || 0,
  }));
  const allPositions = [...microphones, ...(run?.tracks ?? [])];
  const minX = Math.min(0, ...allPositions.map((point) => point.x));
  const minY = Math.min(0, ...allPositions.map((point) => point.y));
  const width = Math.max(3, ...allPositions.map((point) => point.x)) - minX;
  const height = Math.max(2, ...allPositions.map((point) => point.y)) - minY;
  const plotX = (x: number) => 40 + (x - minX) / width * 520;
  const plotY = (y: number) => 300 - (y - minY) / height * 260;

  return <main className="experiment-operations" data-testid="experiment-operations">
    <header className="experiment-operations__header">
      <div><a href="/workbench">← Workflow workbench</a><h1>Microphone experiment</h1>
        <p>Define an array · validate calibration · acquire · localize · benchmark · preserve evidence</p></div>
      <span className={`experiment-health-badge experiment-health-badge--${health?.state.toLowerCase() ?? 'not_configured'}`}
        aria-live="polite" aria-atomic="true" data-testid="experiment-health-state">{health?.state.replaceAll('_', ' ') ?? 'NOT CONFIGURED'}</span>
    </header>

    <section className="experiment-strip" aria-label="Persistent experiment health" data-testid="experiment-runtime-strip">
      <strong className={recording?.state === 'RECORDING' ? 'experiment-recording' : ''}>
        {recording?.state === 'RECORDING' ? '● Recording' : '○ ' + (health?.stage ?? 'Awaiting preflight')}</strong>
      <span>Elapsed {formatSafeDuration(Number(run?.health.facts['elapsed.sampleSeconds'] ?? 0))}</span>
      <span>Intended {formatSafeDuration(Number(health?.facts['intended.seconds'] ?? configuration.durationSeconds))}</span>
      <span>Written {formatCapacity(recording?.bytesWritten ?? 0)}</span>
      <span data-screenshot-mask="true">Usable {formatCapacity(storage?.usableBytes ?? -1)}</span>
      <span data-screenshot-mask="true">Rate {formatCapacity(recording?.measuredBytesPerSecond ?? storage?.expectedBytesPerSecond ?? -1)}/s</span>
      <span data-screenshot-mask="true">Safe time {formatSafeDuration(storage?.estimatedSafeSecondsRemaining ?? -1)}</span>
      <button type="button" disabled={!running || busy} onClick={() => void command(async () => { setRun(await postJson<ExperimentRun>(`${API}/stop`, {})); })}
        title="Stop source acquisition, finalize captured samples, and label an early stop incomplete." data-testid="experiment-stop">Stop &amp; finalize</button>
    </section>

    {error && <p className="experiment-error" role="alert" data-testid="experiment-error">{error}</p>}
    <div className="experiment-operations__grid">
      <section className="experiment-card" aria-labelledby="experiment-setup-heading">
        <h2 id="experiment-setup-heading">1. Source and microphone array</h2>
        <fieldset disabled={busy || running}>
          <label>Experiment name<input value={configuration.name} onChange={(event) => changed({ name: event.target.value })} /></label>
          <label>Source mode<select data-testid="experiment-source-mode" value={configuration.sourceMode}
            onChange={(event) => changed({ sourceMode: event.target.value as ExperimentConfiguration['sourceMode'] })}>
            <option value="SIMULATION">Simulation</option><option value="REPLAY">Recording replay</option><option value="LIVE">Live acquisition</option>
          </select></label>
          <label>Microphone-array profile<select data-testid="experiment-profile" value={configuration.profileId} onChange={(event) => {
            const selected = catalogue?.profiles.find((item) => item.id === event.target.value);
            changed({ profileId: event.target.value }); setDefinition(selected?.definition ?? '');
          }}>{catalogue?.profiles.map((item) => <option key={item.id} value={item.id}>{item.name} · {item.channels} channels</option>)}</select></label>
          {configuration.sourceMode === 'SIMULATION' && <label>Deterministic scenario<select value={configuration.scenario} onChange={(event) => changed({ scenario: event.target.value })}>
            {catalogue?.scenarios.map((name) => <option key={name}>{name}</option>)}</select></label>}
          {configuration.sourceMode === 'REPLAY' && <>
            <label>Recording input<select data-testid="experiment-replay-input" value={configuration.recordingId} onChange={(event) => changed({ recordingId: event.target.value })}>
              <option value="">Select a recording</option>{catalogue?.recordings.map((item) => <option key={item.id} value={item.id}>{item.name} · {item.integrity} · {item.frames} frames</option>)}</select></label>
            <label>Import recording (.aarec or legacy .aar)<input type="file" accept=".aarec,.aar,.partial" onChange={(event) => {
              const file = event.target.files?.[0]; if (file) void command(() => upload(file));
            }} /></label>
            <button type="button" disabled={!configuration.recordingId} onClick={() => void command(async () => {
              const recovered = await postJson<{ id: string }>(`${API}/recover`, { recordingId: configuration.recordingId });
              await loadCatalogue(); changed({ recordingId: recovered.id });
            })}>Recover incomplete input into a new file</button>
          </>}
          {configuration.sourceMode === 'LIVE' && <div className="experiment-hardware" data-testid="experiment-live-hardware">
            <label>Capture device on application host<select value={selectedDevice} onChange={(event) => setSelectedDevice(event.target.value)}>
              <option value="">Select a discovered device</option>{catalogue?.devices.map((device) => <option key={device.deviceId} value={device.deviceId}>{device.name} · {device.vendor}</option>)}</select></label>
            <button type="button" disabled={!selectedDevice} onClick={bindDevice}>Bind device to profile</button>
            <p>{catalogue?.devices.length ? 'Device access and format support are checked on the host. Binding a device invalidates old calibration.'
              : 'No input-capable capture device is available on this host. Live acquisition remains blocked until a real device and measured calibration are configured.'}</p>
          </div>}

          <details id="experiment-profile-editor" className="experiment-profile-editor" data-testid="experiment-profile-editor">
            <summary>Edit geometry, device format and calibration {dirtyProfile ? '· unsaved changes' : ''}</summary>
            <label>Profile identity<input value={values['profile.id'] ?? ''} onChange={(event) => editDefinition(editProfileDefinition(definition, { 'profile.id': event.target.value }, false))} /></label>
            <label>Profile name<input data-testid="experiment-profile-name" value={values['profile.name'] ?? ''} onChange={(event) => editDefinition(editProfileDefinition(definition, { 'profile.name': event.target.value }, false))} /></label>
            <label>Layout<select value={values['profile.layout'] ?? 'CUSTOM'} onChange={(event) => editDefinition(editProfileDefinition(definition, { 'profile.layout': event.target.value }, true))}>
              <option>STEREO_PAIR</option><option>LINEAR</option><option>RECTANGULAR</option><option>CUSTOM</option></select></label>
            <label>Microphone channels<input type="number" min="2" max="64" value={channels} onChange={(event) => {
              const count = Number(event.target.value);
              if (count >= 2 && count <= 64) editDefinition(resizeProfileDefinition(definition, count));
            }} /></label>
            <table><caption>Channel map and measured positions (metres)</caption><thead><tr><th>Channel</th><th>Microphone</th><th>X</th><th>Y</th></tr></thead><tbody>
              {microphones.map((microphone, index) => <tr key={index}><td>{index}</td><td><input aria-label={`Channel ${index} microphone identity`}
                value={microphone.id} onChange={(event) => editDefinition(editProfileDefinition(definition, { [`array.microphone.${index}.id`]: event.target.value }, true))} /></td>
                {(['x', 'y'] as const).map((axis) => <td key={axis}><input type="number" step="0.01" aria-label={`Channel ${index} ${axis} metres`}
                  value={values[`array.microphone.${index}.${axis}`] ?? '0'} onChange={(event) => editDefinition(editProfileDefinition(definition, { [`array.microphone.${index}.${axis}`]: event.target.value }, true))} /></td>)}</tr>)}
            </tbody></table>
            {values['capture.present'] === 'true' && <div className="experiment-form-row">
              <label>Sample rate (Hz)<input type="number" min="1" value={values['capture.sampleRate'] ?? '48000'} onChange={(event) => editDefinition(editProfileDefinition(definition, { 'capture.sampleRate': event.target.value }, true))} /></label>
              <label>PCM sample bits<select value={values['capture.sampleBits'] ?? '16'} onChange={(event) => editDefinition(editProfileDefinition(definition, { 'capture.sampleBits': event.target.value }, true))}><option>16</option><option>24</option><option>32</option></select></label>
            </div>}
            <h3>Measured timing calibration</h3><p>Enter measurements for this exact channel mapping. Channel 0 is the reference; offsets are measured at frame 0. No calibration is invented by the application.</p>
            <label>Calibration identifier<input value={calibrationId} onChange={(event) => setCalibrationId(event.target.value)} /></label>
            <div className="experiment-form-row"><label>Measured at (UTC)<input type="datetime-local" value={calibratedAt} onChange={(event) => setCalibratedAt(event.target.value)} /></label>
              <label>Valid until (UTC)<input type="datetime-local" value={validUntil} onChange={(event) => setValidUntil(event.target.value)} /></label></div>
            <label>One measured CSV row per channel: channel, offset samples, drift ppm, residual RMS, jitter RMS, gain, inverted polarity<textarea rows={4}
              placeholder="Use your measured values; polarity is true or false." value={calibrationRows} onChange={(event) => setCalibrationRows(event.target.value)} /></label>
            <button type="button" disabled={!calibrationRows || !calibrationId || !calibratedAt || !validUntil} onClick={() => {
              try { editDefinition(applyMeasuredCalibration(definition, calibrationId, `${calibratedAt}Z`, `${validUntil}Z`, calibrationRows)); setError(null); }
              catch (failure) { setError(failureText(failure)); }
            }}>Apply measured calibration to draft</button>
            <details><summary>Complete portable profile definition</summary><label>Profile definition<textarea rows={12} spellCheck={false}
              data-testid="experiment-profile-definition" value={definition} onChange={(event) => editDefinition(event.target.value)} /></label>
              <p>Advanced geometry/channel-count changes require matching capture and calibration fields. Save validates every channel and value.</p></details>
            <button type="button" disabled={!dirtyProfile} onClick={() => void command(saveProfile)} data-testid="experiment-save-profile">Validate &amp; save profile</button>
          </details>
          <h2>2. Output and storage</h2>
          <div className="experiment-form-row"><label>Intended duration (seconds)<input data-testid="experiment-duration" type="number" min="1" max="3600"
            value={configuration.durationSeconds} onChange={(event) => changed({ durationSeconds: Number(event.target.value) })} /></label>
            <label>Recording filename<input value={configuration.outputFilename} onChange={(event) => changed({ outputFilename: event.target.value })} /></label></div>
          <label className="experiment-checkbox"><input type="checkbox" checked={configuration.recordOutput} onChange={(event) => changed({ recordOutput: event.target.checked })} />Record every consumed audio block</label>
          <div className="experiment-actions"><button type="button" data-testid="experiment-preflight" disabled={dirtyProfile || !configuration.profileId}
            onClick={() => void command(inspect)}>Check experiment readiness</button>
            <button type="button" className="experiment-primary" data-testid="experiment-start" disabled={!startAllowed} onClick={() => void command(start)}>Start experiment</button></div>
          {dirtyProfile && <p className="experiment-warning">Save the edited profile before preflight.</p>}
          {storageNeedsAcknowledgement && <label className="experiment-checkbox"><input type="checkbox" checked={storageAcknowledged} onChange={(event) => setStorageAcknowledged(event.target.checked)} />I acknowledge the displayed storage margin warning.</label>}
        </fieldset>
      </section>

      <div className="experiment-results">
        <section className="experiment-card" aria-labelledby="experiment-readiness-heading" data-testid="experiment-readiness">
          <h2 id="experiment-readiness-heading">Readiness and health</h2>
          <p>{health?.experimentName ?? configuration.name} · {health?.stage ?? 'Run a preflight to check hardware, calibration and storage.'}</p>
          <p className="experiment-help">Standalone microphone run: readiness covers this saved array profile, source and localization pipeline.
            The open experiment document and editable workflow are checked separately in the <a href="/workbench">workflow workbench</a>.</p>
          {!reviewed && !running && <p className="experiment-help">Start requires a fresh preflight of the saved profile and current source choices.</p>}
          <ul className="experiment-findings">{health?.findings.filter((finding) => finding.severity !== 'INFO' || finding.code.endsWith('.stale')).map((finding) => <li
            key={finding.code} className={`experiment-finding--${finding.severity.toLowerCase()}`} data-testid={`finding-${finding.code}`}>
            <strong>{finding.severity === 'BLOCKING' || finding.severity === 'ERROR' ? '⛔' : '⚠'} {finding.message}</strong>
            <details><summary>{finding.code} · observed <time data-screenshot-mask="true">{new Date(finding.observedAt).toLocaleTimeString()}</time></summary><p>{finding.detail}</p></details>
            {finding.severity === 'BLOCKING' && <a href="#experiment-profile-editor" onClick={() => document.getElementById('experiment-profile-editor')?.setAttribute('open', '')}>Review source / profile settings</a>}
            {finding.acknowledgeable && !finding.acknowledged && run?.runId && !preflight && <button type="button" onClick={() => void command(async () => {
              setRun(await postJson<ExperimentRun>(`${API}/acknowledge`, { code: finding.code }));
            })}>Acknowledge</button>}{finding.acknowledged && <small>Acknowledged · condition/history retained</small>}
          </li>)}</ul>
          {health?.state === 'READY' && <p className="experiment-ready">✓ Source, profile and recording destination passed the requested preflight.</p>}
          <dl className="experiment-metrics">
            <div><dt>Source format</dt><dd>{health?.facts['source.format'] ?? 'Not checked'}</dd></div>
            <div><dt>Calibration</dt><dd>{health?.facts['calibration.status'] ?? 'Not checked'}</dd></div>
            <div><dt>Timing budget</dt><dd>{health?.facts['calibration.budgetPercent'] ? `${health.facts['calibration.budgetPercent']}% of 1 sample` : 'Nominal shared clock for simulation/replay'}</dd></div>
            <div><dt>Expires</dt><dd>{health?.facts['calibration.validUntil'] ?? 'Not applicable / not supplied'}</dd></div>
            <div><dt>Usable storage</dt><dd data-screenshot-mask="true">{formatCapacity(storage?.usableBytes ?? -1)}</dd></div>
            <div><dt>Safe duration</dt><dd data-screenshot-mask="true">{formatSafeDuration(storage?.estimatedSafeSecondsRemaining ?? -1)}</dd></div>
            <div><dt>Last capacity check</dt><dd data-screenshot-mask="true">{storage?.lastSuccessfulCheck ? new Date(storage.lastSuccessfulCheck).toLocaleTimeString() : 'Not checked'}</dd></div>
            <div><dt>Algorithm / version</dt><dd>{health?.facts['plugin'] ?? 'Not checked'} {health?.facts['plugin.version'] ?? ''}</dd></div>
          </dl>
          {storage && <p className="experiment-help">Destination: <span data-screenshot-mask="true">{storage.destination}</span>. Free space is an advisory estimate; runtime checks and write failures remain monitored.</p>}
        </section>

        <section className="experiment-card" aria-labelledby="experiment-localization-heading">
          <h2 id="experiment-localization-heading">3. Localization and runtime</h2>
          <svg viewBox="0 0 600 340" role="img" aria-label="Microphone positions and latest localization tracks" className="experiment-map">
            <rect x="40" y="40" width="520" height="260" rx="4" fill="#151b2b" stroke="#495773" />
            {microphones.map((microphone, index) => <g key={index}><circle cx={plotX(microphone.x)} cy={plotY(microphone.y)} r="6" fill="#89b4fa" />
              <text x={plotX(microphone.x) + 10} y={plotY(microphone.y) - 9} fill="#cdd6f4" fontSize="13">{microphone.id}</text></g>)}
            {run?.tracks.map((track) => <g key={track.id}><circle cx={plotX(track.x)} cy={plotY(track.y)} r="9" fill="#a6e3a1" />
              <text x={plotX(track.x) + 12} y={plotY(track.y) + 5} fill="#a6e3a1" fontSize="13">Track {track.id} · {Math.round(track.confidence * 100)}%</text></g>)}
            <text x="40" y="328" fill="#bac2de" fontSize="13">Microphones (blue) · Estimated sources (green) · Coordinates in metres</text>
          </svg>
          <dl className="experiment-metrics"><div><dt>Processed frames</dt><dd>{run?.health.facts['processed.frames'] ?? '0'}</dd></div>
            <div><dt>Processed blocks</dt><dd>{run?.health.facts['processed.blocks'] ?? '0'}</dd></div>
            <div><dt>Recording queue</dt><dd>{recording ? `${recording.queueDepth}/${recording.queueCapacity}` : 'Not recording'}</dd></div>
            <div><dt>Dropped / gaps</dt><dd>{recording ? `${recording.droppedBlocks} / ${recording.continuityGapCount}` : '—'}</dd></div>
            <div><dt>Processing / budget</dt><dd data-screenshot-mask="true">{Number(run?.health.facts['processing.lastNanos'] ?? 0).toLocaleString()} / {Number(run?.health.facts['processing.budgetNanos'] ?? 0).toLocaleString()} ns</dd></div>
            <div><dt>Written / received</dt><dd>{recording ? `${recording.writtenFrames}/${recording.receivedFrames} frames` : '—'}</dd></div></dl>
          <p className="experiment-help">Pause is unavailable for continuous synchronized capture. Stop ends acquisition and finalizes captured samples; stopping early keeps the experiment incomplete.</p>
        </section>

        <section className="experiment-card" aria-labelledby="experiment-evidence-heading" data-testid="experiment-evidence">
          <h2 id="experiment-evidence-heading">4. Validation and evidence</h2>
          <p><strong>{run?.exportAvailable ? run.health.facts['evidence.complete'] === 'true' ? '✓ Complete experiment evidence' : '⚠ Incomplete experiment evidence' : 'Evidence is finalized after the run.'}</strong></p>
          <p>Benchmark: {run?.health.facts['benchmark.status'] ?? 'Pending'}</p>
          {run?.health.facts['recording.sha256'] && <p className="experiment-hash">Recording file SHA-256: <span data-screenshot-mask="true">{run.health.facts['recording.sha256']}</span></p>}
          <div className="experiment-actions">{run?.exportAvailable && <a className="experiment-link-button" data-testid="experiment-export" href={`${API}/evidence`}>
            {run.health.facts['evidence.complete'] === 'true' ? 'Export complete evidence' : 'Export incomplete evidence'}</a>}
            <button type="button" onClick={() => downloadDiagnostic({ configuration, preflight, run })}>Export diagnostic snapshot</button>
            <button type="button" disabled={running} onClick={() => changed({ name: `${configuration.name} — copy` })}>Clone experiment</button></div>
          <details><summary>Readiness facts, warning timeline and acknowledgement history</summary>
            <dl className="experiment-facts">{Object.entries(health?.facts ?? {}).sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{value}</dd></div>)}</dl>
            <ol>{health?.history.map((event, index) => <li key={index}>{event.observedAt} · {event.action} · {event.detail}</li>)}</ol>
          </details>
          <details><summary>Recorded inputs and replay history</summary><ul>{catalogue?.recordings.map((item) => <li key={item.id}>{item.id} · {item.integrity} · {item.frames} frames
            <button type="button" disabled={running} onClick={() => changed({ sourceMode: 'REPLAY', recordingId: item.id })}>Use recording for replay</button></li>)}</ul></details>
        </section>
      </div>
    </div>
  </main>;
}
