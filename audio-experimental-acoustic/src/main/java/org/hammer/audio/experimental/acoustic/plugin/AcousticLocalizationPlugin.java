package org.hammer.audio.experimental.acoustic.plugin;

import java.awt.BorderLayout;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import org.hammer.audio.experimental.acoustic.workbench.AcousticLocalizationWorkbenchPanel;
import org.hammer.audio.plugin.AnalysisContribution;
import org.hammer.audio.plugin.AudioAnalyzerPlugin;
import org.hammer.audio.plugin.DemoSignalContribution;
import org.hammer.audio.plugin.MenuContribution;
import org.hammer.audio.plugin.PluginDescriptor;
import org.hammer.audio.plugin.ViewContribution;

/**
 * Reference plugin that exposes the experimental acoustic-localization research code (wingbeat
 * frequency tracking, TDOA estimators, delay-and-sum beamforming and the 2D room simulator) to the
 * host application as a set of contributions.
 *
 * <p>This class deliberately only describes contributions through the stable plugin API. The
 * concrete DSP types remain in the {@code org.hammer.audio.experimental.acoustic} packages so the
 * stable plugin API does not need to depend on any audio-domain module.
 */
public final class AcousticLocalizationPlugin implements AudioAnalyzerPlugin {

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
}
