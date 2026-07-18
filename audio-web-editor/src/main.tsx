import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import WorkflowEditorApp from './WorkflowEditorApp';
import './styles.css';

const rootElement = document.getElementById('root');
if (rootElement === null) {
  throw new Error('Missing #root application mount point');
}

createRoot(rootElement).render(
  <StrictMode>
    <WorkflowEditorApp />
  </StrictMode>,
);
