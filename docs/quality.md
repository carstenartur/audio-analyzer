# Quality Gates & Coverage

This page describes the quality checks that are enforced in Maven/CI.

## Current gates

|          Gate           |                                      Configuration                                      |                Fails build/CI?                 |
|-------------------------|-----------------------------------------------------------------------------------------|------------------------------------------------|
| Java version            | Maven Enforcer requires Java `[21,)`                                                    | **Yes**, in `mvn verify`                       |
| Unit tests              | Surefire, `java.awt.headless=true`                                                      | **Yes**, in `mvn verify`                       |
| Spotless                | Java, POM and Markdown format check                                                     | **Yes**, in `mvn verify`                       |
| Architecture boundaries | JUnit test in `audio-app`                                                               | **Yes**, in `mvn verify`                       |
| JaCoCo                  | `prepare-agent`, `report`, `check`; `BUNDLE` line coverage minimum `0.05`               | **Yes**, in `mvn verify`                       |
| Checkstyle              | `checkstyle.xml`, severity `warning`, `failOnViolation=true`                            | **Yes**, in `mvn verify`                       |
| PMD                     | `pmd-ruleset.xml`, `failOnViolation=true`                                               | **Yes**, in `mvn verify`                       |
| SpotBugs                | `effort=Max`, `threshold=Low`, exclusions in `spotbugs-exclude.xml`, `failOnError=true` | **Yes**, in `mvn verify`                       |
| Codecov upload          | `codecov/codecov-action`, `fail_ci_if_error=false`                                      | **No**; upload failures are not a quality gate |
| CodeQL                  | GitHub workflow with explicit Maven package build                                       | **Yes** when the CodeQL workflow runs          |

## Current coverage snapshot

Observed JaCoCo line coverage from a full verification run:

|            Module             | Line coverage |
|-------------------------------|--------------:|
| `audio-core`                  |        69.56% |
| `audio-plugin-api`            |       100.00% |
| `audio-geometry`              |        61.05% |
| `audio-acquisition`           |     no report |
| `audio-dsp`                   |        78.65% |
| `audio-app`                   |        29.96% |
| `audio-experimental-acoustic` |        86.03% |

`audio-acquisition` is included in the Maven reactor and JaCoCo configuration, but this run
did not produce a module JaCoCo XML/HTML report for it because there was no test execution data in
that module.

The 5% JaCoCo floor is deliberately low because its purpose is to prove the gate and prevent
coverage from disappearing, not to claim comprehensive test coverage.

## Report locations after `mvn verify`

- Checkstyle: `*/target/checkstyle-result.xml`
- PMD: `*/target/pmd.xml`
- SpotBugs: `*/target/spotbugsXml.xml`
- JaCoCo HTML: `*/target/site/jacoco/index.html`
- JaCoCo XML: `*/target/site/jacoco/jacoco.xml`

## Hardening roadmap

1. Keep static-analysis and formatting/test gates green in regular `mvn verify` runs.
2. Raise JaCoCo line coverage in small steps: **5% → 10% → 20% → 30%**, backed by tests for behavior
   rather than coverage-only assertions.

## Target areas for increased coverage

- `SampleDecoder` format and partial-buffer paths.
- `AudioRingBuffer` boundary and SPSC stress behavior.
- `WaveformRenderer`, trigger and spectrum display-state edge cases.
- `SpectrumAnalyzer`, `StereoDelayAnalyzer`, `SpectrogramAnalyzer` and `DiagnosisAnalyzer` rejection
  and threshold paths.
- Recording/replay and evidence-bundle assembly.
- `SampleClock` drift/jitter assumptions: currently documented as a limitation and should become a
  focused test or tracked issue before real synchronized-array claims are made.

