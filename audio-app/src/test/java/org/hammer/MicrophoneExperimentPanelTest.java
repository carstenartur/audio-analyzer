package org.hammer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import org.hammer.audio.acquisition.health.ExperimentHealthSnapshot;
import org.hammer.audio.acquisition.health.ExperimentHealthState;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.Catalogue;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.RunView;
import org.hammer.audio.experiment.operations.MicrophoneExperimentService;
import org.hammer.audio.recording.runtime.RecordingState;
import org.hammer.audio.recording.runtime.RecordingStatus;
import org.hammer.audio.recording.runtime.RecordingStorageLevel;
import org.hammer.audio.recording.runtime.RecordingStoragePolicy;
import org.hammer.audio.recording.runtime.RecordingStorageStatus;
import org.junit.jupiter.api.Test;

class MicrophoneExperimentPanelTest {

  private static final Instant NOW = Instant.parse("2026-11-20T12:00:02Z");

  @Test
  void persistentRuntimeUsesActualRecorderMetricsWithoutDependingOnHealthFacts() throws Exception {
    var current = new AtomicReference<>(snapshot(RecordingState.RECORDING));
    MicrophoneExperimentPanel panel = panel(current);
    try {
      String text = awaitStatus(panel, "RUNNING");
      assertTrue(text.contains("capture.aarec"), text);
      assertTrue(text.contains("00:00:02"), text);
      assertTrue(text.contains("128.0 KiB/s"), text);
      assertTrue(text.contains("500.0 KiB"), text);
      assertTrue(text.contains("8.0 GiB"), text);
      assertTrue(text.contains("09:14:09"), text);
      assertTrue(text.contains("32000 / 32256 frames"), text);
      assertTrue(text.contains("125 / 126 blocks"), text);
      assertTrue(text.contains("Queue: 1 / 64"), text);
      assertTrue(text.contains("Dropped blocks / frames: 0 / 0"), text);
      assertTrue(text.contains("Continuity gaps: 0"), text);
      assertTrue(allText(panel).contains("Last successful capacity check"));
      assertPersistentTextFits(panel);
    } finally {
      SwingUtilities.invokeAndWait(panel::close);
    }
  }

  @Test
  void finalIncompleteRecordingKeepsLossCountsAndReasonVisible() throws Exception {
    var current = new AtomicReference<>(snapshot(RecordingState.INCOMPLETE));
    MicrophoneExperimentPanel panel = panel(current);
    try {
      String text = awaitStatus(panel, "INCOMPLETE");
      assertTrue(text.contains("31744 / 32000 frames"), text);
      assertTrue(text.contains("124 / 125 blocks"), text);
      assertTrue(text.contains("Queue: 0 / 64"), text);
      assertTrue(text.contains("Dropped blocks / frames: 1 / 256"), text);
      assertTrue(text.contains("Continuity gaps: 1"), text);
      assertTrue(allText(panel).contains("Storage reserve reached"));
      assertFalse(text.contains("COMPLETED"), text);
    } finally {
      SwingUtilities.invokeAndWait(panel::close);
    }
  }

  @Test
  void newSnapshotWithoutRecorderClearsPreviousRecordingMetrics() throws Exception {
    var current = new AtomicReference<>(snapshot(RecordingState.RECORDING));
    MicrophoneExperimentPanel panel = panel(current);
    try {
      assertTrue(awaitStatus(panel, "RUNNING").contains("capture.aarec"));
      current.set(
          new RunView("", health(ExperimentHealthState.NOT_CONFIGURED), null, List.of(), false));
      String text = awaitStatus(panel, "NOT_CONFIGURED");
      assertFalse(text.contains("capture.aarec"), text);
      assertFalse(text.contains("128.0 KiB/s"), text);
    } finally {
      SwingUtilities.invokeAndWait(panel::close);
    }
  }

  private static MicrophoneExperimentPanel panel(AtomicReference<RunView> current)
      throws Exception {
    MicrophoneExperimentService service = mock(MicrophoneExperimentService.class);
    when(service.catalogue()).thenReturn(new Catalogue(List.of(), List.of(), List.of(), List.of()));
    when(service.snapshot()).thenAnswer(invocation -> current.get());
    var result = new AtomicReference<MicrophoneExperimentPanel>();
    SwingUtilities.invokeAndWait(() -> result.set(new MicrophoneExperimentPanel(service)));
    return result.get();
  }

  private static String awaitStatus(MicrophoneExperimentPanel panel, String expected)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
    var result = new AtomicReference<>("");
    while (System.nanoTime() < deadline) {
      SwingUtilities.invokeAndWait(
          () ->
              result.set(
                  text(((BorderLayout) panel.getLayout()).getLayoutComponent(BorderLayout.NORTH))));
      if (result.get().contains(expected)) {
        return result.get();
      }
      Thread.sleep(20L);
    }
    assertTrue(result.get().contains(expected), result.get());
    return result.get();
  }

  private static String allText(Container parent) throws Exception {
    var result = new AtomicReference<>("");
    SwingUtilities.invokeAndWait(() -> result.set(text(parent)));
    return result.get();
  }

  private static void assertPersistentTextFits(MicrophoneExperimentPanel panel) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          panel.setSize(1000, 760);
          layout(panel);
          JScrollPane scroll =
              (JScrollPane)
                  ((BorderLayout) panel.getLayout()).getLayoutComponent(BorderLayout.NORTH);
          JTextComponent view = (JTextComponent) scroll.getViewport().getView();
          try {
            var lastCharacter = view.modelToView2D(view.getText().length() - 1);
            assertTrue(
                lastCharacter.getMaxY() <= scroll.getViewport().getHeight(),
                "The persistent metrics must fit at the normal window width, without hidden wrapped"
                    + " rows");
          } catch (BadLocationException exception) {
            throw new AssertionError(exception);
          }
        });
  }

  private static void layout(Container parent) {
    parent.doLayout();
    for (Component component : parent.getComponents()) {
      if (component instanceof Container child) {
        layout(child);
      }
    }
  }

  private static String text(Component component) {
    if (component instanceof JTextComponent text) {
      return text.getText();
    }
    if (component instanceof JLabel label) {
      return label.getText();
    }
    if (component instanceof Container container) {
      StringBuilder text = new StringBuilder();
      for (Component child : container.getComponents()) {
        text.append(text(child)).append('\n');
      }
      return text.toString();
    }
    return "";
  }

  private static RunView snapshot(RecordingState state) {
    boolean incomplete = state == RecordingState.INCOMPLETE;
    Path destination = Path.of("runs", "example", "capture.aarec");
    var storage =
        new RecordingStorageStatus(
            destination,
            "test-volume",
            "test-fs",
            true,
            8L << 30,
            8L << 30,
            16L << 30,
            512_000L,
            131_072.0,
            256_313.0,
            33_249L,
            RecordingStorageLevel.NORMAL,
            NOW,
            "",
            RecordingStoragePolicy.configured(),
            NOW,
            List.of());
    var recording =
        new RecordingStatus(
            state,
            destination,
            NOW.minusSeconds(2),
            NOW,
            incomplete ? 125L : 126L,
            incomplete ? 124L : 125L,
            incomplete ? 32_000L : 32_256L,
            incomplete ? 31_744L : 32_000L,
            incomplete ? 1L : 0L,
            incomplete ? 256L : 0L,
            incomplete ? 1L : 0L,
            incomplete ? 0 : 1,
            64,
            4,
            incomplete ? 507_904L : 512_000L,
            131_072.0,
            storage,
            incomplete ? "Storage reserve reached" : "",
            "");
    return new RunView(
        "run-example",
        health(incomplete ? ExperimentHealthState.INCOMPLETE : ExperimentHealthState.RUNNING),
        recording,
        List.of(),
        incomplete);
  }

  private static ExperimentHealthSnapshot health(ExperimentHealthState state) {
    return new ExperimentHealthSnapshot(
        "run-example",
        "Example",
        state,
        "ACQUIRING / LOCALIZING",
        NOW,
        List.of(),
        Map.of("intended.seconds", "5", "storage.expectedBytesPerSecond", "1"),
        List.of());
  }
}
