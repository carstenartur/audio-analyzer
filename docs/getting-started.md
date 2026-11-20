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
DESKTOP_JAR=$(find audio-app/target -maxdepth 1 -type f \
  -name 'audio-app-*.jar' ! -name '*-workbench.jar' -print -quit)
java -jar "$DESKTOP_JAR"
```

The window initially selects **Live microphone**; opening it does not start the deterministic demo.
For a first recording that needs no hardware:

1. Select **Demo mode**, then **Sine** in the demo-signal selector.
2. Choose **File > Start/Stop**. The waveform must now change and show a repeating signal; the
   spectrum should show one dominant tone. FFT-bin resolution can place the reported peak near,
   rather than exactly on, the generator frequency.
3. Choose **File > Spectrum: averaging** and **File > Spectrum: peak hold** to see their effects.
   Use **File > Spectrum: reset peak hold** to clear the retained peaks. **Pause/Freeze** holds the
   visible analysis; it is not an acquisition or recording pause.
4. Choose **File > Start recording...**, select a writable destination such as `first-demo.aarec`,
   and confirm. Check the recording strip: elapsed time and written frames must increase.
5. After a few seconds, choose **File > Stop recording** and wait for finalization. Choose
   **File > Inspect or recover recording...** and select the result. A normal completed recording
   should report `COMPLETE`, a verified checksum and no continuity gaps.
6. Stop the original input with **File > Start/Stop**, then choose **File > Open recording...** and
   select `first-demo.aarec`. Replay starts automatically and feeds the same analysis views.
7. For a saved comparison, choose **File > Compare two recordings...** and select two recordings.
   Comparing the same completed file twice is a simple sanity check: comparable measurement deltas
   should be zero. Save the Markdown report when prompted.

If frames stay at zero, first check that acquisition is running and the view is not frozen. If the
recording is incomplete, preserve it and follow the inspection/recovery guide before treating it as
complete evidence. New recordings use `.aarec`; `.aar` is a legacy input extension.

Continue with the [feature guides](features/README.md) for trigger, spectrum and recording details.

## Design a workflow in the browser

Run:

```bash
java -jar audio-app/target/audio-app-*-workbench.jar
```

After the server reports that it has started, open [http://localhost:8080/workbench](http://localhost:8080/workbench).
Use the actual host/port if you changed the server configuration. Keep the server process running.

The initial graph is a read-only orientation example. To edit:

1. In **Collaboration**, enter a unique **Session id**, such as `first-audio-run`, and a **Workflow name**.
2. Choose **Private workspace** for this first example. Mode is fixed when the session is created.
3. Optionally enter a display name and choose **Save display name**. It is an unverified pseudonym;
   the server supplies the client identity.
4. Choose **Create session** and wait until **Connection** reports `live`. The new session begins
   with its own empty graph; it does not copy the orientation graph or an open portable document.
5. Add one **Synthetic Signal Generator** and one **Gain** from **Node palette**.
6. Drag the generator's **Synthetic Signal** output port to the gain node's **Audio In** port.
7. Select each node and enter the following **Property** and **Value** pairs in **Parameters**.
   Choose **Apply property** after each row and wait for the accepted revision before the next edit.

|            Node            |        Property         | Value  |
|----------------------------|-------------------------|--------|
| Synthetic Signal Generator | `signal.waveform`       | `sine` |
| Synthetic Signal Generator | `signal.frequency-hz`   | `1000` |
| Synthetic Signal Generator | `signal.phase-radians`  | `0`    |
| Synthetic Signal Generator | `signal.amplitude`      | `0.5`  |
| Synthetic Signal Generator | `signal.sample-rate-hz` | `8000` |
| Synthetic Signal Generator | `signal.channels`       | `1`    |
| Synthetic Signal Generator | `signal.frame-count`    | `8`    |
| Gain                       | `gain.factor`           | `2`    |

8. Scroll the left sidebar to **Immutable run**. Wait for **Selected workflow readiness** to become
   ready, then choose **Run current revision**. A blocker names the missing/unsupported property;
   correct it and wait for a new preflight before starting.
9. Wait for the terminal result. Both nodes should complete in computation mode, with eight mono
   output frames and a stable output digest. The samples are approximately
   `0, 0.7071, 1, 0.7071, 0, -0.7071, -1, -0.7071`; the bounded preview uses hexadecimal floating-point
   notation. The [deterministic example](architecture/deterministic-audio-workflow-example.md)
   explains the exact output and digest contract.

This example performs offline signal computation. It does not produce a microphone recording or a
complete acoustic evidence ZIP. For that complete path, open
[http://localhost:8080/workbench/experiment](http://localhost:8080/workbench/experiment) and follow
[Complete a first simulation](features/microphone-experiments.md#complete-a-first-simulation).

The main canvas can display more node types than the current computation backend executes. Keep this
first run to the two configured nodes; the [backend guide](architecture/deterministic-audio-workflow-backend.md)
lists supported types and limits.

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

## Save a portable setup or share a session

These are separate paths. The desktop document editor can validate workflow DSL changes and save a
complete `.audioexp` copy. The browser document panel previews/opens/downloads its standalone document
workspace; it does not export edits from a live collaboration graph. Live sessions preserve their
semantic history according to the configured persistence mode. Follow the
[portable document round trips](features/portable-experiment-documents.md) or the
[two-browser collaboration procedure](features/collaborative-workflows.md) for the matching task.

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
- [Microphone experiments](features/microphone-experiments.md)
- [Experiment readiness and runtime health](features/experiment-health.md)
- [Portable experiment documents](features/portable-experiment-documents.md)
- [A/B comparison](features/ab-comparison.md)
- [Collaborative workflows](features/collaborative-workflows.md)
- [Experimental acoustic localization](plugins/acoustic-localization.md)
- [Plugin development](development/plugin-development.md)

