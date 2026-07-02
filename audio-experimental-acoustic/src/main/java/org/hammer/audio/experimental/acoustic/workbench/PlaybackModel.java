package org.hammer.audio.experimental.acoustic.workbench;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.experimental.acoustic.tracking.TrackingSnapshot;

/**
 * Playback navigation model for a completed workbench run.
 *
 * <p>Holds a {@link WorkbenchRunResult} and tracks the currently selected frame. Listener
 * notification is synchronous and runs on whatever thread calls the navigation methods. When used
 * from a Swing UI all navigation calls should be made on the Event Dispatch Thread.
 *
 * <p>This class contains no Swing dependency and is therefore headless-testable.
 */
public final class PlaybackModel {

  private final WorkbenchRunResult runResult;
  private int currentFrameIndex;
  private final List<FrameChangeListener> listeners = new ArrayList<>();

  /** Notified when the current frame index changes. */
  @FunctionalInterface
  public interface FrameChangeListener {
    /**
     * Called after the current frame index has changed.
     *
     * @param frameIndex the new 0-based frame index
     */
    void onFrameChanged(int frameIndex);
  }

  /**
   * Create a playback model for the given result. The initial frame is 0 (or irrelevant for an
   * empty result).
   *
   * @param result the completed run result; must not be {@code null}
   */
  public PlaybackModel(WorkbenchRunResult result) {
    this.runResult = Objects.requireNonNull(result, "result");
    this.currentFrameIndex = 0;
  }

  /** The underlying run result. */
  public WorkbenchRunResult result() {
    return runResult;
  }

  /** Total number of frames (snapshots) in the run. */
  public int frameCount() {
    return runResult.snapshots().size();
  }

  /** {@code true} if the run produced no snapshots. */
  public boolean isEmpty() {
    return runResult.snapshots().isEmpty();
  }

  /** Current 0-based frame index. Always {@code 0} for an empty run. */
  public int currentFrame() {
    return currentFrameIndex;
  }

  /**
   * Snapshot for the current frame, or {@code null} if the run is empty.
   *
   * @return current {@link TrackingSnapshot}, or {@code null}
   */
  public TrackingSnapshot currentSnapshot() {
    if (isEmpty()) {
      return null;
    }
    return runResult.snapshots().get(currentFrameIndex);
  }

  /**
   * Seek to the given frame index. The value is silently clamped to {@code [0, frameCount()-1]}.
   * Listeners are notified only when the index actually changes. No-op for an empty run.
   *
   * @param frameIndex desired 0-based frame index
   */
  public void seekTo(int frameIndex) {
    if (isEmpty()) {
      return;
    }
    int clamped = Math.max(0, Math.min(frameIndex, frameCount() - 1));
    if (clamped != currentFrameIndex) {
      currentFrameIndex = clamped;
      notifyListeners();
    }
  }

  /**
   * Advance to the next frame. No-op when already at the last frame or the run is empty.
   *
   * @return {@code true} if the frame index changed
   */
  public boolean stepForward() {
    if (isEmpty() || currentFrameIndex >= frameCount() - 1) {
      return false;
    }
    currentFrameIndex++;
    notifyListeners();
    return true;
  }

  /**
   * Go back to the previous frame. No-op when already at frame 0 or the run is empty.
   *
   * @return {@code true} if the frame index changed
   */
  public boolean stepBack() {
    if (isEmpty() || currentFrameIndex <= 0) {
      return false;
    }
    currentFrameIndex--;
    notifyListeners();
    return true;
  }

  /** Seek to the first frame (index 0). No-op for an empty run. */
  public void first() {
    seekTo(0);
  }

  /** Seek to the last frame. No-op for an empty run. */
  public void last() {
    if (!isEmpty()) {
      seekTo(frameCount() - 1);
    }
  }

  /** {@code true} if the current frame is the first frame (or the run is empty). */
  public boolean isAtFirst() {
    return isEmpty() || currentFrameIndex == 0;
  }

  /** {@code true} if the current frame is the last frame (or the run is empty). */
  public boolean isAtLast() {
    return isEmpty() || currentFrameIndex == frameCount() - 1;
  }

  /**
   * Register a listener for frame changes.
   *
   * @param listener must not be {@code null}
   */
  public void addListener(FrameChangeListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener"));
  }

  /**
   * Unregister a previously registered listener.
   *
   * @param listener listener to remove; ignored if not registered
   */
  public void removeListener(FrameChangeListener listener) {
    listeners.remove(listener);
  }

  private void notifyListeners() {
    for (FrameChangeListener listener : listeners) {
      listener.onFrameChanged(currentFrameIndex);
    }
  }
}
