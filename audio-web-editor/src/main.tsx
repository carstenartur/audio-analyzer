import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { IndexedWorkflowHistoryPanel } from './IndexedWorkflowHistoryPanel';
import WorkflowEditorApp from './WorkflowEditorApp';
import { ExperimentOperationsPanel } from './ExperimentOperationsPanel';
import { WorkflowMergePanel } from './WorkflowMergePanel';
import './styles.css';
import './experiment-document.css';
import './indexed-history.css';
import './workflow-run.css';
import './workflow-merge.css';

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

protectServerOwnedNodeDeletion();

const rootElement = document.getElementById('root');
if (rootElement === null) {
  throw new Error('Missing #root application mount point');
}

createRoot(rootElement).render(
  <StrictMode>
    {window.location.pathname === '/workbench/experiment' ? <ExperimentOperationsPanel /> : <>
      <WorkflowEditorApp />
      <IndexedWorkflowHistoryPanel />
      <WorkflowMergePanel />
    </>}
  </StrictMode>,
);
