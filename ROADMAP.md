# Roadmap

This roadmap lists open next steps. Completed foundations such as FFT/spectrum, spectrogram,
diagnosis, evidence export, recording/replay, A/B comparison, trigger and spectrum peak-hold are
current capabilities and are documented under [docs/features/](docs/features/README.md).

## Product hardening

- Continue reducing Checkstyle, PMD and SpotBugs findings below the committed CI baseline.
- Raise JaCoCo coverage thresholds gradually after adding behavior-focused tests.
- Add a lightweight documentation link check if it can run without network access.
- Keep the README screenshot workflow reproducible as the Swing dashboard evolves.

## UI and ergonomics

- Exercise the main dashboard at common desktop sizes and HiDPI scale factors.
- Add focused layout tests for measurement and diagnosis panels when adding controls.
- Review long labels/translations before they are added to fixed-width controls.

## Architecture

- Resolve the current `org.hammer.audio` split package before any JPMS migration.
- Decide whether `DemoSignalType` belongs in a stable shared API package or an app-specific package.
- Keep plugin contracts in `audio-plugin-api`; avoid compile-time app dependencies on concrete
  plugins.

## Recording, export and comparison

- Add richer evidence-bundle metadata and reproducibility hints.
- Expand A/B comparison reports with configurable thresholds for regression use cases.
- Add replay-driven integration tests around end-to-end model publication.

## Experimental acoustic localization

The `audio-experimental-acoustic` module remains a research plugin, not a production feature.

**Current capabilities (implemented):**

- End-to-end tracking pipeline with multi-peak detection, frequency clustering, TDOA estimation,
  beamforming and Kalman-based source tracking
- Interactive simulation workbench with nine deterministic scenarios
- HumBugDB dataset import (local, offline-first)
- Rule-based classification baseline with evaluation metrics
- Feature extraction and distribution analysis
- Synthetic-vs-real comparison infrastructure
- Benchmark metrics for localization and classification

**Experimental (partial implementation):**

- Feature ranking and comparison for classifier development
- Generator calibration for realistic synthetic data
- Additional visualization contributions to plugin views

**Open research work (future):**

Tracked in repository issues and detailed in [plugin documentation](docs/plugins/acoustic-localization/README.md#future-research-directions):

1. Sub-sample GCC-PHAT peak interpolation for higher localization precision
2. Multi-source separation using probabilistic data association
3. 3D geometry and calibrated array file formats
4. Improved reflection models and measured room impulse responses
5. Expanded benchmark corpus with more real mosquito recordings

## Timing and synchronization

- Turn the documented `SampleClock` drift/jitter limitation into an executable test or tracked issue.
- Add calibration-data examples for synchronized microphone arrays before making stronger real-world
  localization claims.

