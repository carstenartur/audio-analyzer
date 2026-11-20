import { ExperimentHealthView } from './ExperimentHealthView';
import { useExperimentHealth } from './useExperimentHealth';

/** The local document stays distinct from a collaborative workflow selected for execution. */
export function ExperimentDocumentHealthPanel() {
  const observed = useExperimentHealth('/workflow/experiment-health');
  return <ExperimentHealthView title="Document readiness" health={observed.displayHealth} error={observed.error} />;
}
