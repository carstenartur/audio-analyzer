import { useEffect, useState } from 'react';
import { exportExperimentEvidence, inspectExperimentBindings, type ExperimentBindingRequest, type ExperimentDocumentDiagnostic, type ExperimentDocumentPreviewResponse } from './experimentDocumentApi';

interface PortableView {
  assets: Array<{ id: string; relativePath: string; mediaType: string; sizeBytes: number; sha256: string }>;
  outputs: Array<{ id: string; baseName: string; mediaType: string }>;
  profiles: { capture?: { channels: number; sampleRateHz: number; sampleSizeBits: number }; calibration?: { validUntil: string } };
}

/** This view displays host-validated canonical output; validation remains in the shared service. */
export function ExperimentDocumentBindings({ preview }: { preview: ExperimentDocumentPreviewResponse }) {
  const view = JSON.parse(preview.canonicalDocument) as PortableView;
  const [assets, setAssets] = useState<Record<string, string>>({});
  const [types, setTypes] = useState<Record<string, string>>({});
  const [output, setOutput] = useState('');
  const [devices, setDevices] = useState<Array<{ deviceId: string; name: string }>>([]);
  const [deviceId, setDeviceId] = useState('');
  const [channels, setChannels] = useState(view.profiles.capture?.channels ?? 1);
  const [mapping, setMapping] = useState(Array.from({ length: view.profiles.capture?.channels ?? 0 }, (_, index) => index).join(','));
  const [synchronized, setSynchronized] = useState(false);
  const [calibration, setCalibration] = useState(false);
  const [diagnostics, setDiagnostics] = useState<ExperimentDocumentDiagnostic[]>(preview.localRequirements);
  const [status, setStatus] = useState('Local resources have not been verified');
  const [controller, setController] = useState<AbortController | null>(null);

  useEffect(() => {
    const abort = new AbortController();
    void fetch('/experiment-operations/devices', { signal: abort.signal }).then(async (response) => {
      if (response.ok) setDevices((await response.json()) as Array<{ deviceId: string; name: string }>);
    }).catch(() => { /* Hardware is optional for inspection. */ });
    return () => abort.abort();
  }, []);

  const request = (): ExperimentBindingRequest => ({
    document: preview.canonicalDocument, assetPaths: assets, confirmedMediaTypes: types,
    outputDirectory: output, deviceId, deviceChannels: channels,
    channelMapping: mapping.trim() === '' ? [] : mapping.split(',').map((value) => Number(value.trim())),
    synchronizationConfirmed: synchronized, calibrationConfirmed: calibration,
  });

  const inspect = async (evidence: boolean) => {
    const abort = new AbortController(); setController(abort); setStatus(evidence ? 'Preparing evidence package…' : 'Verifying selected resources…');
    try {
      if (evidence) {
        const blob = await exportExperimentEvidence(request(), abort.signal);
        const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = 'experiment-evidence.zip'; link.click();
        window.setTimeout(() => URL.revokeObjectURL(url), 1000); setStatus('Evidence package downloaded');
      } else {
        const result = await inspectExperimentBindings(request(), abort.signal); setDiagnostics(result.diagnostics);
        setStatus(result.ready ? 'Selected resources satisfy this setup; no device was opened' : 'Inspection is available; resolve the diagnostics before execution');
      }
    } catch (failure) { setStatus(abort.signal.aborted ? 'Resource inspection cancelled' : failure instanceof Error ? failure.message : String(failure)); }
    finally { setController(null); }
  };

  return <details className="experiment-document__bindings">
    <summary>Local bindings and evidence package</summary>
    <p className="help-text">Paths refer to the machine running Audio Analyzer and must be inside directories approved by its owner. If access is disabled, ask the host owner to configure document resource directories or use the desktop file chooser. Local paths and device identities stay outside the portable document.</p>
    {view.assets.map((asset) => <fieldset key={asset.id} disabled={controller !== null}>
      <legend>{asset.id}: {asset.relativePath}</legend>
      <p>{asset.mediaType} · {asset.sizeBytes} bytes</p><code>{asset.sha256}</code>
      <label className="field">Selected local file path<input value={assets[asset.id] ?? ''} onChange={(event) => setAssets({ ...assets, [asset.id]: event.target.value })} /></label>
      <label><input type="checkbox" checked={types[asset.id] === asset.mediaType} onChange={(event) => setTypes({ ...types, [asset.id]: event.target.checked ? asset.mediaType : '' })} />I confirm the media type if the format cannot be recognized from its content</label>
    </fieldset>)}
    {view.outputs.length === 0 ? null : <><h4>Requested outputs</h4><ul>{view.outputs.map((item) => <li key={item.id}>{item.id}: {item.baseName} ({item.mediaType})</li>)}</ul><label className="field">Local output directory<input disabled={controller !== null} value={output} onChange={(event) => setOutput(event.target.value)} /></label></>}
    {!view.profiles.capture ? null : <fieldset disabled={controller !== null}>
      <legend>Capture capability and channel mapping</legend>
      <p>Required PCM: {view.profiles.capture.sampleRateHz} Hz, {view.profiles.capture.channels} channels, {view.profiles.capture.sampleSizeBits} bits.</p>
      <label className="field">Local device<select value={deviceId} onChange={(event) => setDeviceId(event.target.value)}><option value="">No device — inspect or use a separate simulation setup</option>{devices.map((device) => <option key={device.deviceId} value={device.deviceId}>{device.name}</option>)}</select></label>
      <label className="field">Physical channels to capture<input type="number" min={1} max={256} value={channels} onChange={(event) => setChannels(Number(event.target.value))} /></label>
      <label className="field">Logical-to-physical channel indices (zero based)<input value={mapping} onChange={(event) => setMapping(event.target.value)} /></label>
      <label><input type="checkbox" checked={synchronized} onChange={(event) => setSynchronized(event.target.checked)} />I verified synchronized channels for this device</label>
      {view.profiles.calibration ? <><p>Calibration valid until {view.profiles.calibration.validUntil}</p><label><input type="checkbox" checked={calibration} onChange={(event) => setCalibration(event.target.checked)} />This calibration applies to the selected device and channel mapping</label></> : null}
    </fieldset>}
    <div className="experiment-document__actions"><button type="button" disabled={controller !== null} onClick={() => void inspect(false)}>Verify local bindings</button><button type="button" disabled={controller !== null} onClick={() => void inspect(true)}>Export evidence package</button>{controller ? <button type="button" onClick={() => controller.abort()}>Cancel</button> : null}</div>
    <p aria-live="polite">{status}</p>
    <ul>{diagnostics.map((item, index) => <li key={`${item.pointer}:${item.code}:${index}`}><strong>{item.severity}</strong> {item.pointer}: {item.message}</li>)}</ul>
  </details>;
}
