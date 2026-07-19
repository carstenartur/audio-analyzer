# Getting started

This guide is for audio-processing users who are comfortable running a Java project but do not need to understand the implementation before using the workbench.

## What you need

- Java 21 or newer;
- the included Maven Wrapper (`mvnw` or `mvnw.cmd`);
- a desktop environment for the Swing workbench;
- a browser for the workflow workbench;
- Docker only for optional integration and screenshot tests.

## Build once

From the repository root:

```bash
./mvnw clean package
```

On Windows:

```cmd
mvnw.cmd clean package
```

The build creates the desktop application, the packaged Spring Boot workbench and the production web assets.

## Explore a signal in the desktop workbench

Run:

```bash
java -jar audio-app/target/audio-app-*.jar
```

Start with a deterministic demo source. This avoids microphone, driver and room-acoustics variables while you learn the displays.

A useful first pass is:

1. Observe the waveform and verify that it repeats consistently.
2. Compare peak and RMS measurements.
3. Locate the dominant spectral component.
4. Enable spectrum averaging to make steady components easier to see.
5. Enable peak hold and introduce a transient.
6. Record a short `.aar` file.
7. Replay it and confirm that the same analysis path receives the saved audio blocks.

Continue with the [feature guides](features/README.md) for trigger, spectrum and recording details.

## Design a workflow in the browser

Run:

```bash
java -cp "audio-app/target/audio-app-*.jar:audio-app/target/lib/*" \
  org.hammer.audio.app.WorkbenchApplication
```

Open the local URL printed by the server.

The initial graph is a read-only orientation example. To edit:

1. Keep or change the generated actor identity.
2. Create a session.
3. Choose a collaboration mode.
4. Wait until the connection reports `live`.
5. Add a **Synthetic Signal Generator**.
6. Add a **Gain** node.
7. Inspect the semantic revision and durable history controls.

Every accepted edit is a typed semantic operation. The browser does not become the authority for the graph.

## Try semantic undo safely

In a private or personal-undo session:

1. Add a node.
2. Select **Undo**.
3. Review the server-generated preview.
4. Check the operation, actor, timestamp and affected semantic objects.
5. Confirm the command.

Undo appends a new audited inverse operation. It does not delete the original action.

In shared-undo mode, select a concrete history row first. The confirmation remains disabled until you acknowledge that the shared canonical workflow changes for all participants.

See [Collaborative workflows](features/collaborative-workflows.md).

## Memory mode or durable mode

The normal workbench starts in memory mode. It is suitable for orientation, UI evaluation and short-lived sessions.

Use explicit Hibernate persistence when collaboration recovery, durable outbox delivery and database-backed JGit checkpoints are required. Production-style startup requires an explicit data source, versioned migrations and Hibernate schema validation.

See [Hibernate-backed workflow persistence](workbench-hibernate-persistence.md).

## Use real audio carefully

Real capture introduces variables that deterministic demos do not:

- operating-system audio configuration;
- sample format and channel mapping;
- microphone frequency response;
- channel synchronization;
- gain staging and clipping;
- room reflections and background noise.

Record the exact setup when results matter. For microphone-array localization, synchronized channels and calibrated geometry are requirements rather than optional refinements.

## Next steps

- [Recording and replay](features/recording-and-replay.md)
- [A/B comparison](features/ab-comparison.md)
- [Collaborative workflows](features/collaborative-workflows.md)
- [Experimental acoustic localization](plugins/acoustic-localization.md)
- [Plugin development](development/plugin-development.md)
