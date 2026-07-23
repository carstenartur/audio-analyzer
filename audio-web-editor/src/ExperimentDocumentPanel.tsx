import { useState } from 'react';

import {
  EXPERIMENT_DOCUMENT_MEDIA_TYPE,
  ExperimentDocumentApiError,
  normalizeExperimentDocument,
  previewExperimentDocument,
  type ExperimentDocumentPreviewResponse,
} from './experimentDocumentApi';

interface ExperimentDocumentPanelProps {
  onError: (message: string | null) => void;
  onStatus: (message: string) => void;
}

function failureMessage(failure: unknown): string {
  if (failure instanceof ExperimentDocumentApiError) {
    const location = failure.pointer === null ? '' : ` at ${failure.pointer}`;
    const code = failure.code === null ? '' : ` [${failure.code}]`;
    return `${failure.message}${location}${code}`;
  }
  return failure instanceof Error ? failure.message : String(failure);
}

function download(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}

/** Safe upload, preview and canonical-download surface. It never applies or executes a document. */
export function ExperimentDocumentPanel({ onError, onStatus }: ExperimentDocumentPanelProps) {
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<ExperimentDocumentPreviewResponse | null>(null);
  const [busy, setBusy] = useState(false);

  const selectFile = (selected: File | undefined) => {
    setFile(selected ?? null);
    setPreview(null);
    onError(null);
    if (selected === undefined) {
      onStatus('No portable experiment document selected');
    } else {
      onStatus(`Selected experiment document: ${selected.name}`);
    }
  };

  const inspect = async () => {
    if (file === null) {
      onError('Choose an .audioexp document before previewing');
      return;
    }
    setBusy(true);
    onError(null);
    try {
      const result = await previewExperimentDocument(file);
      setPreview(result);
      onStatus(
        `Previewed ${result.experimentName}: ${result.nodeCount} nodes, ${result.edgeCount} edges`,
      );
    } catch (failure) {
      setPreview(null);
      onError(failureMessage(failure));
    } finally {
      setBusy(false);
    }
  };

  const normalize = async () => {
    if (file === null || preview === null) {
      onError('Preview the experiment document before downloading a normalized copy');
      return;
    }
    setBusy(true);
    onError(null);
    try {
      const normalized = await normalizeExperimentDocument(file);
      download(normalized.blob, normalized.filename);
      onStatus(`Downloaded canonical copy of ${preview.experimentName}`);
    } catch (failure) {
      onError(failureMessage(failure));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="experiment-document" data-testid="experiment-document-panel">
      <h2>Experiment document</h2>
      <p className="help-text">
        Preview and normalize a portable setup. Opening never applies or executes it.
      </p>
      <label className="field">
        Portable setup
        <input
          accept={`.${'audioexp'},${EXPERIMENT_DOCUMENT_MEDIA_TYPE},application/json`}
          data-testid="experiment-document-file"
          disabled={busy}
          onChange={(event) => selectFile(event.target.files?.[0])}
          type="file"
        />
      </label>
      <button
        className="action-button"
        data-testid="experiment-document-preview"
        disabled={busy || file === null}
        onClick={() => void inspect()}
        type="button"
      >
        {busy ? 'Working…' : 'Preview document'}
      </button>
      <button
        className="action-button"
        data-testid="experiment-document-normalize"
        disabled={busy || file === null || preview === null}
        onClick={() => void normalize()}
        type="button"
      >
        Download normalized copy
      </button>

      {preview === null ? null : (
        <div className="experiment-document__preview" data-testid="experiment-document-preview-result">
          <dl className="experiment-document__facts">
            <div>
              <dt>Experiment</dt>
              <dd>{preview.experimentName}</dd>
            </div>
            <div>
              <dt>Source mode</dt>
              <dd>{preview.sourceMode}</dd>
            </div>
            <div>
              <dt>Workflow</dt>
              <dd>
                {preview.workflowName} ({preview.nodeCount} nodes, {preview.edgeCount} edges)
              </dd>
            </div>
            <div>
              <dt>SHA-256</dt>
              <dd className="experiment-document__hash">{preview.canonicalSha256}</dd>
            </div>
            <div>
              <dt>Execution</dt>
              <dd>{preview.executionAllowed ? 'available' : 'blocked'}</dd>
            </div>
            <div>
              <dt>Mode</dt>
              <dd>{preview.readOnly ? 'read-only inspection' : 'compatible'}</dd>
            </div>
          </dl>

          {preview.migrations.length === 0 ? null : (
            <>
              <h3>Migrations</h3>
              <ul className="experiment-document__list">
                {preview.migrations.map((migration) => (
                  <li key={migration}>{migration}</li>
                ))}
              </ul>
            </>
          )}

          {preview.diagnostics.length === 0 ? (
            <p className="help-text">No document diagnostics.</p>
          ) : (
            <>
              <h3>Diagnostics</h3>
              <ul className="experiment-document__list">
                {preview.diagnostics.map((diagnostic) => (
                  <li
                    className={`experiment-document__diagnostic experiment-document__diagnostic--${diagnostic.severity.toLowerCase()}`}
                    key={`${diagnostic.pointer}:${diagnostic.code}`}
                  >
                    <strong>{diagnostic.severity}</strong> {diagnostic.pointer} {diagnostic.code}:{' '}
                    {diagnostic.message}
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
    </section>
  );
}
