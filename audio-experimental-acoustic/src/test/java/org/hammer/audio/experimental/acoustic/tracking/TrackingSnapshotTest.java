package org.hammer.audio.experimental.acoustic.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.hammer.audio.acquisition.SynchronizationAssessment;
import org.hammer.audio.acquisition.SynchronizationMode;
import org.hammer.audio.acquisition.SynchronizationStatus;
import org.hammer.audio.experimental.acoustic.wingbeat.ClassificationResult;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatLabel;
import org.hammer.audio.geometry.Vector2;
import org.hammer.audio.geometry.Vector3;
import org.junit.jupiter.api.Test;

class TrackingSnapshotTest {

  @Test
  void fiveArgumentConstructorDefaultsToSharedClockAndEmptyClassifications() {
    TrackingSnapshot snapshot =
        new TrackingSnapshot(1L, 2L, List.of(cluster()), List.of(track()), 3L);

    assertTrue(snapshot.classificationResults().isEmpty());
    assertEquals(SynchronizationMode.NOMINAL_SHARED_CLOCK, snapshot.synchronization().mode());
  }

  @Test
  void sixArgumentConstructorRetainsClassificationResults() {
    TrackingSnapshot snapshot =
        new TrackingSnapshot(
            1L,
            2L,
            List.of(cluster()),
            List.of(track()),
            3L,
            Map.of(7, new ClassificationResult(WingbeatLabel.FEMALE_LIKELY, 0.4)));

    assertEquals(WingbeatLabel.FEMALE_LIKELY, snapshot.classificationResults().get(7).label());
    assertEquals(SynchronizationStatus.TRUSTED, snapshot.synchronization().status());
  }

  @Test
  void canonicalConstructorRetainsDegradedSynchronizationDiagnostics() {
    SynchronizationAssessment degraded =
        new SynchronizationAssessment(
            SynchronizationMode.DRIFT_COMPENSATED,
            SynchronizationStatus.DEGRADED,
            0.4,
            0.4 / 48_000.0,
            true,
            List.of("Timing error consumes more than half of the budget."));

    TrackingSnapshot snapshot =
        new TrackingSnapshot(1L, 2L, List.of(cluster()), List.of(track()), 3L, Map.of(), degraded);

    assertEquals(degraded, snapshot.synchronization());
  }

  private static FrequencyCluster cluster() {
    return new FrequencyCluster(512.0, 10.0, List.of(new DetectedPeak(0, 512.0, 10.0, 4.0)));
  }

  private static TrackedSource track() {
    return new TrackedSource(
        7, 512.0, 512.0, Vector2.ZERO, Vector2.ZERO, Vector3.ZERO, 0.0, 1.0, 0.8, 0L, 1);
  }
}
