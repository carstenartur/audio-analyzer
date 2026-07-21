package org.hammer.audio.experimental.acoustic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.experimental.acoustic.DelayAndSumBeamformer.BeamformingPoint;
import org.hammer.audio.geometry.Vector2;

/** Coarse-to-fine candidate refinement over the existing delay-and-sum beamforming baseline. */
public final class AdaptiveBeamformingSearch {

  private final DelayAndSumBeamformer beamformer;

  /** Creates an adaptive search over one interchangeable beamforming scorer. */
  public AdaptiveBeamformingSearch(DelayAndSumBeamformer beamformer) {
    this.beamformer = Objects.requireNonNull(beamformer, "beamformer");
  }

  /**
   * Searches the initial bounds and repeatedly refines the cell surrounding the current best point.
   */
  public BeamformingSearchResult search(
      AudioBlock block,
      MicrophoneArray array,
      SearchBounds initialBounds,
      int stepsPerAxis,
      int refinementLevels) {
    Objects.requireNonNull(block, "block");
    Objects.requireNonNull(array, "array");
    Objects.requireNonNull(initialBounds, "initialBounds");
    if (stepsPerAxis < 2) {
      throw new IllegalArgumentException("stepsPerAxis must be >= 2");
    }
    if (refinementLevels < 1) {
      throw new IllegalArgumentException("refinementLevels must be >= 1");
    }

    SearchBounds currentBounds = initialBounds;
    List<BeamformingPoint> evaluated = new ArrayList<>();
    BeamformingPoint best = null;
    for (int level = 0; level < refinementLevels; level++) {
      List<BeamformingPoint> levelPoints =
          beamformer.scan(block, array, currentBounds.grid(stepsPerAxis));
      evaluated.addAll(levelPoints);
      best = bestPoint(levelPoints);
      double xRadius = currentBounds.width() / stepsPerAxis;
      double yRadius = currentBounds.height() / stepsPerAxis;
      currentBounds = initialBounds.around(best.positionMeters(), xRadius, yRadius);
    }
    return new BeamformingSearchResult(best, evaluated, refinementLevels, stepsPerAxis);
  }

  private static BeamformingPoint bestPoint(List<BeamformingPoint> points) {
    BeamformingPoint best = null;
    for (BeamformingPoint point : points) {
      if (best == null || point.energy() > best.energy()) {
        best = point;
      }
    }
    if (best == null) {
      throw new IllegalArgumentException("points must not be empty");
    }
    return best;
  }

  /** Rectangular search region in meters. */
  public record SearchBounds(double minimumX, double maximumX, double minimumY, double maximumY) {

    // Validate finite ordered bounds.
    public SearchBounds {
      if (!Double.isFinite(minimumX)
          || !Double.isFinite(maximumX)
          || !Double.isFinite(minimumY)
          || !Double.isFinite(maximumY)) {
        throw new IllegalArgumentException("search bounds must be finite");
      }
      if (minimumX >= maximumX || minimumY >= maximumY) {
        throw new IllegalArgumentException("search bounds must have positive width and height");
      }
    }

    /** Region width in meters. */
    public double width() {
      return maximumX - minimumX;
    }

    /** Region height in meters. */
    public double height() {
      return maximumY - minimumY;
    }

    /** Returns an inclusive regular grid over this region. */
    public List<Vector2> grid(int stepsPerAxis) {
      if (stepsPerAxis < 1) {
        throw new IllegalArgumentException("stepsPerAxis must be >= 1");
      }
      List<Vector2> points = new ArrayList<>((stepsPerAxis + 1) * (stepsPerAxis + 1));
      for (int xIndex = 0; xIndex <= stepsPerAxis; xIndex++) {
        for (int yIndex = 0; yIndex <= stepsPerAxis; yIndex++) {
          points.add(
              new Vector2(
                  minimumX + width() * xIndex / stepsPerAxis,
                  minimumY + height() * yIndex / stepsPerAxis));
        }
      }
      return List.copyOf(points);
    }

    /** Returns a clipped refinement region around one selected point. */
    public SearchBounds around(Vector2 center, double xRadius, double yRadius) {
      Objects.requireNonNull(center, "center");
      if (!(xRadius > 0.0) || !(yRadius > 0.0)) {
        throw new IllegalArgumentException("refinement radii must be > 0");
      }
      double clippedMinimumX = Math.max(minimumX, center.x() - xRadius);
      double clippedMaximumX = Math.min(maximumX, center.x() + xRadius);
      double clippedMinimumY = Math.max(minimumY, center.y() - yRadius);
      double clippedMaximumY = Math.min(maximumY, center.y() + yRadius);
      if (clippedMinimumX == clippedMaximumX) {
        clippedMinimumX = Math.max(minimumX, clippedMinimumX - xRadius);
        clippedMaximumX = Math.min(maximumX, clippedMaximumX + xRadius);
      }
      if (clippedMinimumY == clippedMaximumY) {
        clippedMinimumY = Math.max(minimumY, clippedMinimumY - yRadius);
        clippedMaximumY = Math.min(maximumY, clippedMaximumY + yRadius);
      }
      return new SearchBounds(
          clippedMinimumX, clippedMaximumX, clippedMinimumY, clippedMaximumY);
    }
  }

  /** Adaptive best point plus every evaluated confidence-surface point. */
  public record BeamformingSearchResult(
      BeamformingPoint best,
      List<BeamformingPoint> evaluatedPoints,
      int refinementLevels,
      int stepsPerAxis) {

    // Validate and defensively copy one search result.
    public BeamformingSearchResult {
      Objects.requireNonNull(best, "best");
      evaluatedPoints =
          List.copyOf(Objects.requireNonNull(evaluatedPoints, "evaluatedPoints"));
      if (evaluatedPoints.isEmpty()) {
        throw new IllegalArgumentException("evaluatedPoints must not be empty");
      }
      if (refinementLevels < 1 || stepsPerAxis < 2) {
        throw new IllegalArgumentException("invalid search configuration");
      }
    }

    /** Number of beamforming candidates actually evaluated. */
    public int evaluatedCandidateCount() {
      return evaluatedPoints.size();
    }

    /** Full evaluated confidence surface normalized to the selected best energy. */
    public List<BeamformingConfidencePoint> normalizedConfidenceSurface() {
      double maximumEnergy = best.energy();
      return evaluatedPoints.stream()
          .map(
              point ->
                  new BeamformingConfidencePoint(
                      point.positionMeters(),
                      point.energy(),
                      maximumEnergy > 0.0 ? point.energy() / maximumEnergy : 0.0))
          .toList();
    }
  }

  /** One evaluated beamforming point with confidence normalized to the selected best score. */
  public record BeamformingConfidencePoint(
      Vector2 positionMeters, double energy, double normalizedConfidence) {

    // Validate one immutable confidence-surface point.
    public BeamformingConfidencePoint {
      Objects.requireNonNull(positionMeters, "positionMeters");
      if (!Double.isFinite(energy) || energy < 0.0) {
        throw new IllegalArgumentException("energy must be finite and >= 0");
      }
      if (!Double.isFinite(normalizedConfidence)
          || normalizedConfidence < 0.0
          || normalizedConfidence > 1.0) {
        throw new IllegalArgumentException("normalizedConfidence must be in [0,1]");
      }
    }
  }
}
