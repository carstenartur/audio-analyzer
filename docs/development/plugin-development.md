# Developing an Audio Analyzer plugin

Audio Analyzer plugins add analyses, signal sources, repeatable experiments, processing pipelines, result streams, visualizations, calibration procedures, benchmarks, exports or optional Swing views without coupling the host application to a concrete implementation.

The stable contract lives in `audio-plugin-api`. Plugins are discovered with Java `ServiceLoader`.

## Architectural boundary

A plugin implementation may depend on its own domain and DSP code, but host communication must use `org.hammer.audio.plugin` contracts.

The plugin API intentionally does not depend on:

- JavaSound;
- `audio-core`;
- `audio-dsp`;
- `audio-acquisition`;
- `audio-geometry`;
- Spring;
- JGit or Hibernate;
- concrete host application classes.

`ViewContribution` is the one UI-facing exception: it uses the JDK's `JComponent` so the desktop host can open a contributed view without depending on the plugin's implementation class.

## Add the API dependency

Inside this repository, add:

```xml
<dependency>
  <groupId>audioin</groupId>
  <artifactId>audio-plugin-api</artifactId>
  <version>${project.version}</version>
</dependency>
```

For an external build, install or consume the matching API artifact and use an explicit compatible version. Do not compile a plugin against `audio-app`.

## Implement the plugin contract

The smallest useful plugin supplies metadata and uses default empty contribution lists:

```java
package example.audio.plugin;

import org.hammer.audio.plugin.AudioAnalyzerPlugin;
import org.hammer.audio.plugin.PluginDescriptor;

public final class MeasurementPlugin implements AudioAnalyzerPlugin {

  private static final PluginDescriptor DESCRIPTOR =
      new PluginDescriptor(
          "measurement-tools",
          "Measurement Tools",
          "1.0.0",
          "Additional repeatable audio measurements.",
          "https://example.invalid/measurement-tools",
          false);

  @Override
  public PluginDescriptor descriptor() {
    return DESCRIPTOR;
  }
}
```

Descriptor requirements:

- `id` is stable and machine-readable;
- `name` is suitable for menus and diagnostics;
- `version` follows a deliberate compatibility policy;
- `description` is one concise sentence;
- `documentationPath` points to maintained documentation or is `null`;
- `experimental` truthfully distinguishes research code from production-ready code.

## Register with ServiceLoader

Add this file to the plugin JAR:

```text
src/main/resources/META-INF/services/org.hammer.audio.plugin.AudioAnalyzerPlugin
```

Its content is the fully qualified implementation name:

```text
example.audio.plugin.MeasurementPlugin
```

The host loads providers from its runtime class loader. A plugin JAR must therefore be present on the application runtime classpath. The current plugin manager is not a hot-deploy directory watcher.

## Choose contribution types

`AudioAnalyzerPlugin` exposes independent immutable contribution lists:

| Contribution | Use it for |
|---|---|
| `AnalysisContribution` | An analyzer, derived snapshot or post-processing capability |
| `DemoSignalContribution` | A deterministic signal users can select for experiments |
| `SignalSourceContribution` | Microphone, synthetic, recording or dataset input |
| `ExperimentContribution` | A named repeatable experimental scenario |
| `PipelineContribution` | A described DSP and analysis stage chain |
| `SnapshotStreamContribution` | A per-frame result stream contract |
| `VisualizationContribution` | UI-independent visualization metadata |
| `CalibrationContribution` | A calibration procedure or persistent calibration state |
| `BenchmarkContribution` | A quality metric and evaluation procedure |
| `ExportFormatContribution` | A serialization or report format |
| `MenuContribution` | A named action exposed by the host |
| `ViewContribution` | A lazily created Swing component |

Return `List.of()` for unsupported contribution types. Do not return `null` or a mutable list.

## Add a desktop view only when needed

A view contribution supplies an id, title and component factory. The factory must create a fresh component for each invocation because the host owns the component lifecycle.

Prefer UI-independent analysis, pipeline, snapshot and visualization contracts when the same capability may be used by the web workbench or a headless application.

## Failure isolation

`PluginManager` loads each provider independently. A broken constructor, service declaration or descriptor is reported as one failed plugin and does not prevent other providers from loading.

That isolation is not a substitute for plugin testing. A plugin should verify:

- its service file resolves exactly the intended provider;
- `descriptor()` is non-null and stable;
- contribution ids are unique within the plugin;
- contribution lists are immutable;
- factories return new objects where required;
- experimental status and documentation links are accurate;
- no host-internal packages appear in imports.

## Reference implementation

`audio-experimental-acoustic` is the repository's broad reference plugin. Its `AcousticLocalizationPlugin` demonstrates all contribution families while keeping concrete localization and dataset code in the experimental module.

Use it as an API example, not as evidence that every experimental algorithm is production-ready.

## Packaging and launch

Build the plugin JAR and place it on the runtime classpath together with the application and its dependencies. For example:

```bash
java -cp \
  "audio-app/target/audio-app-*.jar:audio-app/target/lib/*:path/to/measurement-plugin.jar" \
  org.hammer.AudioAnalyseFrame
```

`org.hammer.AudioAnalyseFrame` is the desktop main class configured in the current application JAR manifest. On Windows, use `;` as the classpath separator.

## Compatibility guidance

Until a published plugin compatibility policy says otherwise:

- compile against the exact intended `audio-plugin-api` version;
- avoid depending on snapshot internals outside the API package;
- treat contribution record/schema changes as API compatibility events;
- test the plugin against the packaged application version it will run with;
- pin versions in reproducible experiments.

## Documentation expectations

A professional plugin should ship:

- a purpose and scope statement;
- one first-use path;
- supported contribution list;
- input and output assumptions;
- deterministic example data where possible;
- known limitations;
- experimental or validation status;
- calibration and benchmark evidence for measurement claims.

Plugin documentation should be linked from `PluginDescriptor.documentationPath`. It should not turn the main project README into an implementation manual.
