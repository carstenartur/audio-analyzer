package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.experimental.acoustic.scenario.ClassificationGroundTruth;
import org.hammer.audio.experimental.acoustic.scenario.Scenario;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioSource;
import org.hammer.audio.experimental.acoustic.tracking.TrackedSource;
import org.hammer.audio.geometry.Vector2;

/** Compare tracking/classification measurements against scenario ground truth. */
public final class TrackingBenchmarkComparator
    implements GroundTruthComparator<Scenario, BenchmarkMeasurements, BenchmarkReport> {

  private final SnapshotGroundTruthAligner aligner;

  public TrackingBenchmarkComparator() {
    this(new SnapshotGroundTruthAligner());
  }

  public TrackingBenchmarkComparator(SnapshotGroundTruthAligner aligner) {
    this.aligner = Objects.requireNonNull(aligner, "aligner");
  }

  @Override
  public BenchmarkReport compare(Scenario truth, BenchmarkMeasurements measurements) {
    Objects.requireNonNull(truth, "truth");
    Objects.requireNonNull(measurements, "measurements");

    Vector2 arrayCenter = arrayCenter(measurements);
    List<Double> distanceErrorsMeters = new ArrayList<>();
    List<Double> angularErrorsDegrees = new ArrayList<>();
    List<Double> absoluteFrequencyErrorsHz = new ArrayList<>();
    List<Double> relativeFrequencyErrors = new ArrayList<>();
    List<Double> absoluteDopplerErrorsMetersPerSecond = new ArrayList<>();
    List<Long> processingTimes = new ArrayList<>(measurements.snapshots().size());
    Map<String, Map<Integer, Integer>> trackCountsBySource = new HashMap<>();
    Set<String> continuityEvaluableSources = new HashSet<>();

    long totalMeasuredObservations = 0L;
    long totalSpuriousTracks = 0L;
    long exactSourceCountFrames = 0L;
    long absoluteSourceCountError = 0L;
    int localizationSkippedCount = 0;
    int localizationUnavailableTruthCount = 0;
    int frequencySkippedCount = 0;
    int frequencyUnavailableTruthCount = 0;
    int dopplerSkippedCount = 0;
    int dopplerUnavailableTruthCount = 0;
    int continuityEvaluatedCount = 0;
    int continuitySkippedCount = 0;
    int continuityUnavailableTruthCount = 0;
    long scenarioStartTimestampNanos =
        measurements.snapshots().isEmpty()
            ? 0L
            : measurements.snapshots().get(0).sourceTimestampNanos();

    for (var snapshot : measurements.snapshots()) {
      processingTimes.add(snapshot.processingNanos());
      totalMeasuredObservations += snapshot.tracks().size();
      if (snapshot.tracks().size() == truth.sources().size()) {
        exactSourceCountFrames++;
      }
      absoluteSourceCountError += Math.abs(snapshot.tracks().size() - truth.sources().size());

      SnapshotAlignment alignment = aligner.align(truth, snapshot, scenarioStartTimestampNanos);
      totalSpuriousTracks += alignment.spuriousTracks().size();
      for (AlignedSourceObservation observation : alignment.matchedSources()) {
        TrackedSource track = observation.trackedSource();
        GroundTruthObservation groundTruth = observation.groundTruth();
        ScenarioSource source = groundTruth.source();
        if (groundTruth.hasAlignmentTruth()) {
          continuityEvaluableSources.add(source.sourceId());
          continuityEvaluatedCount++;
          trackCountsBySource
              .computeIfAbsent(source.sourceId(), ignored -> new HashMap<>())
              .merge(track.id(), 1, Integer::sum);
        } else {
          continuityUnavailableTruthCount++;
        }
        if (groundTruth.hasPositionTruth()) {
          distanceErrorsMeters.add(
              groundTruth.expectedPositionMeters().distanceTo(track.positionMeters()));
          angularErrorsDegrees.add(
              angularErrorDegrees(
                  arrayCenter, groundTruth.expectedPositionMeters(), track.positionMeters()));
        } else {
          localizationUnavailableTruthCount++;
        }
        if (groundTruth.hasFrequencyTruth()) {
          double trueFrequency = groundTruth.expectedFrequencyHz();
          double absoluteErrorHz = Math.abs(track.frequencyHz() - trueFrequency);
          absoluteFrequencyErrorsHz.add(absoluteErrorHz);
          relativeFrequencyErrors.add(absoluteErrorHz / trueFrequency);
        } else {
          frequencyUnavailableTruthCount++;
        }
        if (groundTruth.hasDopplerTruth()) {
          double trueRadialVelocity =
              radialVelocityToward(
                  arrayCenter,
                  groundTruth.expectedPositionMeters(),
                  groundTruth.expectedVelocityMetersPerSecond());
          absoluteDopplerErrorsMetersPerSecond.add(
              Math.abs(track.radialVelocityMetersPerSecond() - trueRadialVelocity));
        } else {
          dopplerUnavailableTruthCount++;
        }
      }
      for (GroundTruthObservation missingSource : alignment.missingSources()) {
        if (missingSource.hasAlignmentTruth()) {
          continuityEvaluableSources.add(missingSource.source().sourceId());
          continuitySkippedCount++;
        } else {
          continuityUnavailableTruthCount++;
        }
        if (missingSource.hasPositionTruth()) {
          localizationSkippedCount++;
        } else {
          localizationUnavailableTruthCount++;
        }
        if (missingSource.hasFrequencyTruth()) {
          frequencySkippedCount++;
        } else {
          frequencyUnavailableTruthCount++;
        }
        if (missingSource.hasDopplerTruth()) {
          dopplerSkippedCount++;
        } else {
          dopplerUnavailableTruthCount++;
        }
      }
    }

    int classificationCorrect = 0;
    int classificationEvaluatedCount = 0;
    int classificationSkippedCount = 0;
    int classificationUnavailableTruthCount = 0;
    for (ScenarioSource source : truth.sources()) {
      ClassificationGroundTruth labels = source.labels();
      ClassificationPrediction prediction =
          measurements.classificationPredictions().get(source.sourceId());
      if (labels == null || !hasComparableClassification(labels)) {
        classificationUnavailableTruthCount++;
        continue;
      }
      if (prediction == null) {
        classificationSkippedCount++;
        continue;
      }
      classificationEvaluatedCount++;
      if (classificationMatches(labels, prediction)) {
        classificationCorrect++;
      }
    }

    int continuitySampleCount =
        continuityEvaluatedCount + continuitySkippedCount + continuityUnavailableTruthCount;
    int continuityAvailableCount = continuityEvaluatedCount + continuitySkippedCount;
    Double trackContinuity =
        continuityAvailableCount == 0
            ? null
            : continuityEvaluatedCount / (double) continuityAvailableCount;
    Double idStability = idStability(continuityEvaluableSources, trackCountsBySource);
    Double sourceCountAccuracy =
        measurements.snapshots().isEmpty()
            ? null
            : exactSourceCountFrames / (double) measurements.snapshots().size();
    Double meanSourceCountError =
        measurements.snapshots().isEmpty()
            ? null
            : absoluteSourceCountError / (double) measurements.snapshots().size();
    Double falsePositiveRate =
        totalMeasuredObservations == 0L
            ? null
            : totalSpuriousTracks / (double) totalMeasuredObservations;
    Double falseNegativeRate =
        continuityAvailableCount == 0
            ? null
            : continuitySkippedCount / (double) continuityAvailableCount;

    return new BenchmarkReport(
        truth.id(),
        truth.sources().size(),
        measurements.snapshots().size(),
        LocalizationErrorMetric.ofSamples(
            distanceErrorsMeters,
            angularErrorsDegrees,
            localizationSkippedCount,
            localizationUnavailableTruthCount),
        FrequencyErrorMetric.ofSamples(
            absoluteFrequencyErrorsHz,
            relativeFrequencyErrors,
            frequencySkippedCount,
            frequencyUnavailableTruthCount),
        DopplerErrorMetric.ofSamples(
            absoluteDopplerErrorsMetersPerSecond,
            dopplerSkippedCount,
            dopplerUnavailableTruthCount),
        ClassificationAccuracyMetric.ofCounts(
            classificationCorrect,
            classificationEvaluatedCount,
            classificationSkippedCount,
            classificationUnavailableTruthCount),
        trackContinuity,
        continuitySampleCount,
        continuityEvaluatedCount,
        continuitySkippedCount,
        continuityUnavailableTruthCount,
        idStability,
        sourceCountAccuracy,
        meanSourceCountError,
        falsePositiveRate,
        falseNegativeRate,
        mean(processingTimes),
        median(processingTimes),
        max(processingTimes));
  }

  private static boolean hasComparableClassification(ClassificationGroundTruth labels) {
    return labels.species() != null
        || labels.sex() != null
        || labels.age() != null
        || labels.feedingStatus() != null
        || !labels.labels().isEmpty();
  }

  private static boolean classificationMatches(
      ClassificationGroundTruth truth, ClassificationPrediction prediction) {
    if (truth.species() != null && !truth.species().equals(prediction.species())) {
      return false;
    }
    if (truth.sex() != null && !truth.sex().equals(prediction.sex())) {
      return false;
    }
    if (truth.age() != null && !truth.age().equals(prediction.age())) {
      return false;
    }
    if (truth.feedingStatus() != null
        && !truth.feedingStatus().equals(prediction.feedingStatus())) {
      return false;
    }
    for (Map.Entry<String, String> entry : truth.labels().entrySet()) {
      if (!Objects.equals(entry.getValue(), prediction.labels().get(entry.getKey()))) {
        return false;
      }
    }
    return true;
  }

  private static Vector2 arrayCenter(BenchmarkMeasurements measurements) {
    double sumX = 0.0;
    double sumY = 0.0;
    for (Microphone microphone : measurements.array().microphones()) {
      sumX += microphone.positionMeters().x();
      sumY += microphone.positionMeters().y();
    }
    double divisor = measurements.array().microphones().size();
    return new Vector2(sumX / divisor, sumY / divisor);
  }

  private static double angularErrorDegrees(Vector2 reference, Vector2 expected, Vector2 measured) {
    Vector2 expectedDirection = expected.minus(reference);
    Vector2 measuredDirection = measured.minus(reference);
    double expectedLength = expectedDirection.length();
    double measuredLength = measuredDirection.length();
    if (expectedLength == 0.0 || measuredLength == 0.0) {
      return 0.0;
    }
    double cosTheta = expectedDirection.dot(measuredDirection) / (expectedLength * measuredLength);
    cosTheta = Math.max(-1.0, Math.min(1.0, cosTheta));
    return Math.toDegrees(Math.acos(cosTheta));
  }

  private static double radialVelocityToward(Vector2 receiver, Vector2 source, Vector2 velocity) {
    return velocity.dot(receiver.minus(source).normalized());
  }

  private static Double idStability(
      Set<String> evaluableSourceIds, Map<String, Map<Integer, Integer>> trackCountsBySource) {
    if (evaluableSourceIds.isEmpty()) {
      return null;
    }
    double total = 0.0;
    for (String sourceId : evaluableSourceIds) {
      Map<Integer, Integer> trackCounts = trackCountsBySource.get(sourceId);
      if (trackCounts == null || trackCounts.isEmpty()) {
        continue;
      }
      int dominant = 0;
      int matched = 0;
      for (int count : trackCounts.values()) {
        dominant = Math.max(dominant, count);
        matched += count;
      }
      total += dominant / (double) matched;
    }
    return total / evaluableSourceIds.size();
  }

  private static long mean(List<Long> values) {
    if (values.isEmpty()) {
      return 0L;
    }
    long sum = 0L;
    for (Long value : values) {
      sum += value;
    }
    return Math.round(sum / (double) values.size());
  }

  private static long median(List<Long> values) {
    if (values.isEmpty()) {
      return 0L;
    }
    List<Long> sorted = new ArrayList<>(values);
    sorted.sort(Long::compareTo);
    int middle = sorted.size() / 2;
    if ((sorted.size() & 1) == 1) {
      return sorted.get(middle);
    }
    return Math.round((sorted.get(middle - 1) + sorted.get(middle)) / 2.0);
  }

  private static long max(List<Long> values) {
    long max = 0L;
    for (Long value : values) {
      max = Math.max(max, value);
    }
    return max;
  }
}
