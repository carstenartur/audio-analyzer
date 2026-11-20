package org.hammer.audio.recording.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RecordingStoragePolicyTest {

  @Test
  void distinguishesRawCapacityAndTimeBudgetWithConservativeRate() {
    RecordingStoragePolicy policy = new RecordingStoragePolicy(1_000L, 100L, 300L, 30L, 0.1);
    assertEquals(RecordingStorageLevel.NORMAL, policy.level(true, 10_000L, 10.0, 1.0));
    assertEquals(RecordingStorageLevel.WARNING, policy.level(true, 2_000L, 10.0, 1.0));
    assertEquals(RecordingStorageLevel.CRITICAL, policy.level(true, 200L, 10.0, 1.0));
    assertEquals(RecordingStorageLevel.CRITICAL, policy.level(false, 1_000_000L, 1.0, 1.0));
    assertEquals(RecordingStorageLevel.WARNING, policy.level(true, 900L, 0.01, 0.01));
    assertEquals(90L, policy.safeSeconds(1_000L, 1.0, 10.0));
    assertEquals(false, policy.fits(100L, 100L));
  }

  @Test
  void refusesInvertedOrNonfiniteThresholds() {
    assertThrows(
        IllegalArgumentException.class, () -> new RecordingStoragePolicy(1L, 2L, 10L, 1L, 0.1));
    assertThrows(
        IllegalArgumentException.class, () -> new RecordingStoragePolicy(10L, 1L, 1L, 2L, 0.1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecordingStoragePolicy(10L, 1L, 10L, 1L, Double.NaN));
  }
}
