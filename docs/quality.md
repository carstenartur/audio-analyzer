# Quality gates and coverage

Audio Analyzer treats executable behavior, architecture boundaries and public documentation as parts of one product-quality system. A green default build is necessary, but browser, persistence and visual changes also require the opt-in checks that exercise those boundaries.

## Default validation

Run before merging:

```bash
./mvnw clean verify
```

This covers:

- Java 21 enforcement;
- Java and TypeScript compilation;
- unit and integration tests in the default reactor;
- Spotless for Java, POM and Markdown;
- architecture fitness tests;
- JaCoCo reporting and minimum coverage;
- Checkstyle, PMD and SpotBugs baseline gates;
- production Vite build and asset reproducibility.

CodeQL runs as a separate GitHub workflow with an explicit Maven build.

Codecov upload is informative rather than a hard artifact gate. Upload failures still require investigation.

## Opt-in packaged-product gates

The normal reactor remains Docker-free. Changes to the web workbench, collaboration, SSE, session restore or generated web screenshots must also run the relevant Docker/Chromium workflows.

### Collaboration E2E

The Collaboration E2E workflow starts the packaged Spring Boot application and two isolated Chromium contexts. It verifies:

- live cross-browser canonical projection convergence;
- presence outside workflow state;
- stale-revision rejection without optimistic residue;
- controlled SSE interruption and catch-up;
- full reload and session restoration;
- durable personal and shared undo/redo behavior.

The suite uploads Failsafe reports and full browser/server diagnostics. It does not use fixed-delay sleeps; waits are tied to observable revisions, connection states, responses and DOM visibility.

### Screenshot verification

The screenshot profile generates documented states from the packaged application and compares them with committed PNG baselines.

A screenshot is accepted only when:

- the scenario asserts the described semantic state before capture;
- volatile presentation data is deliberately normalized or masked;
- labels, object ids and controls remain readable;
- important content is not clipped or obscured;
- the surrounding documentation matches the image;
- verify mode reproduces the committed baseline.

See [Workbench screenshot documentation pipeline](workbench-screenshot-pipeline.md).

## Persistence and migration gates

Durable-mode changes should prove:

- ordered Flyway migrations by schema owner;
- Hibernate `validate` startup;
- one application-managed `SessionFactory`;
- collaboration/session/outbox recovery;
- shared JGit storage integration;
- H2 and PostgreSQL behavior where production SQL semantics matter;
- no raw-JDBC or second-bootstrap test implementation.

## Coverage position

The JaCoCo floor is intentionally conservative. It proves that reporting and minimum behavior coverage cannot disappear; it is not a claim of comprehensive testing.

Raise coverage by protecting meaningful behavior:

1. add a focused test for a real contract or failure mode;
2. keep the current gate green;
3. raise the threshold in a small reviewable step;
4. avoid tests that execute lines without asserting behavior.

High-value targets include:

- sample-format and partial-buffer decoding;
- bounded ring-buffer behavior;
- waveform trigger and spectrum state transitions;
- analyzer rejection and threshold paths;
- recording/replay and evidence bundles;
- collaboration conflict, retry and recovery paths;
- persistence migration and restart invariants;
- UI adapter tests that avoid live hardware.

## Static-analysis baseline

Existing findings may be baseline-gated while debt is reduced. A touched area should not introduce new findings.

When changing a file:

- fix nearby low-risk findings;
- do not broaden exclusions to hide unrelated problems;
- document a necessary, narrow exclusion centrally;
- prefer behavior-preserving cleanups over mechanical repository-wide rewrites;
- keep framework adapters from leaking into stable domain packages.

## Documentation quality

Public documentation must:

- serve audio-processing users before implementation specialists;
- distinguish stable capabilities from experimental research;
- use commands that work from a clean checkout or state prerequisites;
- link detailed architecture and plugin material instead of overloading the README;
- regenerate user-interface images from current code;
- include visual QA in addition to pixel comparison;
- avoid calibrated-performance, species or safety claims without evidence;
- record remaining limitations in `docs/QA-FINDINGS.md` or linked issues.

See:

- [Documentation home](README.md)
- [Application and documentation QA plan](qa/application-documentation-qa-plan.md)
- [Screenshot QA checklist](qa/screenshot-qa-checklist.md)
- [Release-readiness checklist](qa/release-readiness-checklist.md)

## Common reports

After `mvn verify`:

- Checkstyle: `*/target/checkstyle-result.xml`
- PMD: `*/target/pmd.xml`
- SpotBugs: `*/target/spotbugsXml.xml`
- JaCoCo HTML: `*/target/site/jacoco/index.html`
- JaCoCo XML: `*/target/site/jacoco/jacoco.xml`
- Surefire XML: `*/target/surefire-reports/*.xml`
- Failsafe XML: `*/target/failsafe-reports/*.xml`

GitHub workflows additionally publish report bundles and failure artifacts.

## Release readiness

Before a public release or research-facing announcement:

1. run the default and relevant opt-in workflows;
2. complete visual screenshot review;
3. complete a dated manual packaged-application QA record;
4. verify persistent-mode migration/recovery when that mode is part of the release;
5. close or explicitly defer blocking findings;
6. ensure README and feature documentation describe the released behavior conservatively.

CI is evidence, not a substitute for manual usability and visual review.
