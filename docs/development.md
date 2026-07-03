# Development

This guide explains how to build, validate and document Audio Analyzer from a clean checkout. The
project is a Java 21 multi-module Maven build with a Swing application, stable audio/DSP modules and
an optional experimental acoustic-localization plugin.

## Prerequisites

Required:

- Java 21 or newer;
- the included Maven Wrapper (`mvnw` / `mvnw.cmd`);
- a shell capable of running the commands below.

The wrapper downloads the configured Maven version automatically. A system Maven installation can be
used for local convenience, but CI uses the wrapper configuration.

## Build and validation

Use the full verification command before opening or merging a PR:

```bash
./mvnw clean verify
```

On Windows:

```cmd
mvnw.cmd clean verify
```

The command compiles all modules, runs tests, checks formatting, executes static analysis, generates
coverage reports and enforces architecture checks.

For a faster package-only build while iterating locally:

```bash
./mvnw clean package
```

## Running the application

After `package` or `verify`, run the desktop app from the generated JAR:

```bash
java -jar audio-app/target/audio-app-*.jar
```

If the shell does not expand `audio-app/target/audio-app-*.jar`, replace it with the concrete file
name in `audio-app/target/`.

## Reproducible artifacts

The build uses a fixed `project.build.outputTimestamp`. Rebuilding the same sources should produce the
same app JAR checksum:

```bash
./mvnw clean package
sha256sum audio-app/target/audio-app-*.jar

./mvnw clean package
sha256sum audio-app/target/audio-app-*.jar
```

Both checksums should match when inputs and build environment are unchanged.

## Code style and formatting

Spotless formats Java, POM and Markdown files. The default `verify` phase checks formatting.

```bash
./mvnw spotless:apply
./mvnw spotless:check
```

Use `spotless:apply` before committing documentation changes. Markdown tables are formatted by
Spotless; for long QA matrices, prefer checklists or bullet lists when table readability becomes
fragile.

The repository also contains an `.editorconfig` file for UTF-8 encoding, LF line endings, indentation,
trailing-whitespace removal and final newlines.

## Continuous integration

GitHub Actions runs the Maven build on pushes and pull requests to `master`.

Current CI expectations:

- Java 21 is required by Maven Enforcer;
- Surefire tests run with `java.awt.headless=true`;
- Spotless must pass for Java, POM and Markdown files;
- Checkstyle, PMD and SpotBugs are baseline-gated;
- JaCoCo generates reports and enforces the configured minimum line coverage;
- architecture fitness tests protect package boundaries;
- CodeQL uses an explicit Maven package build.

Workflow artifacts include JUnit XML, raw Surefire output, HTML test reports when generated, JaCoCo
coverage reports and static-analysis XML reports.

## Headless Swing testing

Tests run with `java.awt.headless=true`. Swing code that depends on resize events may need to dispatch
the event explicitly:

```java
SwingUtilities.invokeAndWait(() -> {
    panel.setSize(300, 150);
    panel.dispatchEvent(new ComponentEvent(panel, ComponentEvent.COMPONENT_RESIZED));
});
```

Guidelines:

- use `SwingUtilities.invokeAndWait()` for UI mutations that must run on the EDT;
- mock `AudioCaptureService` when testing UI logic;
- assert model state and rendered output contracts rather than relying only on manual screenshots;
- add targeted tests when controls introduce long labels, dynamic layout or resizing behavior.

## Documentation screenshots

README and feature screenshots are generated headlessly by `DocImageRenderer`:

```bash
./mvnw -pl audio-app -am package -DskipTests
java -cp "audio-app/target/classes:audio-app/target/lib/*" \
  org.hammer.tools.DocImageRenderer docs/images
```

The command writes `docs/images/screenshot.png` and feature images under `docs/images/features/`.
Use `;` instead of `:` in the classpath on Windows.

Screenshot changes require visual review. Follow:

- [Screenshot QA checklist](qa/screenshot-qa-checklist.md)
- [Application and documentation QA plan](qa/application-documentation-qa-plan.md)

## Documentation standards

Public documentation should be accurate, testable and conservative:

- separate stable application features from experimental research features;
- avoid production claims for acoustic localization unless supported by calibration and benchmark
  evidence;
- keep command examples version-tolerant;
- link to source files or docs rather than duplicating long explanations;
- update screenshots and surrounding text together;
- record known limitations in `docs/QA-FINDINGS.md` or a linked issue.

## Optional benchmarks

An opt-in JMH profile benchmarks selected DSP paths on synthetic buffers. Benchmark sources live under
`src/jmh/java`.

```bash
./mvnw clean verify -Pjmh
./mvnw exec:java -Pjmh
```

## Contribution checklist

Before opening a PR:

1. run `./mvnw spotless:apply`;
2. run `./mvnw clean verify` when practical;
3. review Checkstyle, PMD and SpotBugs output for new findings;
4. update documentation and screenshots when behavior changes;
5. document deferred warnings, missing QA or known limitations in the PR description.
