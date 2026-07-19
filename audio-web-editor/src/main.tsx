import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { IndexedWorkflowHistoryPanel } from './IndexedWorkflowHistoryPanel';
import WorkflowEditorApp from './WorkflowEditorApp';
import './styles.css';

const ACTOR_STORAGE_KEY = 'audio-analyzer.workflow.actor';

function seedTabActorIdentity(): void {
  try {
    if (sessionStorage.getItem(ACTOR_STORAGE_KEY) !== null) {
      return;
    }
    const suffix = crypto.randomUUID().slice(0, 8);
    sessionStorage.setItem(
      ACTOR_STORAGE_KEY,
      JSON.stringify({
        actorId: `actor-${suffix}`,
        userId: `user-${suffix}`,
        displayName: `Browser ${suffix}`,
      }),
    );
  } catch {
    // The session hook still creates an in-memory identity when browser storage is unavailable.
  }
}

function protectServerOwnedNodeDeletion(): void {
  document.addEventListener(
    'keydown',
    (event) => {
      if (event.key !== 'Delete' && event.key !== 'Backspace') {
        return;
      }
      const target = event.target;
      if (
        target instanceof HTMLElement &&
        (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))
      ) {
        return;
      }
      if (document.querySelector('.react-flow__node.selected') === null) {
        return;
      }
      event.preventDefault();
      event.stopImmediatePropagation();
    },
    { capture: true },
  );
}

seedTabActorIdentity();
protectServerOwnedNodeDeletion();

const rootElement = document.getElementById('root');
if (rootElement === null) {
  throw new Error('Missing #root application mount point');
}

createRoot(rootElement).render(
  <StrictMode>
    <WorkflowEditorApp />
    <IndexedWorkflowHistoryPanel />
  </StrictMode>,
);
