# Immutable workflow run lifecycle

The workflow run API captures one exact immutable input before dispatch. A run may originate from an exact collaboration-session semantic revision or an exact stored workflow commit.

## Truthful backend mode

The initial backend mode is `SIMULATION`. It topologically visits nodes and exercises lifecycle, progress, cancellation, result and reproducibility contracts, but it does not perform audio or DSP computation. Production clients must display the returned mode and must not present a simulation as completed computation. Issue #274 owns the real deterministic audio backend.

## Identity and reproducibility

Each accepted start command has a stable idempotency key and maps to one process-local run id. The captured canonical DSL is hashed with SHA-256. Run metadata retains the workflow id, snapshot id, plan id, source revision or commit id, capture timestamp and fingerprint. Later editor or collaboration changes cannot mutate this input.

## Lifecycle

The public states are:

- `QUEUED`
- `RUNNING`
- `CANCEL_REQUESTED`
- `CANCELLED`
- `COMPLETED`
- `FAILED`

Cancellation is cooperative once backend execution has started. A queued cancellation prevents backend dispatch. Terminal results are available only when the backend produced reproducibility evidence.

## Process restart boundary

The active-run registry is intentionally process-local in this slice. Restarting the application does not resume queued or running jobs and does not promise recovery of completed run records. Git history remains the authority for stored workflow inputs; no second copy of workflow history is persisted as run authority.

A later durable job-record design may add recovery, leases and distributed dispatch without changing the current REST contract or the framework-independent execution backend port.
