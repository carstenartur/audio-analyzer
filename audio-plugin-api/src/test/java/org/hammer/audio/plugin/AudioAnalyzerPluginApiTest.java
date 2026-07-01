package org.hammer.audio.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.swing.JLabel;
import org.junit.jupiter.api.Test;

class AudioAnalyzerPluginApiTest {

  @Test
  void pluginDescriptorRejectsBlankRequiredFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginDescriptor("", "name", "1.0", "desc", null, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginDescriptor("id", " ", "1.0", "desc", null, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginDescriptor("id", "name", "", "desc", null, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginDescriptor("id", "name", "1.0", " ", null, false));
  }

  @Test
  void pluginDescriptorPreservesValues() {
    PluginDescriptor d =
        new PluginDescriptor("p", "Plugin", "1.2.3", "does things", "docs/p.md", true);
    assertEquals("p", d.id());
    assertEquals("Plugin", d.name());
    assertEquals("1.2.3", d.version());
    assertEquals("does things", d.description());
    assertEquals("docs/p.md", d.documentationPath());
    assertTrue(d.experimental());
  }

  @Test
  void pluginDefaultsReturnEmptyContributionLists() {
    AudioAnalyzerPlugin plugin = () -> new PluginDescriptor("x", "X", "1", "desc", null, false);
    assertTrue(plugin.analysisContributions().isEmpty());
    assertTrue(plugin.viewContributions().isEmpty());
    assertTrue(plugin.menuContributions().isEmpty());
    assertTrue(plugin.demoSignalContributions().isEmpty());
    assertTrue(plugin.signalSourceContributions().isEmpty());
    assertTrue(plugin.experimentContributions().isEmpty());
    assertTrue(plugin.pipelineContributions().isEmpty());
    assertTrue(plugin.snapshotStreamContributions().isEmpty());
    assertTrue(plugin.visualizationContributions().isEmpty());
    assertTrue(plugin.calibrationContributions().isEmpty());
    assertTrue(plugin.benchmarkContributions().isEmpty());
    assertTrue(plugin.exportFormatContributions().isEmpty());
    assertFalse(plugin.descriptor().experimental());
  }

  @Test
  void viewContributionInvokesFactoryOnDemand() {
    ViewContribution view =
        new ViewContribution() {
          @Override
          public String id() {
            return "v";
          }

          @Override
          public String title() {
            return "View";
          }

          @Override
          public java.util.function.Supplier<JLabel> componentFactory() {
            return () -> new JLabel("hello");
          }
        };
    assertEquals("hello", ((JLabel) view.componentFactory().get()).getText());
    // Two invocations must produce distinct instances.
    assertNotSame(view.componentFactory().get(), view.componentFactory().get());
  }

  @Test
  void menuContributionActionIsInvokable() {
    boolean[] fired = {false};
    MenuContribution menu =
        new MenuContribution() {
          @Override
          public String id() {
            return "m";
          }

          @Override
          public String label() {
            return "Menu";
          }

          @Override
          public Runnable action() {
            return () -> fired[0] = true;
          }
        };
    menu.action().run();
    assertTrue(fired[0]);
  }

  @Test
  void demoSignalAndAnalysisContributionsExposeMetadata() {
    DemoSignalContribution demo =
        new DemoSignalContribution() {
          @Override
          public String id() {
            return "demo";
          }

          @Override
          public String label() {
            return "Demo";
          }

          @Override
          public String description() {
            return "desc";
          }
        };
    assertEquals("demo", demo.id());
    AnalysisContribution analysis =
        new AnalysisContribution() {
          @Override
          public String id() {
            return "a";
          }

          @Override
          public String description() {
            return "d";
          }
        };
    assertEquals(List.of("a", "d"), List.of(analysis.id(), analysis.description()));
  }

  @Test
  void signalSourceContributionExposesAllFields() {
    SignalSourceContribution src =
        new SignalSourceContribution() {
          @Override
          public String id() {
            return "mic-array";
          }

          @Override
          public String name() {
            return "4-mic array";
          }

          @Override
          public String description() {
            return "Square four-microphone array.";
          }

          @Override
          public String category() {
            return "microphone";
          }
        };
    assertEquals("mic-array", src.id());
    assertEquals("4-mic array", src.name());
    assertEquals("Square four-microphone array.", src.description());
    assertEquals("microphone", src.category());
  }

  @Test
  void experimentContributionExposesAllFields() {
    ExperimentContribution exp =
        new ExperimentContribution() {
          @Override
          public String id() {
            return "exp-1";
          }

          @Override
          public String name() {
            return "Single source";
          }

          @Override
          public String description() {
            return "Localize one static emitter.";
          }

          @Override
          public String category() {
            return "localization";
          }
        };
    assertEquals("exp-1", exp.id());
    assertEquals("Single source", exp.name());
    assertEquals("Localize one static emitter.", exp.description());
    assertEquals("localization", exp.category());
  }

  @Test
  void pipelineContributionExposesAllFields() {
    PipelineContribution pipe =
        new PipelineContribution() {
          @Override
          public String id() {
            return "dsp-chain";
          }

          @Override
          public String name() {
            return "DSP chain";
          }

          @Override
          public String description() {
            return "Peak detection then TDOA.";
          }

          @Override
          public String stages() {
            return "peak-detection, TDOA";
          }
        };
    assertEquals("dsp-chain", pipe.id());
    assertEquals("DSP chain", pipe.name());
    assertEquals("Peak detection then TDOA.", pipe.description());
    assertEquals("peak-detection, TDOA", pipe.stages());
  }

  @Test
  void snapshotStreamContributionExposesAllFields() {
    SnapshotStreamContribution stream =
        new SnapshotStreamContribution() {
          @Override
          public String id() {
            return "tracking-stream";
          }

          @Override
          public String name() {
            return "Tracking stream";
          }

          @Override
          public String description() {
            return "Per-frame tracking results.";
          }

          @Override
          public String snapshotTypeName() {
            return "com.example.TrackingSnapshot";
          }
        };
    assertEquals("tracking-stream", stream.id());
    assertEquals("Tracking stream", stream.name());
    assertEquals("Per-frame tracking results.", stream.description());
    assertEquals("com.example.TrackingSnapshot", stream.snapshotTypeName());
  }

  @Test
  void visualizationContributionExposesAllFields() {
    VisualizationContribution viz =
        new VisualizationContribution() {
          @Override
          public String id() {
            return "room-map";
          }

          @Override
          public String name() {
            return "Room map";
          }

          @Override
          public String description() {
            return "2D top-down room view.";
          }

          @Override
          public String renderKind() {
            return "2d-spatial";
          }
        };
    assertEquals("room-map", viz.id());
    assertEquals("Room map", viz.name());
    assertEquals("2D top-down room view.", viz.description());
    assertEquals("2d-spatial", viz.renderKind());
  }

  @Test
  void calibrationContributionExposesAllFields() {
    CalibrationContribution cal =
        new CalibrationContribution() {
          @Override
          public String id() {
            return "mic-gain";
          }

          @Override
          public String name() {
            return "Microphone gain calibration";
          }

          @Override
          public String description() {
            return "Equalises per-channel sensitivity.";
          }

          @Override
          public String category() {
            return "microphone";
          }
        };
    assertEquals("mic-gain", cal.id());
    assertEquals("Microphone gain calibration", cal.name());
    assertEquals("Equalises per-channel sensitivity.", cal.description());
    assertEquals("microphone", cal.category());
  }

  @Test
  void benchmarkContributionExposesAllFields() {
    BenchmarkContribution bm =
        new BenchmarkContribution() {
          @Override
          public String id() {
            return "pos-error";
          }

          @Override
          public String name() {
            return "Position error";
          }

          @Override
          public String description() {
            return "Mean Euclidean position error.";
          }

          @Override
          public String unit() {
            return "metres";
          }
        };
    assertEquals("pos-error", bm.id());
    assertEquals("Position error", bm.name());
    assertEquals("Mean Euclidean position error.", bm.description());
    assertEquals("metres", bm.unit());
  }

  @Test
  void exportFormatContributionExposesAllFields() {
    ExportFormatContribution fmt =
        new ExportFormatContribution() {
          @Override
          public String id() {
            return "csv-export";
          }

          @Override
          public String name() {
            return "CSV export";
          }

          @Override
          public String fileExtension() {
            return "csv";
          }

          @Override
          public String description() {
            return "Comma-separated values table.";
          }
        };
    assertEquals("csv-export", fmt.id());
    assertEquals("CSV export", fmt.name());
    assertEquals("csv", fmt.fileExtension());
    assertEquals("Comma-separated values table.", fmt.description());
  }
}
