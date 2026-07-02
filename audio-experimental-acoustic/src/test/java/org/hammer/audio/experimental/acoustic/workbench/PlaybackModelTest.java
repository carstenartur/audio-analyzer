package org.hammer.audio.experimental.acoustic.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;
import org.hammer.audio.experimental.acoustic.tracking.TrackingSnapshot;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link PlaybackModel}: snapshot navigation is deterministic and handles empty
 * runs gracefully.
 */
class PlaybackModelTest {

  // -------------------------------------------------------------------------
  // Empty run tests
  // -------------------------------------------------------------------------

  @Test
  void emptyRunIsDetected() {
    WorkbenchRunResult empty = emptyResult();
    PlaybackModel model = new PlaybackModel(empty);

    assertTrue(model.isEmpty(), "model must be empty for a run with no snapshots");
    assertEquals(0, model.frameCount(), "frameCount must be 0 for empty run");
    assertEquals(0, model.currentFrame(), "currentFrame must be 0 for empty run");
    assertNull(model.currentSnapshot(), "currentSnapshot must be null for empty run");
  }

  @Test
  void navigationOnEmptyRunIsNoOp() {
    WorkbenchRunResult empty = emptyResult();
    PlaybackModel model = new PlaybackModel(empty);

    // All navigation calls must not throw and must not change the frame
    model.first();
    model.last();
    model.stepForward();
    model.stepBack();
    model.seekTo(5);

    assertEquals(0, model.currentFrame());
    assertTrue(model.isEmpty());
  }

  @Test
  void stepForwardOnEmptyRunReturnsFalse() {
    PlaybackModel model = new PlaybackModel(emptyResult());
    assertFalse(model.stepForward(), "stepForward must return false for empty run");
  }

  @Test
  void stepBackOnEmptyRunReturnsFalse() {
    PlaybackModel model = new PlaybackModel(emptyResult());
    assertFalse(model.stepBack(), "stepBack must return false for empty run");
  }

  @Test
  void isAtFirstAndIsAtLastTrueForEmptyRun() {
    PlaybackModel model = new PlaybackModel(emptyResult());
    assertTrue(model.isAtFirst(), "isAtFirst must be true for empty run");
    assertTrue(model.isAtLast(), "isAtLast must be true for empty run");
  }

  // -------------------------------------------------------------------------
  // Non-empty run – basic navigation
  // -------------------------------------------------------------------------

  @Test
  void nonEmptyRunHasCorrectFrameCount() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);

    assertFalse(model.isEmpty());
    assertEquals(result.snapshots().size(), model.frameCount());
  }

  @Test
  void initialFrameIsZero() {
    PlaybackModel model = new PlaybackModel(runScenario());
    assertEquals(0, model.currentFrame());
    assertNotNull(model.currentSnapshot());
  }

  @Test
  void firstAndLastNavigateToCorrectFrames() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);

    model.last();
    assertEquals(model.frameCount() - 1, model.currentFrame(), "last() must go to final frame");
    assertNotNull(model.currentSnapshot());

    model.first();
    assertEquals(0, model.currentFrame(), "first() must go to frame 0");
  }

  @Test
  void stepForwardAdvancesOneFrame() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);

    boolean advanced = model.stepForward();
    assertTrue(advanced, "stepForward must return true when not at last frame");
    assertEquals(1, model.currentFrame());
  }

  @Test
  void stepForwardAtLastFrameReturnsFalse() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);
    model.last();

    boolean advanced = model.stepForward();
    assertFalse(advanced, "stepForward must return false when already at last frame");
    assertEquals(model.frameCount() - 1, model.currentFrame());
  }

  @Test
  void stepBackAtFirstFrameReturnsFalse() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);

    boolean moved = model.stepBack();
    assertFalse(moved, "stepBack must return false when at first frame");
    assertEquals(0, model.currentFrame());
  }

  @Test
  void stepBackGoesBackOneFrame() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);
    model.last();
    int lastIdx = model.currentFrame();

    boolean moved = model.stepBack();
    assertTrue(moved, "stepBack must return true when not at first frame");
    assertEquals(lastIdx - 1, model.currentFrame());
  }

  @Test
  void seekToClampsBelowZero() {
    PlaybackModel model = new PlaybackModel(runScenario());
    model.seekTo(-99);
    assertEquals(0, model.currentFrame(), "seekTo must clamp negative values to 0");
  }

  @Test
  void seekToClampsAboveMax() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);
    model.seekTo(Integer.MAX_VALUE);
    assertEquals(model.frameCount() - 1, model.currentFrame(), "seekTo must clamp to last frame");
  }

  @Test
  void seekToMiddleFrame() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);
    int mid = model.frameCount() / 2;
    model.seekTo(mid);
    assertEquals(mid, model.currentFrame());
    assertNotNull(model.currentSnapshot());
  }

  // -------------------------------------------------------------------------
  // Determinism
  // -------------------------------------------------------------------------

  @Test
  void navigationIsDeterministic() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel m1 = new PlaybackModel(result);
    PlaybackModel m2 = new PlaybackModel(result);

    // Same navigation steps must produce same snapshots
    m1.stepForward();
    m1.stepForward();
    m2.seekTo(2);

    assertEquals(m1.currentFrame(), m2.currentFrame());
    TrackingSnapshot s1 = m1.currentSnapshot();
    TrackingSnapshot s2 = m2.currentSnapshot();
    assertNotNull(s1);
    assertNotNull(s2);
    assertEquals(
        s1.sourceFrameIndex(),
        s2.sourceFrameIndex(),
        "source frame indices must match for the same frame index");
    assertEquals(
        s1.tracks().size(), s2.tracks().size(), "track counts must match for the same frame index");
  }

  @Test
  void fullForwardWalkReachesLastFrame() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);
    int steps = 0;
    while (!model.isAtLast()) {
      model.stepForward();
      steps++;
    }
    assertEquals(model.frameCount() - 1, model.currentFrame());
    assertEquals(model.frameCount() - 1, steps, "step count must equal frameCount - 1");
  }

  @Test
  void fullBackwardWalkReachesFirstFrame() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);
    model.last();
    while (!model.isAtFirst()) {
      model.stepBack();
    }
    assertEquals(0, model.currentFrame());
  }

  // -------------------------------------------------------------------------
  // Listener tests
  // -------------------------------------------------------------------------

  @Test
  void listenerIsNotifiedOnSeekTo() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);
    List<Integer> notified = new ArrayList<>();
    model.addListener(notified::add);

    model.seekTo(1);
    assertEquals(List.of(1), notified, "listener must receive the new frame index");
  }

  @Test
  void listenerNotCalledWhenFrameUnchanged() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);
    List<Integer> notified = new ArrayList<>();
    model.addListener(notified::add);

    model.seekTo(0); // already at 0 — no change
    assertTrue(notified.isEmpty(), "listener must not be called when frame does not change");
  }

  @Test
  void listenerCanBeRemoved() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);
    List<Integer> notified = new ArrayList<>();
    PlaybackModel.FrameChangeListener listener = notified::add;
    model.addListener(listener);
    model.removeListener(listener);

    model.stepForward();
    assertTrue(notified.isEmpty(), "removed listener must not be called");
  }

  @Test
  void stepForwardNotifiesListener() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);
    List<Integer> notified = new ArrayList<>();
    model.addListener(notified::add);

    model.stepForward();
    assertFalse(notified.isEmpty(), "listener must be called after stepForward");
    assertEquals(1, notified.get(0), "notified frame must be 1 after first stepForward");
  }

  @Test
  void stepBackNotifiesListener() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);
    model.last();

    List<Integer> notified = new ArrayList<>();
    model.addListener(notified::add);

    model.stepBack();
    assertFalse(notified.isEmpty(), "listener must be called after stepBack");
  }

  // -------------------------------------------------------------------------
  // Snapshot content tests
  // -------------------------------------------------------------------------

  @Test
  void currentSnapshotMatchesExpectedSnapshot() {
    WorkbenchRunResult result = runScenario();
    PlaybackModel model = new PlaybackModel(result);

    for (int i = 0; i < result.snapshots().size(); i++) {
      model.seekTo(i);
      TrackingSnapshot expected = result.snapshots().get(i);
      TrackingSnapshot actual = model.currentSnapshot();
      assertNotNull(actual, "snapshot at frame " + i + " must not be null");
      assertEquals(
          expected.sourceFrameIndex(),
          actual.sourceFrameIndex(),
          "sourceFrameIndex must match at frame " + i);
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static WorkbenchRunResult emptyResult() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    return new WorkbenchRunResult(
        scenario, WorkbenchParameters.defaults().build(), List.of(), 0L, null);
  }

  private static WorkbenchRunResult runScenario() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    return WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());
  }
}
