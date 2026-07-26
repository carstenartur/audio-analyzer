import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';

import { ExperimentDocumentPanel } from './ExperimentDocumentPanel';

/** Mounts the document panel into the established left workbench sidebar without duplicating state. */
export function ExperimentDocumentPanelPortal() {
  const [host, setHost] = useState<HTMLDivElement | null>(null);

  useEffect(() => {
    const sidebar = document.querySelector<HTMLElement>('[data-testid="node-palette"]');
    if (sidebar === null) {
      return undefined;
    }
    const container = document.createElement('div');
    container.dataset.testid = 'experiment-document-panel-host';
    const firstHeading = sidebar.querySelector('h2');
    sidebar.insertBefore(container, firstHeading);
    setHost(container);
    return () => {
      setHost(null);
      container.remove();
    };
  }, []);

  return host === null ? null : createPortal(<ExperimentDocumentPanel />, host);
}
