package org.hammer.audio.experimental.acoustic.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.geometry.Vector2;

/**
 * Trajectory ground truth for an acoustic source in a scenario.
 *
 * <p>{@link #timestamps()} and {@link #positions()} are always required and must have equal length.
 * {@link #velocities()} and {@link #orientations()} may be {@code null} for partial ground truth
 * (e.g. a dataset that records positions but not velocities).
 *
 * <p>Use {@link #linear(Vector2, Vector2, double, int)} to construct a uniformly sampled linear
 * trajectory from a start position and constant velocity.
 */
public record ScenarioTrajectory(
    List<Double> timestamps,
    List<Vector2> positions,
    List<Vector2> velocities,
    List<Double> orientations) {

  /** Validate sizes and defensively copy all provided lists. */
  public ScenarioTrajectory {
    Objects.requireNonNull(timestamps, "timestamps");
    Objects.requireNonNull(positions, "positions");
    if (timestamps.isEmpty()) {
      throw new IllegalArgumentException("timestamps must not be empty");
    }
    if (timestamps.size() != positions.size()) {
      throw new IllegalArgumentException(
          "timestamps and positions must have the same size; got "
              + timestamps.size()
              + " vs "
              + positions.size());
    }
    if (velocities != null && velocities.size() != timestamps.size()) {
      throw new IllegalArgumentException(
          "velocities must have the same size as timestamps when provided");
    }
    if (orientations != null && orientations.size() != timestamps.size()) {
      throw new IllegalArgumentException(
          "orientations must have the same size as timestamps when provided");
    }
    double previousTimestamp = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < timestamps.size(); i++) {
      Double timestamp = timestamps.get(i);
      if (timestamp == null || !Double.isFinite(timestamp)) {
        throw new IllegalArgumentException("timestamps must contain only finite values");
      }
      if (i > 0 && timestamp <= previousTimestamp) {
        throw new IllegalArgumentException("timestamps must be strictly increasing");
      }
      previousTimestamp = timestamp;
    }
    if (orientations != null) {
      for (Double orientation : orientations) {
        if (orientation == null || !Double.isFinite(orientation)) {
          throw new IllegalArgumentException("orientations must contain only finite values");
        }
      }
    }
    timestamps = List.copyOf(timestamps);
    positions = List.copyOf(positions);
    velocities = velocities != null ? List.copyOf(velocities) : null;
    orientations = orientations != null ? List.copyOf(orientations) : null;
  }

  /**
   * Create a uniformly sampled linear trajectory.
   *
   * <p>The trajectory is sampled at {@code sampleCount} evenly spaced time steps over {@code
   * durationSeconds}, starting at {@code startPositionMeters} and moving with constant {@code
   * velocityMetersPerSecond}. Velocities are included; orientations are not.
   *
   * @param startPositionMeters initial position in meters
   * @param velocityMetersPerSecond constant 2-D velocity in m/s
   * @param durationSeconds total scenario duration; must be positive
   * @param sampleCount number of sample points; must be at least 2
   */
  public static ScenarioTrajectory linear(
      Vector2 startPositionMeters,
      Vector2 velocityMetersPerSecond,
      double durationSeconds,
      int sampleCount) {
    Objects.requireNonNull(startPositionMeters, "startPositionMeters");
    Objects.requireNonNull(velocityMetersPerSecond, "velocityMetersPerSecond");
    if (!(durationSeconds > 0.0) || !Double.isFinite(durationSeconds)) {
      throw new IllegalArgumentException("durationSeconds must be finite and > 0");
    }
    if (sampleCount < 2) {
      throw new IllegalArgumentException("sampleCount must be >= 2");
    }
    List<Double> timestamps = new ArrayList<>(sampleCount);
    List<Vector2> positions = new ArrayList<>(sampleCount);
    List<Vector2> velocities = new ArrayList<>(sampleCount);
    double step = durationSeconds / (sampleCount - 1);
    for (int i = 0; i < sampleCount; i++) {
      double t = i * step;
      timestamps.add(t);
      positions.add(startPositionMeters.plus(velocityMetersPerSecond.scale(t)));
      velocities.add(velocityMetersPerSecond);
    }
    return new ScenarioTrajectory(timestamps, positions, velocities, null);
  }
}
