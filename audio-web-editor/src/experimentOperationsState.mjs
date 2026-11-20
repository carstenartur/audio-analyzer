/** @param {{state: string, findings: ReadonlyArray<{severity: string}>}|null} health @param {boolean} reviewed */
export function canStartExperiment(health, reviewed) {
  return reviewed && health !== null && ['READY', 'READY_WITH_WARNINGS'].includes(health.state)
    && !health.findings.some((finding) => finding.severity === 'BLOCKING');
}

/** @param {number} bytes */
export function formatCapacity(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return 'Unknown';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KiB', 'MiB', 'GiB', 'TiB'];
  let value = bytes / 1024;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; }
  return `${value.toFixed(1)} ${units[index]}`;
}

/** @param {number} seconds */
export function formatSafeDuration(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) return 'Unknown';
  const whole = Math.floor(seconds);
  if (whole < 3600) return `${Math.floor(whole / 60)}m ${whole % 60}s`;
  return `${Math.floor(whole / 3600)}h ${Math.floor((whole % 3600) / 60)}m`;
}

/** @param {string} definition @returns {Record<string,string>} */
export function profileValues(definition) {
  /** @type {Record<string,string>} */
  const values = {};
  for (const line of definition.split(/\r?\n/)) {
    const separator = line.indexOf('=');
    if (separator > 0) {
      values[line.slice(0, separator)] = decodeURIComponent(line.slice(separator + 1).replaceAll('+', ' '));
    }
  }
  return values;
}

/** @param {string} definition @param {Record<string,string>} updates @param {boolean} invalidateCalibration */
export function editProfileDefinition(definition, updates, invalidateCalibration) {
  const values = { ...profileValues(definition), ...updates };
  if (invalidateCalibration) values['calibration.present'] = 'false';
  return Object.keys(values).sort().map((key) => `${key}=${encodeURIComponent(values[key])}`).join('\n') + '\n';
}

/** @param {string} definition @param {number} channels */
export function resizeProfileDefinition(definition, channels) {
  if (!Number.isInteger(channels) || channels < 2 || channels > 64) throw new Error('Choose between 2 and 64 microphone channels.');
  const values = profileValues(definition);
  values['array.channels'] = String(channels);
  values['profile.layout'] = 'CUSTOM';
  values['calibration.present'] = 'false';
  if (values['capture.present'] === 'true') values['capture.channels'] = String(channels);
  for (let index = 0; index < channels; index++) {
    const prefix = `array.microphone.${index}.`;
    values[prefix + 'id'] ??= `mic-${index}`;
    values[prefix + 'channel'] = String(index);
    values[prefix + 'x'] ??= '0';
    values[prefix + 'y'] ??= '0';
  }
  for (const key of Object.keys(values)) {
    const channel = /^array\.microphone\.(\d+)\./.exec(key);
    if (channel && Number(channel[1]) >= channels) delete values[key];
  }
  return editProfileDefinition('', values, true);
}

/**
 * Apply supplied measurements; no synthetic calibration is generated for real hardware.
 * @param {string} definition @param {string} id @param {string} measuredAt @param {string} validUntil @param {string} rows
 */
export function applyMeasuredCalibration(definition, id, measuredAt, validUntil, rows) {
  const channels = Number(profileValues(definition)['array.channels']);
  const parsed = rows.trim().split(/\r?\n/).filter(Boolean).map((line) => line.split(',').map((value) => value.trim()));
  if (!id.trim() || !Number.isFinite(Date.parse(measuredAt)) || !Number.isFinite(Date.parse(validUntil))
      || Date.parse(validUntil) < Date.parse(measuredAt)) throw new Error('Supply a calibration identity and valid measurement/expiry times.');
  if (parsed.length !== channels || parsed.some((row, index) => Number(row[0]) !== index)) {
    throw new Error('Supply exactly one measured row for every channel, in channel order.');
  }
  /** @type {Record<string,string>} */
  const updates = {
    'calibration.present': 'true', 'calibration.id': id, 'calibration.referenceChannel': '0',
    'calibration.calibratedAt': new Date(measuredAt).toISOString(), 'calibration.validUntil': new Date(validUntil).toISOString(),
  };
  for (const [index, row] of parsed.entries()) {
    if (row.length !== 7 || row.slice(1, 6).some((value) => !Number.isFinite(Number(value)))) {
      throw new Error('Each row needs channel, offset, drift, residual RMS, jitter RMS, gain, inverted polarity.');
    }
    if (index === 0 && (Number(row[1]) !== 0 || Number(row[2]) !== 0)) throw new Error('The reference channel 0 must have zero offset and drift.');
    if (Number(row[3]) < 0 || Number(row[4]) < 0 || Number(row[5]) <= 0 || !['true', 'false'].includes(row[6])) {
      throw new Error('Residual and jitter must be non-negative; gain must be positive; polarity is true or false.');
    }
    const prefix = `calibration.channel.${index}.`;
    updates[`${prefix}referenceFrame`] = '0';
    ['offsetSamples', 'driftPpm', 'residualRmsSamples', 'jitterRmsSamples', 'gainLinear', 'invertedPolarity']
      .forEach((key, column) => { updates[prefix + key] = row[column + 1]; });
  }
  return editProfileDefinition(definition, updates, false);
}
