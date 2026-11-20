package org.hammer.audio.acquisition.health;

/** Shared readiness, execution and evidence-completion states for every experiment surface. */
public enum ExperimentHealthState {
  NOT_CONFIGURED,
  BLOCKED,
  READY_WITH_WARNINGS,
  READY,
  RUNNING,
  DEGRADED,
  STOPPING,
  COMPLETED,
  INCOMPLETE,
  FAILED
}
