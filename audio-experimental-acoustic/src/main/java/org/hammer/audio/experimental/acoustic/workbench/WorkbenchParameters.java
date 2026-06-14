package org.hammer.audio.experimental.acoustic.workbench;

/**
 * Configurable parameters for an acoustic localization workbench run.
 *
 * <p>Use {@link #defaults()} to obtain a {@link Builder} pre-populated with research-grade
 * defaults, then customise individual fields and call {@link Builder#build()}.
 */
public final class WorkbenchParameters {

  /** TDOA estimator strategy for the workbench pipeline. */
  public enum TdoaEstimatorType {
    /** Frequency-domain GCC-PHAT (default, robust under narrow-band signals). */
    GCC_PHAT,
    /** Time-domain normalised cross-correlation (faster, weaker under reflections). */
    CROSS_CORRELATION
  }

  private final int blockSize;
  private final int fftSize;
  private final int maxPeaks;
  private final double minSnr;
  private final double bandMinHz;
  private final double bandMaxHz;
  private final double clusteringToleranceHz;
  private final int candidateGridSteps;
  private final TdoaEstimatorType tdoaEstimatorType;
  private final double trackerFrequencyMatchHz;
  private final int trackerMissingFramesToDrop;
  private final double trackerConfidenceDecay;
  private final double trackerConfidenceGain;

  private WorkbenchParameters(Builder b) {
    this.blockSize = b.blockSize;
    this.fftSize = b.fftSize;
    this.maxPeaks = b.maxPeaks;
    this.minSnr = b.minSnr;
    this.bandMinHz = b.bandMinHz;
    this.bandMaxHz = b.bandMaxHz;
    this.clusteringToleranceHz = b.clusteringToleranceHz;
    this.candidateGridSteps = b.candidateGridSteps;
    this.tdoaEstimatorType = b.tdoaEstimatorType;
    this.trackerFrequencyMatchHz = b.trackerFrequencyMatchHz;
    this.trackerMissingFramesToDrop = b.trackerMissingFramesToDrop;
    this.trackerConfidenceDecay = b.trackerConfidenceDecay;
    this.trackerConfidenceGain = b.trackerConfidenceGain;
  }

  /** Return a builder pre-populated with defaults. */
  public static Builder defaults() {
    return new Builder();
  }

  /** Frames per processed audio block (also used as default FFT size). */
  public int blockSize() {
    return blockSize;
  }

  /** FFT size in samples (power of two). */
  public int fftSize() {
    return fftSize;
  }

  /** Maximum peaks per channel returned by the multi-peak detector. */
  public int maxPeaks() {
    return maxPeaks;
  }

  /** Minimum peak-to-band-median SNR ratio for accepting a peak. */
  public double minSnr() {
    return minSnr;
  }

  /** Lower bound of the frequency search band in Hz. */
  public double bandMinHz() {
    return bandMinHz;
  }

  /** Upper bound of the frequency search band in Hz. */
  public double bandMaxHz() {
    return bandMaxHz;
  }

  /** Maximum frequency distance for merging peaks into one cluster in Hz. */
  public double clusteringToleranceHz() {
    return clusteringToleranceHz;
  }

  /** Number of grid steps along each room axis (grid has (steps+1)² points). */
  public int candidateGridSteps() {
    return candidateGridSteps;
  }

  /** TDOA estimator strategy. */
  public TdoaEstimatorType tdoaEstimatorType() {
    return tdoaEstimatorType;
  }

  /** Maximum frequency difference for matching an observation to an existing track in Hz. */
  public double trackerFrequencyMatchHz() {
    return trackerFrequencyMatchHz;
  }

  /** Number of consecutive missed frames after which a track is dropped. */
  public int trackerMissingFramesToDrop() {
    return trackerMissingFramesToDrop;
  }

  /** Multiplicative confidence decay applied when a track is not observed for a frame. */
  public double trackerConfidenceDecay() {
    return trackerConfidenceDecay;
  }

  /** Additive confidence gain applied when a track is observed in a frame. */
  public double trackerConfidenceGain() {
    return trackerConfidenceGain;
  }

  /** Builder for {@link WorkbenchParameters}. */
  public static final class Builder {

    private int blockSize = 1024;
    private int fftSize = 1024;
    private int maxPeaks = 3;
    private double minSnr = 2.0;
    private double bandMinHz = 150.0;
    private double bandMaxHz = 2500.0;
    private double clusteringToleranceHz = 25.0;
    private int candidateGridSteps = 8;
    private TdoaEstimatorType tdoaEstimatorType = TdoaEstimatorType.GCC_PHAT;
    private double trackerFrequencyMatchHz = 35.0;
    private int trackerMissingFramesToDrop = 4;
    private double trackerConfidenceDecay = 0.85;
    private double trackerConfidenceGain = 0.4;

    private Builder() {}

    /** Set block size (and default FFT size). */
    public Builder blockSize(int v) {
      this.blockSize = v;
      // Only adopt v as the default FFT size when it is itself a valid power of two.
      if (v > 0 && (v & (v - 1)) == 0) {
        this.fftSize = v;
      }
      return this;
    }

    /** Set FFT size explicitly (must be a positive power of two). */
    public Builder fftSize(int v) {
      if (v <= 0 || (v & (v - 1)) != 0) {
        throw new IllegalArgumentException("fftSize must be a positive power of two, got " + v);
      }
      this.fftSize = v;
      return this;
    }

    /** Set maximum peaks per channel. */
    public Builder maxPeaks(int v) {
      this.maxPeaks = v;
      return this;
    }

    /** Set minimum SNR for peak acceptance. */
    public Builder minSnr(double v) {
      this.minSnr = v;
      return this;
    }

    /** Set lower frequency band boundary in Hz. */
    public Builder bandMinHz(double v) {
      this.bandMinHz = v;
      return this;
    }

    /** Set upper frequency band boundary in Hz. */
    public Builder bandMaxHz(double v) {
      this.bandMaxHz = v;
      return this;
    }

    /** Set frequency clustering tolerance in Hz. */
    public Builder clusteringToleranceHz(double v) {
      this.clusteringToleranceHz = v;
      return this;
    }

    /** Set number of candidate grid steps. */
    public Builder candidateGridSteps(int v) {
      if (v <= 0) {
        throw new IllegalArgumentException("candidateGridSteps must be positive, got " + v);
      }
      this.candidateGridSteps = v;
      return this;
    }

    /** Set TDOA estimator type. */
    public Builder tdoaEstimatorType(TdoaEstimatorType v) {
      this.tdoaEstimatorType = v;
      return this;
    }

    /** Set tracker frequency match tolerance in Hz. */
    public Builder trackerFrequencyMatchHz(double v) {
      this.trackerFrequencyMatchHz = v;
      return this;
    }

    /** Set number of missing frames before a track is dropped. */
    public Builder trackerMissingFramesToDrop(int v) {
      this.trackerMissingFramesToDrop = v;
      return this;
    }

    /** Set tracker confidence decay factor. */
    public Builder trackerConfidenceDecay(double v) {
      this.trackerConfidenceDecay = v;
      return this;
    }

    /** Set tracker confidence gain per observation. */
    public Builder trackerConfidenceGain(double v) {
      this.trackerConfidenceGain = v;
      return this;
    }

    /** Build the parameter object. */
    public WorkbenchParameters build() {
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
      return new WorkbenchParameters(this);
    }
  }
}
