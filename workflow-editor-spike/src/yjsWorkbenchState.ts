import * as Y from 'yjs';
import { Awareness } from 'y-protocols/awareness';

/**
 * Explicitly non-semantic Yjs state for issue #220.
 *
 * The document may contain presence, viewport and panel preferences only. Workflow nodes, edges,
 * properties and operation history remain server-authoritative and must never be inserted here.
 */
export class YjsWorkbenchState {
  readonly document = new Y.Doc();
  readonly awareness = new Awareness(this.document);

  private readonly localOrigin = Symbol('local-workbench-state');
  private readonly uiState = this.document.getMap<string | number>('workbench-ui');
  private readonly undoManager = new Y.UndoManager(this.uiState, {
    trackedOrigins: new Set([this.localOrigin]),
  });

  constructor(userId: string, displayName: string) {
    this.awareness.setLocalStateField('user', { userId, displayName });
  }

  setCursor(x: number, y: number): void {
    this.awareness.setLocalStateField('cursor', { x, y });
  }

  setViewport(x: number, y: number, zoom: number): void {
    this.document.transact(() => {
      this.uiState.set('viewport.x', x);
      this.uiState.set('viewport.y', y);
      this.uiState.set('viewport.zoom', zoom);
    }, this.localOrigin);
  }

  setSelectedPanel(panel: string): void {
    this.document.transact(() => this.uiState.set('selectedPanel', panel), this.localOrigin);
  }

  viewport(): { x: number; y: number; zoom: number } {
    return {
      x: Number(this.uiState.get('viewport.x') ?? 0),
      y: Number(this.uiState.get('viewport.y') ?? 0),
      zoom: Number(this.uiState.get('viewport.zoom') ?? 1),
    };
  }

  selectedPanel(): string | null {
    const value = this.uiState.get('selectedPanel');
    return typeof value === 'string' ? value : null;
  }

  undoUiState(): void {
    this.undoManager.undo();
  }

  redoUiState(): void {
    this.undoManager.redo();
  }

  destroy(): void {
    this.awareness.destroy();
    this.undoManager.destroy();
    this.document.destroy();
  }
}

/** Guard used by tests and adapters to document the canonical-state boundary. */
export const FORBIDDEN_CANONICAL_KEYS = Object.freeze([
  'workflow',
  'nodes',
  'edges',
  'operations',
  'dsl',
  'checkpoints',
]);
