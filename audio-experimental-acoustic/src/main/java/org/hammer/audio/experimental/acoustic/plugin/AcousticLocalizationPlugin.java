package org.hammer.audio.experimental.acoustic.plugin;

import java.awt.BorderLayout;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import org.hammer.audio.experimental.acoustic.workbench.AcousticLocalizationWorkbenchPanel;
import org.hammer.audio.experimental.acoustic.workbench.ImportedRecordingWorkbenchPanel;
import org.hammer.audio.plugin.AnalysisContribution;
import org.hammer.audio.plugin.AudioAnalyzerPlugin;
import org.hammer.audio.plugin.BenchmarkContribution;
import org.hammer.audio.plugin.CalibrationContribution;
import org.hammer.audio.plugin.DemoSignalContribution;
import org.hammer.audio.plugin.ExperimentContribution;
import org.hammer.audio.plugin.ExportFormatContribution;
import org.hammer.audio.plugin.MenuContribution;
import org.hammer.audio.plugin.PipelineContribution;
import org.hammer.audio.plugin.PluginDescriptor;
import org.hammer.audio.plugin.SignalSourceContribution;
import org.hammer.audio.plugin.SnapshotStreamContribution;
import org.hammer.audio.plugin.ViewContribution;
import org.hammer.audio.plugin.VisualizationContribution;

/**
 * Reference plugin that exposes the experimental acoustic-localization research code (wingbeat
 * frequency tracking, TDOA estimators, delay-and-sum beamforming and the 2D room simulator) to the
 * host application as a set of contributions.
 *
 * <p>This class deliberately only describes contributions through the stable plugin API. The
 * concrete DSP types remain in the {@code org.hammer.audio.experimental.acoustic} packages so the
 * stable plugin API does not need to depend on any audio-domain module.
 *
 * <p>The plugin implements all workbench contribution types defined by the generic plugin API,
 * making the acoustic-localization workflow the first reference implementation of the reusable
 * signal-processing workbench platform.
 */
public final class AcousticLocalizationPlugin implements AudioAnalyzerPlugin {

  private static final String CATEGORY_LOCALIZATION = "localization";

  private static final PluginDescriptor DESCRIPTOR =
      new PluginDescriptor(
          "acoustic-localization",
          "Experimental Acoustic Localization",
          "0.1.0",
          "Experimental localization of weak, intermittent or insect-like acoustic sources.",
          "docs/plugins/acoustic-localization.md",
          true);

  @Override
  public PluginDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public List<AnalysisContribution> analysisContributions() {
    return List.of(
        contribution(
            "wingbeat-frequency-tracker",
            "Tracks the dominant wingbeat frequency inside a configurable band."),
        contribution(
            "cross-correlation-tdoa",
            "Time-domain cross-correlation TDOA estimator for microphone pairs."),
        contribution(
            "gcc-phat-tdoa",
            "Frequency-domain GCC-PHAT TDOA estimator robust against narrow-band signals."),
        contribution(
            "delay-and-sum-beamformer",
            "Delay-and-sum beamformer producing a 2D heatmap over a candidate grid."),
        contribution(
            "doppler-velocity-tracking",
            "Doppler-based radial velocity estimation fused with multi-frame source tracking."),
        contribution(
            "mosquito-localization-pipeline",
            "Orchestrates frequency tracking, TDOA estimation and beamforming into a snapshot."));
  }

  @Override
  public List<DemoSignalContribution> demoSignalContributions() {
    return List.of(
        demo(
            "insect-burst",
            "Insect-like high-frequency burst",
            "Short, narrow-band burst around typical mosquito wingbeat frequencies."),
        demo(
            "moving-source",
            "Moving acoustic source",
            "Synthetic source moving across a 2D array, useful for tracking experiments."));
  }

  @Override
  public List<SignalSourceContribution> signalSourceContributions() {
    return List.of(
        signalSource(
            "simulated-microphone-array",
            "Simulated Microphone Array",
            "Deterministic synthetic microphone array generated from 2D room acoustics simulation.",
            "synthetic"),
        signalSource(
            "humbugdb-recording",
            "HumBugDB Recording",
            "Offline recording loaded from a local HumBugDB dataset export.",
            "dataset"));
  }

  @Override
  public List<ExperimentContribution> experimentContributions() {
    return List.of(
        experiment(
            "single-static-source",
            "Single Static Source",
            "Localize a single stationary mosquito-like emitter in a simulated room.",
            CATEGORY_LOCALIZATION),
        experiment(
            "moving-source",
            "Moving Source",
            "Track a single source moving linearly across the microphone array.",
            CATEGORY_LOCALIZATION),
        experiment(
            "two-sources",
            "Two Simultaneous Sources",
            "Localize two independent emitters active at the same time.",
            CATEGORY_LOCALIZATION),
        experiment(
            "reflections",
            "Room Reflections",
            "Test localization robustness under first-order wall reflections.",
            CATEGORY_LOCALIZATION),
        experiment(
            "noisy-environment",
            "Noisy Environment",
            "Measure localization accuracy under additive Gaussian noise.",
            CATEGORY_LOCALIZATION),
        experiment(
            "doppler-moving-source",
            "Doppler Moving Source",
            "Estimate Doppler radial velocity of a moving source.",
            CATEGORY_LOCALIZATION),
        experiment(
            "humbugdb-classification",
            "HumBugDB Classification",
            "Replay feature extraction and rule-based classification on imported HumBugDB clips.",
            "classification"));
  }

  @Override
  public List<PipelineContribution> pipelineContributions() {
    return List.of(
        pipeline(
            "mosquito-localization",
            "Mosquito Localization Pipeline",
            "End-to-end: peak detection, frequency clustering, TDOA, beamforming, Kalman tracking.",
            "peak-detection, frequency-clustering, TDOA, delay-and-sum, Kalman-tracking"),
        pipeline(
            "wingbeat-classification",
            "Wingbeat Classification Pipeline",
            "Feature extraction from a recording followed by rule-based species classification.",
            "feature-extraction, rule-based-classifier, evaluation"));
  }

  @Override
  public List<SnapshotStreamContribution> snapshotStreamContributions() {
    return List.of(
        snapshotStream(
            "tracking-snapshot-stream",
            "Tracking Snapshot Stream",
            "Per-frame TrackingSnapshot values produced by the localization pipeline.",
            "org.hammer.audio.experimental.acoustic.tracking.TrackingSnapshot"),
        snapshotStream(
            "acoustic-localization-snapshot-stream",
            "Acoustic Localization Snapshot Stream",
            "Per-frame AcousticLocalizationSnapshot values with frequency clusters and positions.",
            "org.hammer.audio.experimental.acoustic.AcousticLocalizationSnapshot"));
  }

  @Override
  public List<VisualizationContribution> visualizationContributions() {
    return List.of(
        visualization(
            "room-map-2d",
            "2D Room Map",
            "Top-down 2D view of the room with microphones, ground-truth and tracked positions.",
            "2d-spatial"),
        visualization(
            "frequency-cluster-timeline",
            "Frequency Cluster Timeline",
            "Time-series of detected frequency clusters per frame.",
            "time-series"),
        visualization(
            "tracking-log",
            "Tracking Log",
            "Per-frame text log of track IDs, positions, confidence and Doppler estimates.",
            "table"),
        visualization(
            "classification-confusion-matrix",
            "Classification Confusion Matrix",
            "Ground-truth vs predicted label grid from HumBugDB classification.",
            "table"));
  }

  @Override
  public List<CalibrationContribution> calibrationContributions() {
    return List.of(
        calibration(
            "generator-calibration",
            "Generator Calibration",
            "Tunes generator parameters to match real HumBugDB recording statistics.",
            "generator"),
        calibration(
            "feature-normalization",
            "Feature Normalization",
            "Normalizes WingbeatFeatureVector statistics from real corpus for classification.",
            "feature-normalization"));
  }

  @Override
  public List<BenchmarkContribution> benchmarkContributions() {
    return List.of(
        benchmark(
            "localization-position-error",
            "Localization Position Error",
            "Mean / median / 95th-percentile Euclidean position error against ground truth.",
            "metres"),
        benchmark(
            "doppler-velocity-error",
            "Doppler Velocity Error",
            "Mean absolute error between estimated and true radial velocity.",
            "metres/second"),
        benchmark(
            "classification-accuracy",
            "Classification Accuracy",
            "Overall accuracy and per-label precision/recall from HumBugDB evaluation.",
            "percent"),
        benchmark(
            "processing-latency",
            "Processing Latency",
            "Frame processing time relative to the real-time audio block budget.",
            "milliseconds"));
  }

  @Override
  public List<ExportFormatContribution> exportFormatContributions() {
    return List.of(
        exportFormat(
            "markdown-report",
            "Markdown Report",
            "md",
            "Full workbench run report with summary table, per-frame log and metrics."),
        exportFormat(
            "csv-tracks",
            "CSV Track Table",
            "csv",
            "Per-frame track positions, velocities and confidence as a flat CSV table."),
        exportFormat(
            "jsonl-snapshots",
            "JSON-lines Snapshots",
            "jsonl",
            "One JSON object per frame snapshot, suitable for offline analysis pipelines."));
  }

  @Override
  public List<MenuContribution> menuContributions() {
    return List.of(
        new MenuContribution() {
          @Override
          public String id() {
            return "log-info";
          }

          @Override
          public String label() {
            return "Log plugin info";
          }

          @Override
          public Runnable action() {
            return () ->
                java.util.logging.Logger.getLogger(AcousticLocalizationPlugin.class.getName())
                    .info(
                        () ->
                            "Acoustic Localization plugin active: see "
                                + DESCRIPTOR.documentationPath());
          }
        });
  }

  @Override
  public List<ViewContribution> viewContributions() {
    return List.of(
        new ViewContribution() {
          @Override
          public String id() {
            return "acoustic-localization-workbench";
          }

          @Override
          public String title() {
            return "Acoustic Localization Workbench (experimental)";
          }

          @Override
          public java.util.function.Supplier<javax.swing.JComponent> componentFactory() {
            return AcousticLocalizationWorkbenchPanel::new;
          }
        },
        new ViewContribution() {
          @Override
          public String id() {
            return "acoustic-localization-overview";
          }

          @Override
          public String title() {
            return "Acoustic Localization (overview)";
          }

          @Override
          public java.util.function.Supplier<JPanel> componentFactory() {
            return AcousticLocalizationPlugin::createOverviewPanel;
          }
        },
        new ViewContribution() {
          @Override
          public String id() {
            return "imported-recording-workbench";
          }

          @Override
          public String title() {
            return "Imported Recording Workbench (experimental)";
          }

          @Override
          public java.util.function.Supplier<javax.swing.JComponent> componentFactory() {
            return ImportedRecordingWorkbenchPanel::new;
          }
        });
  }

  private static JPanel createOverviewPanel() {
    JPanel panel = new JPanel(new BorderLayout(8, 8));
    panel.add(new JLabel("Experimental Acoustic Localization plugin"), BorderLayout.NORTH);
    JTextArea text = new JTextArea();
    text.setEditable(false);
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setText(
        """
        This panel is contributed by the acoustic-localization plugin.

        The plugin provides experimental wingbeat frequency tracking, GCC-PHAT /
        cross-correlation TDOA estimators, delay-and-sum beamforming and a 2D
        room simulator. The tracking pipeline also estimates Doppler radial
        velocity, frequency shift and a smoothed velocity vector per source.
        A companion imported-recording workbench can load a local HumBugDB
        export, browse imported clips and replay feature extraction /
        classification on one selected recording.
        See docs/plugins/acoustic-localization.md for details and limitations.

        Plugin-specific views, analyzers and demo signals are loaded dynamically
        by the host through the audio-plugin-api ServiceLoader contract; the
        main application does not depend on this code at compile time.
        """);
    panel.add(new JScrollPane(text), BorderLayout.CENTER);
    return panel;
  }

  private static AnalysisContribution contribution(String id, String description) {
    return new AnalysisContribution() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public String description() {
        return description;
      }
    };
  }

  private static DemoSignalContribution demo(String id, String label, String description) {
    return new DemoSignalContribution() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public String label() {
        return label;
      }

      @Override
      public String description() {
        return description;
      }
    };
  }

  private static SignalSourceContribution signalSource(
      String id, String name, String description, String category) {
    return new SignalSourceContribution() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public String category() {
        return category;
      }
    };
  }

  private static ExperimentContribution experiment(
      String id, String name, String description, String category) {
    return new ExperimentContribution() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public String category() {
        return category;
      }
    };
  }

  private static PipelineContribution pipeline(
      String id, String name, String description, String stages) {
    return new PipelineContribution() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public String stages() {
        return stages;
      }
    };
  }

  private static SnapshotStreamContribution snapshotStream(
      String id, String name, String description, String snapshotTypeName) {
    return new SnapshotStreamContribution() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public String snapshotTypeName() {
        return snapshotTypeName;
      }
    };
  }

  private static VisualizationContribution visualization(
      String id, String name, String description, String renderKind) {
    return new VisualizationContribution() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public String renderKind() {
        return renderKind;
      }
    };
  }

  private static CalibrationContribution calibration(
      String id, String name, String description, String category) {
    return new CalibrationContribution() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public String category() {
        return category;
      }
    };
  }

  private static BenchmarkContribution benchmark(
      String id, String name, String description, String unit) {
    return new BenchmarkContribution() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public String unit() {
        return unit;
      }
    };
  }

  private static ExportFormatContribution exportFormat(
      String id, String name, String fileExtension, String description) {
    return new ExportFormatContribution() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public String fileExtension() {
        return fileExtension;
      }

      @Override
      public String description() {
        return description;
      }
    };
  }
}
