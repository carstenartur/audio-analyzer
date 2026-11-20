import { useEffect, useState } from 'react';
import { getJson } from './api';
import type { ExperimentHealth } from './experimentOperationsApi';
import { isFreshHealthObservation } from './workflowExperimentHealth.mjs';

interface Observation {
  key: string | null;
  health: ExperimentHealth | null;
  error: string | null;
  checkedAt: number;
  startedAt: number;
}

/** Discard old-source responses and clear readiness immediately when its exact query changes. */
export function useExperimentHealth(key: string | null) {
  const [observed, setObserved] = useState<Observation>({ key: null, health: null, error: null, checkedAt: 0, startedAt: 0 });
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const clock = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(clock);
  }, []);
  useEffect(() => {
    if (key === null) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout> | null = null;
    setObserved({ key, health: null, error: null, checkedAt: 0, startedAt: Date.now() });
    const poll = async () => {
      const requestStartedAt = Date.now();
      try {
        const health = await getJson<ExperimentHealth>(key);
        if (active) {
          setObserved({ key, health, error: null, checkedAt: requestStartedAt, startedAt: requestStartedAt });
          setNow(Date.now());
        }
      } catch (failure) {
        if (active) setObserved((previous) => ({ ...previous, error: failure instanceof Error ? failure.message : String(failure) }));
      }
      if (active) timer = setTimeout(() => void poll(), 1500);
    };
    void poll();
    return () => { active = false; if (timer !== null) clearTimeout(timer); };
  }, [key]);
  if (observed.key !== key || key === null) return { health: null, displayHealth: null, error: null };
  const fresh = isFreshHealthObservation(observed.checkedAt, now);
  const stalled = now - (observed.checkedAt || observed.startedAt) > 5000;
  return {
    health: fresh && observed.error === null ? observed.health : null,
    displayHealth: observed.health,
    error: observed.error ?? (stalled ? 'Status is stale: refresh exceeded the 5-second freshness limit. Displayed values are the last observation.' : null),
  };
}
