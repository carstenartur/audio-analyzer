# Immutable workflow run lifecycle

The workflow run API captures one exact immutable input before dispatch. A run may originate from an
exact collaboration-session semantic revision or an exact stored workflow commit.

## Truthful backend mode

The REST contract always exposes the backend mode. The simulation backend remains available for
isolated lifecycle and contract tests and reports `SIMULATION`. Production wiring now selects the
first real deterministic offline backend and reports `COMPUTATION` for the supported
`SyntheticSignalGenerator -> Gain` vertical slice. Clients must continue to display the returned mode
rather than infer capability from a terminal status.

## Identity and reproducibility

Each accepted start command has a stable idempotency key and maps to one process-local run id. The
captured canonical DSL is hashed with SHA-256. Run metadata retains the workflow id, snapshot id, plan
id, source revision or commit id, capture timestamp and fingerprint. Later editor or collaboration
changes cannot mutate this input.

Computation results additionally expose a backend version and a canonical SHA-256 digest of the
terminal audio samples. Exact historical commits are loaded through `VersionedWorkflowStore` before
execution; Git history remains the authoritative workflow source.

## Lifecycle

The public states are:

- `QUEUED`
- `RUNNING`
- `CANCEL_REQUESTED`
- `CANCELLED`
- `COMPLETED`
- `FAILED`

Cancellation is cooperative once backend execution has started. A queued cancellation prevents
backend dispatch. The deterministic audio backend also checks cancellation between bounded sample
chunks. Terminal results retain reproducibility evidence for successful, cancelled and node-failed
computations.

## Process restart boundary

The active-run registry is intentionally process-local in this slice. Restarting the application does
not resume queued or running jobs and does not promise recovery of completed run records. Git history
remains the authority for stored workflow inputs; no second copy of workflow history is persisted as
run authority.

A later durable job-record design may add recovery, leases and distributed dispatch without changing
the current REST contract or the framework-independent execution backend port.
