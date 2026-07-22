package org.hammer.audio.experimental.acoustic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.experimental.acoustic.DelayAndSumBeamformer.BeamformingPoint;
import org.hammer.audio.geometry.Vector2;

/** Coarse-to-fine candidate refinement over the existing delay-and-sum beamforming baseline. */
public final class AdaptiveBeamformingSearch {

  private static final int ACTIVE_HYPOTHESIS_LIMIT = 2;

  private final DelayAndSumBeamformer beamformer;

  /** Creates an adaptive search over one interchangeable beamforming scorer. */
  public AdaptiveBeamformingSearch(DelayAndSumBeamformer beamformer) {
    this.beamformer = Objects.requireNonNull(beamformer, "beamformer");
  }

  /**
   * Searches the initial bounds and repeatedly refines two deterministic, spatially distinct score
   * hypotheses. Retaining more than one path prevents an early coarse-grid alias from irreversibly
   * excluding the physically correct basin.
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

    List<SearchBounds> activeRegions = List.of(initialBounds);
    Map<Vector2, BeamformingPoint> evaluatedByPosition = new LinkedHashMap<>();
    BeamformingPoint globalBest = null;
    for (int level = 0; level < refinementLevels; level++) {
      Map<Vector2, BeamformingPoint> levelPoints = new LinkedHashMap<>();
      for (SearchBounds region : activeRegions) {
        for (BeamformingPoint point : beamformer.scan(block, array, region.grid(stepsPerAxis))) {
          levelPoints.putIfAbsent(point.positionMeters(), point);
          evaluatedByPosition.putIfAbsent(point.positionMeters(), point);
        }
      }

      List<BeamformingPoint> ranked = rankByEnergy(levelPoints.values());
      BeamformingPoint levelBest = ranked.get(0);
      if (globalBest == null || levelBest.energy() > globalBest.energy()) {
        globalBest = levelBest;
      }
      if (level + 1 < refinementLevels) {
        double scale = Math.pow(stepsPerAxis, level + 1.0);
        double xRadius = initialBounds.width() / scale;
        double yRadius = initialBounds.height() / scale;
        List<BeamformingPoint> centers =
            selectRefinementCenters(ranked, xRadius, yRadius, ACTIVE_HYPOTHESIS_LIMIT);
        List<SearchBounds> nextRegions = new ArrayList<>(centers.size());
        for (BeamformingPoint center : centers) {
          nextRegions.add(initialBounds.around(center.positionMeters(), xRadius, yRadius));
        }
        activeRegions = List.copyOf(nextRegions);
      }
    }

    return new BeamformingSearchResult(
        globalBest, List.copyOf(evaluatedByPosition.values()), refinementLevels, stepsPerAxis);
  }

  private static List<BeamformingPoint> rankByEnergy(Iterable<BeamformingPoint> points) {
    List<BeamformingPoint> ranked = new ArrayList<>();
    points.forEach(ranked::add);
    ranked.sort(
        Comparator.comparingDouble(BeamformingPoint::energy)
            .reversed()
            .thenComparingDouble(point -> point.positionMeters().x())
            .thenComparingDouble(point -> point.positionMeters().y()));
    if (ranked.isEmpty()) {
      throw new IllegalArgumentException("points must not be empty");
    }
    return List.copyOf(ranked);
  }

  private static List<BeamformingPoint> selectRefinementCenters(
      List<BeamformingPoint> ranked, double xRadius, double yRadius, int maximumCenters) {
    List<BeamformingPoint> selected = new ArrayList<>(maximumCenters);
    for (BeamformingPoint candidate : ranked) {
      if (isSpatiallyDistinct(candidate, selected, xRadius, yRadius)) {
        selected.add(candidate);
        if (selected.size() == maximumCenters) {
          return List.copyOf(selected);
        }
      }
    }
    for (BeamformingPoint candidate : ranked) {
      if (!selected.contains(candidate)) {
        selected.add(candidate);
        if (selected.size() == maximumCenters) {
          break;
        }
      }
    }
    return List.copyOf(selected);
  }

  private static boolean isSpatiallyDistinct(
      BeamformingPoint candidate, List<BeamformingPoint> selected, double xRadius, double yRadius) {
    for (BeamformingPoint existing : selected) {
      double xDistance = Math.abs(candidate.positionMeters().x() - existing.positionMeters().x());
      double yDistance = Math.abs(candidate.positionMeters().y() - existing.positionMeters().y());
      if (xDistance <= 2.0 * xRadius && yDistance <= 2.0 * yRadius) {
        return false;
      }
    }
    return true;
  }

  /**
   * Rectangular search region in meters.
   *
   * @param minimumX inclusive minimum x coordinate
   * @param maximumX inclusive maximum x coordinate
   * @param minimumY inclusive minimum y coordinate
   * @param maximumY inclusive maximum y coordinate
   */
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
      if (clippedMinimumX >= clippedMaximumX) {
        clippedMinimumX = Math.max(minimumX, clippedMinimumX - xRadius);
        clippedMaximumX = Math.min(maximumX, clippedMaximumX + xRadius);
      }
      if (clippedMinimumY >= clippedMaximumY) {
        clippedMinimumY = Math.max(minimumY, clippedMinimumY - yRadius);
        clippedMaximumY = Math.min(maximumY, clippedMaximumY + yRadius);
      }
      return new SearchBounds(clippedMinimumX, clippedMaximumX, clippedMinimumY, clippedMaximumY);
    }
  }

  /**
   * Adaptive search result with the global best point and full evaluated surface.
   *
   * @param best global best point across all refinement levels
   * @param evaluatedPoints uniquely evaluated points in deterministic order
   * @param refinementLevels number of completed refinement levels
   * @param stepsPerAxis grid steps used in each active region
   */
  public record BeamformingSearchResult(
      BeamformingPoint best,
      List<BeamformingPoint> evaluatedPoints,
      int refinementLevels,
      int stepsPerAxis) {

    // Validate and defensively copy one search result.
    public BeamformingSearchResult {
      Objects.requireNonNull(best, "best");
      evaluatedPoints = List.copyOf(Objects.requireNonNull(evaluatedPoints, "evaluatedPoints"));
      if (evaluatedPoints.isEmpty()) {
        throw new IllegalArgumentException("evaluatedPoints must not be empty");
      }
      if (refinementLevels < 1 || stepsPerAxis < 2) {
        throw new IllegalArgumentException("invalid search configuration");
      }
    }

    /** Number of unique beamforming candidates actually evaluated. */
    public int evaluatedCandidateCount() {
      return evaluatedPoints.size();
    }

    /** Full evaluated confidence surface normalized to the global maximum energy. */
    public List<BeamformingConfidencePoint> normalizedConfidenceSurface() {
      double maximumEnergy =
          evaluatedPoints.stream().mapToDouble(BeamformingPoint::energy).max().orElse(0.0);
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

  /**
   * One evaluated beamforming point with confidence normalized to the global maximum.
   *
   * @param positionMeters candidate position in meters
   * @param energy raw beamforming energy
   * @param normalizedConfidence energy divided by the global maximum in {@code [0,1]}
   */
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
