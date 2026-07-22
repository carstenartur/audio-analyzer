package org.hammer.audio.experimental.acoustic.workbench;

import java.util.Objects;

/**
 * Configurable parameters for an acoustic localization workbench run.
 *
 * <p>Use {@link #defaults()} to obtain a {@link Builder} pre-populated with research-grade
 * defaults, then customise individual fields and call {@link Builder#build()}.
 *
 * @param blockSize frames per processed audio block (also used as default FFT size)
 * @param fftSize FFT size in samples (power of two)
 * @param maxPeaks maximum peaks per channel returned by the multi-peak detector
 * @param minSnr minimum peak-to-band-median SNR ratio for accepting a peak
 * @param bandMinHz lower bound of the frequency search band in Hz
 * @param bandMaxHz upper bound of the frequency search band in Hz
 * @param clusteringToleranceHz maximum frequency distance for merging peaks into one cluster in Hz
 * @param candidateGridSteps number of grid steps along each room axis
 * @param tdoaEstimatorType TDOA estimator strategy
 * @param trackerFrequencyMatchHz maximum frequency difference for matching an observation in Hz
 * @param trackerMissingFramesToDrop consecutive missed frames after which a track is dropped
 * @param trackerConfidenceDecay multiplicative confidence decay when a track is not observed
 * @param trackerConfidenceGain additive confidence gain when a track is observed
 */
public record WorkbenchParameters(
    int blockSize,
    int fftSize,
    int maxPeaks,
    double minSnr,
    double bandMinHz,
    double bandMaxHz,
    double clusteringToleranceHz,
    int candidateGridSteps,
    TdoaEstimatorType tdoaEstimatorType,
    double trackerFrequencyMatchHz,
    int trackerMissingFramesToDrop,
    double trackerConfidenceDecay,
    double trackerConfidenceGain) {

  /** TDOA estimator strategy for the workbench pipeline. */
  public enum TdoaEstimatorType {
    /** Integer-sample frequency-domain GCC-PHAT baseline and default. */
    GCC_PHAT,
    /** Sixteen-times interpolated GCC-PHAT with peak diagnostics and ambiguity evidence. */
    SUB_SAMPLE_GCC_PHAT,
    /** Time-domain normalised cross-correlation (faster, weaker under reflections). */
    CROSS_CORRELATION
  }

  // Compact constructor: validates inputs
  public WorkbenchParameters {
    Objects.requireNonNull(tdoaEstimatorType, "tdoaEstimatorType");
    if (!Double.isFinite(minSnr)) {
      throw new IllegalArgumentException("minSnr must be finite, got " + minSnr);
    }
    if (!Double.isFinite(bandMinHz) || !Double.isFinite(bandMaxHz)) {
      throw new IllegalArgumentException("frequency band bounds must be finite");
    }
    if (bandMinHz >= bandMaxHz) {
      throw new IllegalArgumentException(
          "bandMinHz (" + bandMinHz + ") must be less than bandMaxHz (" + bandMaxHz + ")");
    }
  }

  /** Return a builder pre-populated with defaults. */
  public static Builder defaults() {
    return new Builder();
  }

  /** Builder for {@link WorkbenchParameters}. */
  public static final class Builder {

    private int valBlockSize = 1024;
    private int valFftSize = 1024;
    private int valMaxPeaks = 3;
    private double valMinSnr = 2.0;
    private double valBandMinHz = 150.0;
    private double valBandMaxHz = 2500.0;
    private double valClusteringToleranceHz = 25.0;
    private int valCandidateGridSteps = 8;
    private TdoaEstimatorType valTdoaEstimatorType = TdoaEstimatorType.GCC_PHAT;
    private double valTrackerFrequencyMatchHz = 35.0;
    private int valTrackerMissingFramesToDrop = 4;
    private double valTrackerConfidenceDecay = 0.85;
    private double valTrackerConfidenceGain = 0.4;

    private Builder() {}

    /** Set block size (and default FFT size). */
    public Builder blockSize(int v) {
      this.valBlockSize = v;
      // Only adopt v as the default FFT size when it is itself a valid power of two.
      if (v > 0 && (v & (v - 1)) == 0) {
        this.valFftSize = v;
      }
      return this;
    }

    /** Set FFT size explicitly (must be a positive power of two). */
    public Builder fftSize(int v) {
      if (v <= 0 || (v & (v - 1)) != 0) {
        throw new IllegalArgumentException("fftSize must be a positive power of two, got " + v);
      }
      this.valFftSize = v;
      return this;
    }

    /** Set maximum peaks per channel. */
    public Builder maxPeaks(int v) {
      this.valMaxPeaks = v;
      return this;
    }

    /** Set minimum SNR for peak acceptance. */
    public Builder minSnr(double v) {
      this.valMinSnr = v;
      return this;
    }

    /** Set lower frequency band boundary in Hz. */
    public Builder bandMinHz(double v) {
      this.valBandMinHz = v;
      return this;
    }

    /** Set upper frequency band boundary in Hz. */
    public Builder bandMaxHz(double v) {
      this.valBandMaxHz = v;
      return this;
    }

    /** Set frequency clustering tolerance in Hz. */
    public Builder clusteringToleranceHz(double v) {
      this.valClusteringToleranceHz = v;
      return this;
    }

    /** Set number of candidate grid steps. */
    public Builder candidateGridSteps(int v) {
      if (v <= 0) {
        throw new IllegalArgumentException("candidateGridSteps must be positive, got " + v);
      }
      this.valCandidateGridSteps = v;
      return this;
    }

    /** Set TDOA estimator type. */
    public Builder tdoaEstimatorType(TdoaEstimatorType v) {
      this.valTdoaEstimatorType = Objects.requireNonNull(v, "tdoaEstimatorType");
      return this;
    }

    /** Set tracker frequency match tolerance in Hz. */
    public Builder trackerFrequencyMatchHz(double v) {
      this.valTrackerFrequencyMatchHz = v;
      return this;
    }

    /** Set number of missing frames before a track is dropped. */
    public Builder trackerMissingFramesToDrop(int v) {
      this.valTrackerMissingFramesToDrop = v;
      return this;
    }

    /** Set tracker confidence decay factor. */
    public Builder trackerConfidenceDecay(double v) {
      this.valTrackerConfidenceDecay = v;
      return this;
    }

    /** Set tracker confidence gain per observation. */
    public Builder trackerConfidenceGain(double v) {
      this.valTrackerConfidenceGain = v;
      return this;
    }

    /** Build the parameter object. */
    public WorkbenchParameters build() {
      return new WorkbenchParameters(
          valBlockSize,
          valFftSize,
          valMaxPeaks,
          valMinSnr,
          valBandMinHz,
          valBandMaxHz,
          valClusteringToleranceHz,
          valCandidateGridSteps,
          valTdoaEstimatorType,
          valTrackerFrequencyMatchHz,
          valTrackerMissingFramesToDrop,
          valTrackerConfidenceDecay,
          valTrackerConfidenceGain);
    }
  }
}
