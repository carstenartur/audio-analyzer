export interface ExperimentConfiguration {
  name: string;
  sourceMode: 'SIMULATION' | 'REPLAY' | 'LIVE';
  profileId: string;
  scenario: string;
  recordingId: string;
  durationSeconds: number;
  recordOutput: boolean;
  outputFilename: string;
}

export interface ExperimentFinding {
  code: string;
  severity: 'INFO' | 'WARNING' | 'BLOCKING' | 'ERROR';
  message: string;
  detail: string;
  component: string;
  observedAt: string;
  maximumAgeSeconds: number;
  remediationAction: string;
  acknowledgeable: boolean;
  acknowledged: boolean;
}

export interface ExperimentHealth {
  experimentId: string;
  experimentName: string;
  state: string;
  stage: string;
  observedAt: string;
  findings: ExperimentFinding[];
  facts: Record<string, string>;
  history: Array<{ observedAt: string; code: string; action: string; detail: string }>;
}

export interface StorageStatus {
  destination: string;
  usableBytes: number;
  bytesWritten: number;
  expectedBytesPerSecond: number;
  measuredBytesPerSecond: number;
  estimatedSafeSecondsRemaining: number;
  level: string;
  errorMessage: string;
  checkedAt: string;
  lastSuccessfulCheck: string | null;
  warningHistory: Array<{ level: string; observedAt: string; detail: string }>;
}

export interface ExperimentPreflight {
  token: string;
  health: ExperimentHealth;
  storage: StorageStatus | null;
}

export interface ExperimentRun {
  runId: string;
  health: ExperimentHealth;
  recording: {
    state: string;
    startedAt: string;
    updatedAt: string;
    destination: string;
    writtenFrames: number;
    receivedFrames: number;
    writtenBlocks: number;
    receivedBlocks: number;
    bytesWritten: number;
    measuredBytesPerSecond: number;
    droppedBlocks: number;
    continuityGapCount: number;
    queueDepth: number;
    queueCapacity: number;
    stopReason: string;
    errorMessage: string;
    storage: StorageStatus;
  } | null;
  tracks: Array<{ id: number; x: number; y: number; confidence: number }>;
  exportAvailable: boolean;
}

export interface MicrophoneProfileView {
  id: string;
  name: string;
  layout: string;
  channels: number;
  definition: string;
}

export interface CaptureDevice {
  deviceId: string;
  name: string;
  vendor: string;
  description: string;
  version: string;
}

export interface ExperimentCatalogue {
  profiles: MicrophoneProfileView[];
  devices: CaptureDevice[];
  scenarios: string[];
  recordings: Array<{ id: string; name: string; integrity: string; frames: number }>;
}
