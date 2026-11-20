/**
 * Require a successful preflight of the exact displayed semantic source.
 * @param {{state:string, findings:ReadonlyArray<{severity:string}>, facts:Record<string,string>}|null} health
 * @param {{sessionId?:string, revision?:number, commitId?:string}|null} source
 */
export function canRunWorkflowSource(health, source) {
  if (health === null || source === null || !['READY', 'READY_WITH_WARNINGS'].includes(health.state)
      || health.findings.some((finding) => finding.severity === 'BLOCKING')
      || !/^[0-9a-f]{64}$/.test(health.facts['workflow.sha256'] ?? '')) return false;
  if (source.commitId) return health.facts['workflow.commit'] === source.commitId;
  return health.facts['workflow.sessionId'] === source.sessionId
    && health.facts['workflow.revision'] === String(source.revision);
}

/** @param {number} observedAt @param {number} now @param {number} maximumAgeMillis */
export function isFreshHealthObservation(observedAt, now, maximumAgeMillis = 5000) {
  const age = now - observedAt;
  return Number.isFinite(age) && observedAt > 0 && age >= 0 && age <= maximumAgeMillis;
}
