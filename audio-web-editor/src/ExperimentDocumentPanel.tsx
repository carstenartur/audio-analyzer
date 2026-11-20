import { useEffect, useRef, useState } from 'react';
import { ExperimentDocumentBindings } from './ExperimentDocumentBindings';
import {
  EXPERIMENT_DOCUMENT_EXTENSION, EXPERIMENT_DOCUMENT_MEDIA_TYPE, ExperimentDocumentApiError,
  acknowledgeDocumentSaved, applyExperimentDocument, documentSessionStatus, downloadCurrentDocument,
  getExperimentCopy, importExperimentCopy, listExperimentCopies, normalizeExperimentDocument, previewExperimentDocument,
  type ExperimentDocumentCopy, type ExperimentDocumentPreviewResponse, type ExperimentDocumentSessionStatus,
} from './experimentDocumentApi';

interface ExperimentDocumentPanelProps { onError?: (message: string | null) => void; onStatus?: (message: string) => void }
interface PendingDocumentSave {
  hash: string;
  filename: string;
  nextOpen?: { file: File; preview: ExperimentDocumentPreviewResponse };
}

function failureMessage(failure: unknown): string {
  if (failure instanceof ExperimentDocumentApiError) {
    return `${failure.message}${failure.pointer ? ` at ${failure.pointer}` : ''}${failure.code ? ` [${failure.code}]` : ''}`;
  }
  return failure instanceof Error ? failure.message : String(failure);
}

function download(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob); const anchor = document.createElement('a');
  anchor.href = url; anchor.download = filename; anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Explicit preview, open, independent import, Save/Save As and local resource binding. */
export function ExperimentDocumentPanel({ onError, onStatus }: ExperimentDocumentPanelProps = {}) {
  const input = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<ExperimentDocumentPreviewResponse | null>(null);
  const [controller, setController] = useState<AbortController | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [cancellable, setCancellable] = useState(true);
  const [status, setStatus] = useState('No portable experiment document selected');
  const [error, setError] = useState<string | null>(null);
  const [session, setSession] = useState<ExperimentDocumentSessionStatus | null>(null);
  const [copies, setCopies] = useState<ExperimentDocumentCopy[]>([]);
  const [recent, setRecent] = useState<string[]>([]);
  const [pendingSave, setPendingSave] = useState<PendingDocumentSave | null>(null);
  const busy = controller !== null || pendingSave !== null;

  const reportStatus = (message: string) => { setStatus(message); onStatus?.(message); };
  const reportError = (message: string | null) => { setError(message); onError?.(message); };
  const refresh = async () => {
    const [nextSession, nextCopies] = await Promise.all([documentSessionStatus(), listExperimentCopies()]);
    setSession(nextSession); setCopies(nextCopies);
  };
  useEffect(() => { void refresh().catch(() => { /* The panel remains usable if session metadata is unavailable. */ }); }, []);

  const selectFile = (selected: File | undefined) => {
    setFile(selected ?? null); setPreview(null); setConfirming(false); reportError(null);
    reportStatus(selected ? `Selected experiment document: ${selected.name}` : 'No portable experiment document selected');
    if (selected) setRecent((names) => [selected.name, ...names.filter((name) => name !== selected.name)].slice(0, 10));
  };

  const run = async (operation: (signal: AbortSignal) => Promise<void>, canCancel = true) => {
    const abort = new AbortController(); setController(abort); setCancellable(canCancel); reportError(null);
    try { await operation(abort.signal); }
    catch (failure) { if (abort.signal.aborted) reportStatus('Document operation cancelled'); else reportError(failureMessage(failure)); }
    finally { setController(null); }
  };

  const inspect = async () => {
    if (!file) return;
    await run(async (signal) => {
      const result = await previewExperimentDocument(file, signal); setPreview(result); setConfirming(false);
      reportStatus(`Previewed ${result.experimentName}: ${result.nodeCount} nodes, ${result.edgeCount} edges`);
    });
  };

  const saveCurrent = async (nextOpen?: PendingDocumentSave['nextOpen']) => {
    const exported = await downloadCurrentDocument();
    const canonical = JSON.parse(await exported.blob.text()) as { provenance: { canonicalSha256: string } };
    download(exported.blob, exported.filename);
    setPendingSave({ hash: canonical.provenance.canonicalSha256, filename: exported.filename, nextOpen });
    reportStatus('Download started. Confirm the saved copy before marking the current document saved.');
  };

  const confirmSave = async () => {
    if (!pendingSave) return;
    const saved = pendingSave;
    await run(async (signal) => {
      await acknowledgeDocumentSaved(saved.hash);
      setPendingSave(null);
      await refresh();
      if (saved.nextOpen) {
        const next = saved.nextOpen;
        await applyExperimentDocument(next.file, next.preview.canonicalSha256, false, next.preview.comparison?.currentWorkflowSha256, signal);
        reportStatus('Opened the confirmed setup; no experiment was executed'); window.location.reload();
      } else {
        setPreview((previous) => previous?.comparison ? { ...previous, comparison: { ...previous.comparison, dirty: false } } : previous);
        reportStatus('Confirmed the saved copy of the complete current document');
      }
    }, false);
  };

  const normalize = async () => {
    if (!file || !preview) return;
    await run(async (signal) => {
      const result = await normalizeExperimentDocument(file, signal); download(result.blob, result.filename);
      reportStatus(`Downloaded canonical copy of ${preview.experimentName}`);
    });
  };

  const apply = async (decision: 'save' | 'discard' | 'open') => {
    if (!file || !preview) return;
    await run(async (signal) => {
      if (decision === 'save') { await saveCurrent({ file, preview }); return; }
      await applyExperimentDocument(file, preview.canonicalSha256, decision !== 'open', preview.comparison?.currentWorkflowSha256, signal);
      reportStatus('Opened the confirmed setup; no experiment was executed'); window.location.reload();
    }, false);
  };

  const importCopy = async () => {
    if (!file || !preview) return;
    await run(async (signal) => {
      await importExperimentCopy(file, preview.canonicalSha256, signal); await refresh();
      reportStatus('Imported an independent workspace copy; current edits were preserved');
    }, false);
  };

  useEffect(() => {
    const key = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || busy) return;
      if (event.key.toLowerCase() === 'o') { event.preventDefault(); input.current?.click(); }
      if (event.key.toLowerCase() === 's') { event.preventDefault(); if (event.shiftKey) void normalize(); else void run(async () => saveCurrent(), false); }
    };
    window.addEventListener('keydown', key); return () => window.removeEventListener('keydown', key);
  }, [busy, file, preview]);

  const canOpen = preview !== null && preview.executionAllowed && !preview.readOnly && file !== null && !busy;
  const dirty = preview?.comparison?.dirty ?? session?.dirty ?? false;

  return <details className="experiment-document" data-testid="experiment-document-panel">
    <summary>Experiment document</summary>
    <div className="experiment-document__content" onDragOver={(event) => event.preventDefault()} onDrop={(event) => { event.preventDefault(); if (!busy) selectFile(event.dataTransfer.files[0]); }}>
      <p className="help-text">Preview or drag a shared setup here. Open replaces the editable workflow after confirmation; Import copy preserves current edits. These actions never execute an experiment.</p>
      <p aria-live="polite">Current document: {session?.open ? (session.dirty ? 'unsaved changes' : 'saved snapshot') : 'no experiment document open'}{session?.lastSavedAt ? ` · last saved ${session.lastSavedAt}` : ''}</p>
      <label className="field">Portable setup<input ref={input} accept={`.${EXPERIMENT_DOCUMENT_EXTENSION},${EXPERIMENT_DOCUMENT_MEDIA_TYPE},application/json`} data-testid="experiment-document-file" disabled={busy} onChange={(event) => selectFile(event.target.files?.[0])} type="file" /></label>
      <div className="experiment-document__actions">
        <button data-testid="experiment-document-preview" disabled={busy || !file} onClick={() => void inspect()} type="button">Preview / Validate</button>
        <button disabled={busy} onClick={() => void run(async () => saveCurrent(), false)} type="button">Save current document</button>
        <button data-testid="experiment-document-normalize" disabled={busy || !file || !preview} onClick={() => void normalize()} type="button">Save As normalized copy</button>
        <button data-testid="experiment-document-import-copy" disabled={busy || !preview} onClick={() => void importCopy()} type="button">Import independent copy</button>
        <button data-testid="experiment-document-apply" disabled={!canOpen} onClick={() => setConfirming(true)} type="button">Open previewed document…</button>
      </div>
      {controller ? <div role="status"><progress aria-label="Document operation in progress" /><button type="button" disabled={!cancellable} onClick={() => controller?.abort()}>Cancel</button></div> : null}
      {pendingSave ? <fieldset data-testid="experiment-document-save-confirmation" disabled={controller !== null}><legend>Confirm the saved copy</legend><p>Confirm only after your browser has saved <strong>{pendingSave.filename}</strong>. If the download was blocked or cancelled, keep the current document open.</p><button type="button" data-testid="experiment-document-confirm-save" onClick={() => void confirmSave()}>{pendingSave.nextOpen ? 'I saved this copy — open the previewed document' : 'I saved this copy'}</button><button type="button" data-testid="experiment-document-cancel-save" onClick={() => { setPendingSave(null); setConfirming(false); reportStatus('Save confirmation cancelled; the current document and unsaved state were preserved'); }}>Cancel — keep current document</button></fieldset> : null}
      <p aria-live="polite" className="experiment-document__status" data-testid="document-status">{status}</p>
      {error ? <p aria-live="assertive" className="experiment-document__error" data-testid="document-error">{error}</p> : null}
      {recent.length ? <details><summary>Recent filenames</summary><ul>{recent.map((name) => <li key={name}>{name}</li>)}</ul><p>Select the file again to reopen it. Full local paths are not shared.</p></details> : null}
      {copies.length ? <details><summary>Independent workspace copies ({copies.length})</summary><ul>{copies.map((copy) => <li key={copy.id}>{copy.name} {copy.readOnly ? '(inspection)' : ''} <code>{copy.canonicalSha256}</code><button type="button" disabled={busy} onClick={() => void run(async (signal) => { const selected = await getExperimentCopy(copy.id); setFile(selected); const inspected = await previewExperimentDocument(selected, signal); setPreview(inspected); setConfirming(false); })}>Preview copy</button><button type="button" disabled={busy} onClick={() => void run(async () => { const selected = await getExperimentCopy(copy.id); download(selected, selected.name); })}>Download copy</button></li>)}</ul></details> : null}
      {!preview ? null : <div className="experiment-document__preview" data-testid="experiment-document-preview-result">
        <dl className="experiment-document__facts">
          <div><dt>Experiment</dt><dd>{preview.experimentName}</dd></div><div><dt>Format</dt><dd>{preview.format}, version {preview.formatVersion}</dd></div>
          <div><dt>Source mode</dt><dd>{preview.sourceMode}</dd></div><div><dt>Workflow</dt><dd>{preview.workflowName} ({preview.nodeCount} nodes, {preview.edgeCount} edges)</dd></div>
          <div><dt>SHA-256</dt><dd className="experiment-document__hash">{preview.canonicalSha256}</dd></div><div><dt>Execution compatibility</dt><dd>{preview.executionAllowed ? 'available' : 'blocked'}; local bindings must still be verified</dd></div><div><dt>Mode</dt><dd>{preview.readOnly ? 'read-only inspection' : 'compatible'}</dd></div>
        </dl>
        <h3>Changes to the current workflow</h3>
        {preview.comparison?.changes.length ? <ul data-testid="experiment-document-semantic-diff">{preview.comparison.changes.map((change, index) => <li key={index}><strong>{change.kind}: {change.targetId}</strong> {change.field}<details><summary>Before / After</summary><pre>{change.before ?? '(absent)'}</pre><pre>{change.after ?? '(absent)'}</pre></details></li>)}</ul> : <p>No semantic graph differences.</p>}
        {confirming ? <fieldset data-testid="experiment-document-confirmation" disabled={busy}><legend>Confirm replacement</legend><p>Open “{preview.experimentName}” using the changes above. This does not execute the experiment.</p>{dirty ? <><p>The current workflow has unsaved changes.</p><button type="button" data-testid="experiment-document-save-open" onClick={() => void apply('save')}>Save current copy and open</button><button type="button" data-testid="experiment-document-discard-open" onClick={() => void apply('discard')}>Discard changes and open</button></> : <button type="button" data-testid="experiment-document-confirm-open" onClick={() => void apply('open')}>Open document</button>}<button type="button" onClick={() => { setConfirming(false); reportStatus('Open cancelled; current state was preserved'); }}>Cancel</button></fieldset> : null}
        <details><summary>Complete setup, profiles, assets and provenance</summary><pre>{preview.canonicalDocument}</pre></details>
        <details><summary>Required and installed plugin sections</summary><h4>Required</h4><ul>{preview.requiredPlugins.map((item) => <li key={item.id}>{item.id} {item.versionRange}: {item.sections.join(', ')}</li>)}</ul><h4>Installed</h4><ul>{preview.installedSections.map((item) => <li key={`${item.pluginId}/${item.sectionId}`}>{item.pluginId} {item.packageVersion} / {item.sectionId}, schema {item.schemaVersion}, algorithm {item.algorithmVersion}<table><thead><tr><th>Parameter</th><th>Type</th><th>Required</th><th>Description</th></tr></thead><tbody>{item.parameters.map((parameter) => <tr key={parameter.pointer}><td>{parameter.pointer}</td><td>{parameter.type}</td><td>{String(parameter.required)}</td><td>{parameter.description}</td></tr>)}</tbody></table></li>)}</ul><h4>Section hashes</h4><ul>{Object.entries(preview.sectionHashes).map(([name, hash]) => <li key={name}>{name}: <code>{hash}</code></li>)}</ul></details>
        {preview.migrations.length ? <><h3>Migrations</h3><ul>{preview.migrations.map((migration) => <li key={migration}>{migration}</li>)}</ul></> : null}
        <h3>Diagnostics</h3>{preview.diagnostics.length ? <ul>{preview.diagnostics.map((item, index) => <li key={`${item.pointer}:${item.code}:${index}`}><strong>{item.severity}</strong> {item.pointer} {item.code}: {item.message}</li>)}</ul> : <p>No document diagnostics.</p>}
        <ExperimentDocumentBindings key={preview.canonicalSha256} preview={preview} />
      </div>}
    </div>
  </details>;
}
