# Quality gates and coverage

This page documents the checks that protect the repository during local development and CI. The goal
is not to claim that every warning has already been eliminated. The goal is to make quality visible,
prevent regressions and gradually reduce remaining technical debt.

## Required validation command

Run the full Maven verification before merging release-facing changes:

```bash
./mvnw clean verify
```

This command runs the core build, tests, formatting checks, static analysis, coverage checks and
architecture fitness tests.

## Build gates

The following gates are expected to pass in `mvn verify`:

- **Java version** — Maven Enforcer requires Java 21 or newer.
- **Unit tests** — Surefire runs module tests with `java.awt.headless=true`.
- **Spotless** — Java, POM and Markdown formatting must match the configured formatter.
- **Architecture fitness tests** — package and dependency boundaries are checked by tests.
- **JaCoCo** — coverage reports are generated and the configured minimum is enforced.
- **Checkstyle** — style findings are baseline-gated through the Maven/CI setup.
- **PMD** — code-quality findings are baseline-gated through the Maven/CI setup.
- **SpotBugs** — bug-pattern findings are baseline-gated through the Maven/CI setup.
- **CodeQL** — the GitHub workflow performs a separate security-analysis build.

Codecov upload is intentionally not a hard gate. Upload failures should be investigated, but they do
not automatically mean that the build artifact is invalid.

## Current coverage position

The JaCoCo line-coverage floor is intentionally low. It proves that coverage cannot disappear and that
reports are generated consistently, but it is not a claim of comprehensive test coverage.

Coverage should be raised gradually only when new tests exercise behavior that matters:

1. keep the current gate green;
2. add focused tests for critical paths;
3. raise the threshold in small steps;
4. avoid coverage-only tests that do not protect behavior.

High-value coverage targets:

- `SampleDecoder` format and partial-buffer paths;
- `AudioRingBuffer` boundary behavior and SPSC stress cases;
- waveform trigger and spectrum display-state edge cases;
- `SpectrumAnalyzer`, `StereoDelayAnalyzer`, `SpectrogramAnalyzer` and `DiagnosisAnalyzer` rejection
  and threshold paths;
- recording/replay and evidence-bundle assembly;
- `SampleClock` drift/jitter assumptions before stronger synchronized-array claims are made;
- workbench model tests that do not depend on live audio hardware.

## Static-analysis baseline

The repository may still contain existing Checkstyle, PMD or SpotBugs findings. CI should prevent the
number of findings from increasing beyond the committed baseline while the project reduces debt module
by module.

When touching a file with existing findings:

- do not introduce new findings;
- fix local findings when the fix is low-risk;
- document intentionally deferred findings in the PR;
- prefer small, reviewable cleanup PRs over broad mechanical rewrites.

## Documentation and screenshot quality

Documentation is part of the product. Public-facing documentation changes should satisfy the QA plan:

- command examples must work from a clean checkout or clearly state prerequisites;
- generated screenshots must be regenerated from the current codebase;
- screenshots must be visually reviewed for overlapping labels, clipped text and misleading states;
- experimental acoustic-localization claims must be limited to what tests, calibration and benchmark
  evidence support;
- remaining limitations must be recorded in `docs/QA-FINDINGS.md` or linked issues.

See:

- [Application and documentation QA plan](qa/application-documentation-qa-plan.md)
- [Screenshot QA checklist](qa/screenshot-qa-checklist.md)
- [Release-readiness checklist](qa/release-readiness-checklist.md)

## Report locations after `mvn verify`

Common report locations:

- Checkstyle: `*/target/checkstyle-result.xml`
- PMD: `*/target/pmd.xml`
- SpotBugs: `*/target/spotbugsXml.xml`
- JaCoCo HTML: `*/target/site/jacoco/index.html`
- JaCoCo XML: `*/target/site/jacoco/jacoco.xml`
- Surefire XML: `*/target/surefire-reports/*.xml`

## Release readiness

Before a public release or announcement, complete the release-readiness checklist and either close or
explicitly defer blocking QA findings. A release should not rely only on CI: manual app QA and visual
screenshot review are required for user-facing quality.
