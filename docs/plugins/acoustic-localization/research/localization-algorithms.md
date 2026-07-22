# Localization algorithm families and measured trade-offs

This document records the localization algorithms that can currently be compared in
`audio-experimental-acoustic`. It deliberately separates measured behaviour from future research
ideas. All measurements are deterministic unit or scenario tests; none of the numbers below are
claims about uncalibrated real hardware.

## TDOA estimators

| Algorithm | Implementation | Strengths | Limitations | Evidence |
|---|---|---|---|---|
| Integer GCC-PHAT | `GccPhatTdoaEstimator` | Small and understandable baseline; physically bounded lag search; useful compatibility reference | Quantizes every delay to a whole sample; confidence is the selected peak's fraction of the searched correlation energy | `GccPhatTdoaEstimatorTest` and the baseline rows in `SubSampleGccPhatTdoaEstimatorTest` |
| Sub-sample GCC-PHAT | `SubSampleGccPhatTdoaEstimator` | Sixteen-times spectral interpolation; parabolic refinement; primary/secondary peak evidence; curvature-aware confidence; explicit ambiguity rejection | More FFT work and memory than the integer baseline; periodic or strongly multipath-dominated signals can still be ambiguous | `SubSampleGccPhatTdoaEstimatorTest` |
| Time-domain cross-correlation | `CrossCorrelationTdoaEstimator` | Simple alternative with different failure modes and no frequency-domain preprocessing | More sensitive to spectral coloration and reverberation; integer-sample result | Workbench estimator selection and existing estimator tests |

The default workbench strategy remains the integer GCC-PHAT implementation so existing runs remain
reproducible. `SUB_SAMPLE_GCC_PHAT` is an explicit selectable strategy.

## Measured TDOA behaviour

`SubSampleGccPhatTdoaEstimatorTest` executes the integer and sub-sample estimators over identical
known-delay blocks and renders the same `TdoaAlgorithmBenchmarkReport` structure used by other
callers.

The deterministic acceptance bounds are intentionally wider than the observed values:

- clean fractional delays: mean error below `0.08` samples and below 30% of the integer baseline;
- independent broadband noise: mean error below `0.05` samples and below 25% of the integer
  baseline;
- deterministic reflected paths plus low noise: mean error below `0.08` samples and below 40% of
  the integer baseline.

These comparisons demonstrate an improvement caused by resolving fractional delay. They do **not**
show that interpolation alone solves arbitrary reverberation. The reflected benchmark keeps the
direct path dominant; stronger multipath may correctly result in ambiguous diagnostics rather than
a confident estimate.

## Confidence and physical consistency

`TdoaPeakDiagnostics` exposes:

- the interpolated lag;
- primary and secondary correlation peaks;
- their ratio;
- resolution-normalized local curvature;
- an explicit ambiguity flag.

`DiagnosticTdoaEstimator.estimateReliable(...)` rejects estimates whose configured separation or
curvature policy is not satisfied.

For arrays with at least three microphones, `TdoaPairConsistencyAnalyzer` adds geometry-level
evidence:

- each pair is checked against the maximum acoustic path difference;
- complete microphone triples are checked for additive cycle consistency;
- the result contains findings, residuals, a normalized consistency score and a downstream
  confidence multiplier.

The workbench exports this evidence in Markdown, CSV and JSON-lines output through
`WorkbenchRunExporter`.

## Beamforming search

| Algorithm | Implementation | Strengths | Limitations | Evidence |
|---|---|---|---|---|
| Uniform grid | `DelayAndSumBeamformer.scan(...)` | Complete, deterministic baseline over caller-provided candidates | Cost grows with every candidate; resolution is fixed by the grid | Existing tracking and scenario tests |
| Adaptive multi-hypothesis refinement | `AdaptiveBeamformingSearch` | Retains the global maximum, follows two spatially distinct hypotheses, deduplicates evaluated positions and exports the complete normalized confidence surface | Still depends on the underlying delay-and-sum score; it is not a replacement for source separation or a calibrated SRP implementation | `AdaptiveBeamformingSearchTest` |

The delay-and-sum scorer aligns channels using **relative** propagation times, rounds only after the
relative delay is formed and evaluates only the common valid overlap of all channels. This avoids
absolute-distance and block-edge artefacts in passive localization.

## Choosing an algorithm

Use the integer GCC-PHAT baseline when compatibility, cost and simple interpretation matter most.
Use sub-sample GCC-PHAT when the sample interval is a material part of the error budget and the peak
diagnostics are available to the caller. Treat an ambiguous result as missing evidence, not as a
low-quality position that should silently continue through the pipeline.

Use adaptive beamforming when a fine uniform grid would be too expensive, but retain the uniform
scan as the benchmark reference. A high normalized beamforming score is relative to the evaluated
surface; it is not by itself a calibrated probability of the physical position.

## Deliberate non-claims and remaining research

The current implementation does not claim:

- blind separation of emitters with indistinguishable spectra;
- reliable localization when a reflected path is stronger than the direct path;
- real-hardware accuracy without measured geometry, synchronization and calibration;
- probabilistically calibrated position covariance from the beamforming surface;
- globally optimal assignment for every crossing-trajectory multi-source case.

Those topics remain valid experiments, but they should be introduced only with deterministic
baselines and side-by-side reports rather than by replacing the existing algorithms.
