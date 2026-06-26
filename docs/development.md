# Development

## Prerequisites

- **Java 21** or higher (enforced by `maven-enforcer-plugin`).
- The project ships a Maven Wrapper (`mvnw`) that downloads the correct Maven version (3.9.9) automatically.

## Build & Test

Using the Maven Wrapper (recommended):

```bash
./mvnw clean verify
```

On Windows:

```cmd
mvnw.cmd clean verify
```

Using a system Maven installation:

```bash
mvn clean verify
```

### Reproducible Builds

Artifacts use a fixed `project.build.outputTimestamp`, so repeated builds from the same sources produce byte-for-byte identical JARs.

```bash
./mvnw clean package
sha256sum audio-app/target/audio-app-0.0.2-SNAPSHOT.jar

./mvnw clean package
sha256sum audio-app/target/audio-app-0.0.2-SNAPSHOT.jar
# Both checksums must match.
```

### Updating the Maven Wrapper

```bash
mvn -N wrapper:wrapper -Dmaven=3.9.9
```

### Dependency Management

JUnit dependencies are managed via the JUnit BOM, so no explicit JUnit version numbers appear in the regular `<dependencies>` section.

## Code Style

The project uses [Spotless](https://github.com/diffplug/spotless) with [google-java-format](https://github.com/google/google-java-format).

```bash
mvn spotless:apply   # auto-format
mvn spotless:check   # verify only (also runs in `verify`)
```

Spotless formats Java sources, `pom.xml`, and Markdown files. An `.editorconfig` file pins encoding (UTF-8), line endings (LF), indentation, trailing-whitespace removal, and final-newline insertion.

## Continuous Integration

GitHub Actions runs the build on every push and pull request to `master`:

- **Build/Test** ([`maven.yml`](../.github/workflows/maven.yml)) on Java 21 with Maven dependency caching.
- **Static analysis**: Checkstyle, SpotBugs, PMD reports uploaded as artifacts; CI fails only when
  report counts exceed `quality-baseline.properties`.
- **Coverage**: JaCoCo report plus a 5% bundle line-coverage check; reports are uploaded to
  [Codecov](https://codecov.io/gh/carstenartur/javarepos), but Codecov upload itself is not a hard
  gate.
- **Security scanning**: CodeQL ([`codeql.yml`](../.github/workflows/codeql.yml)) with an explicit
  Maven package build.

Test artifacts uploaded by the workflow:

|                       Artifact                       |                         Contents                         |
|------------------------------------------------------|----------------------------------------------------------|
| `junit-xml`                                          | Surefire JUnit XML reports                               |
| `surefire-raw`                                       | Raw `.txt` console output and thread dumps               |
| `surefire-html`                                      | HTML test summary (when generated)                       |
| `jacoco-report`                                      | JaCoCo HTML coverage report                              |
| `checkstyle-report`, `spotbugs-report`, `pmd-report` | Static analysis XML output used by the CI baseline check |

## Headless Testing

Tests run with `java.awt.headless=true` (configured in `pom.xml` under `maven-surefire-plugin`). In headless mode, Swing components don't auto-dispatch `ComponentEvent.COMPONENT_RESIZED`, so resize-aware tests dispatch the event manually:

```java
SwingUtilities.invokeAndWait(() -> {
    panel.setSize(300, 150);
    panel.dispatchEvent(new ComponentEvent(panel, ComponentEvent.COMPONENT_RESIZED));
});
```

Testing tips:

- Use `SwingUtilities.invokeAndWait()` for actions that must run on the EDT.
- Mock `AudioCaptureService` with Mockito to isolate UI logic from real audio hardware.
- Verify method invocations (e.g. `verify(svc).recomputeLayout(width, height)`) instead of pixel output.

## Documentation screenshots

The README and feature screenshots are generated headlessly by `DocImageRenderer` after a package or
verify build:

```bash
java -cp "audio-app/target/audio-app-0.0.2-SNAPSHOT.jar:audio-app/target/lib/*" \
  org.hammer.tools.DocImageRenderer docs/images
```

The command writes `docs/images/screenshot.png` and feature images under `docs/images/features/`.
On Windows, use `;` instead of `:` in the classpath.

## JMH Benchmarks

An opt-in JMH profile benchmarks sample decoding on synthetic buffers (no audio device required). Benchmark sources live under `src/jmh/java`.

```bash
# Compile with the JMH profile
mvn clean verify -Pjmh

# Run benchmarks
mvn exec:java -Pjmh
```

## Contributing

1. **Format first** — run `mvn spotless:apply` before committing.
2. **Review warnings** — check Checkstyle, SpotBugs, and PMD output for new findings.
3. **Fix high-severity issues** flagged by SpotBugs or PMD.
4. **Document deferred low-severity warnings** in your PR description.

CI runs every check automatically and uploads the resulting reports.
