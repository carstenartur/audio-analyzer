package org.hammer.audio.experimental.acoustic.wingbeat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.analysis.Fft;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.experimental.acoustic.FrequencyBand;
import org.hammer.audio.experimental.acoustic.tracking.TrackedSource;

/**
 * Extracts a {@link WingbeatFeatureVector} from a tracked acoustic source.
 *
 * <p>Two extraction modes are supported:
 *
 * <ul>
 *   <li><em>Metadata-only extraction</em> uses the fields carried in a {@link TrackedSource}
 *       (frequency, variance, confidence, observation count). This is the minimal path when no
 *       audio block is available. Harmonic amplitudes, spectral centroid, bandwidth and SNR default
 *       to zero or empty lists.
 *   <li><em>Audio-enhanced extraction</em> additionally analyses the FFT spectrum of a live {@link
 *       AudioBlock} to compute harmonic amplitudes, spectral centroid, spectral bandwidth and
 *       signal-to-noise ratio.
 * </ul>
 *
 * <p>This extractor is stateless. Frequency drift and amplitude-modulation estimates default to
 * {@code 0}. Callers that need those features must accumulate sequential {@link
 * WingbeatFeatureVector} instances and compute drift/modulation externally.
 */
public final class WingbeatFeatureExtractor {

  private static final int DEFAULT_HARMONIC_COUNT = 4;

  private final int fftSize;
  private final FrequencyBand searchBand;
  private final Fft fft;
  private final int harmonicCount;

  /**
   * Create an extractor with a custom harmonic count.
   *
   * @param fftSize FFT window size in samples; must be a power of two and {@code >= 256}
   * @param searchBand frequency band to analyse; must not be {@code null}
   * @param harmonicCount number of harmonics to extract ({@code >= 1})
   */
  public WingbeatFeatureExtractor(int fftSize, FrequencyBand searchBand, int harmonicCount) {
    if (fftSize < 256 || Integer.bitCount(fftSize) != 1) {
      throw new IllegalArgumentException("fftSize must be a power of two >= 256");
    }
    if (harmonicCount < 1) {
      throw new IllegalArgumentException("harmonicCount must be >= 1");
    }
    this.fftSize = fftSize;
    this.searchBand = Objects.requireNonNull(searchBand, "searchBand");
    this.fft = new Fft(fftSize);
    this.harmonicCount = harmonicCount;
  }

  /**
   * Create an extractor with the default harmonic count of {@value #DEFAULT_HARMONIC_COUNT}.
   *
   * @param fftSize FFT window size in samples; must be a power of two and {@code >= 256}
   * @param searchBand frequency band to analyse; must not be {@code null}
   */
  public WingbeatFeatureExtractor(int fftSize, FrequencyBand searchBand) {
    this(fftSize, searchBand, DEFAULT_HARMONIC_COUNT);
  }

  /**
   * Extract features using only tracking metadata; no audio block is required.
   *
   * <p>The resulting vector has empty harmonic lists; spectral centroid defaults to the tracked
   * frequency; spectral bandwidth is approximated from the frequency jitter. Drift and amplitude
   * modulation default to {@code 0}.
   *
   * @param source the tracked source; must not be {@code null}
   * @param trackDurationSeconds time since the source was first observed, in seconds; must be
   *     finite and {@code >= 0}
   * @return extracted feature vector
   */
  public WingbeatFeatureVector extract(TrackedSource source, double trackDurationSeconds) {
    Objects.requireNonNull(source, "source");
    validateDuration(trackDurationSeconds);
    double jitter = Math.sqrt(source.frequencyVarianceHzSquared());
    return new WingbeatFeatureVector(
        source.frequencyHz(),
        List.of(),
        List.of(),
        source.frequencyHz(),
        jitter,
        0.0,
        jitter,
        0.0,
        0.0,
        trackDurationSeconds,
        source.confidence());
  }

  /**
   * Extract features using a live audio block for full spectral analysis.
   *
   * <p>This mode computes harmonic amplitudes and ratios, spectral centroid, spectral bandwidth and
   * signal-to-noise ratio in addition to the metadata-derived features.
   *
   * @param source the tracked source; must not be {@code null}
   * @param block the audio block to analyse; must not be {@code null}
   * @param channel index of the channel to analyse; must be a valid channel for {@code block}
   * @param trackDurationSeconds time since the source was first observed, in seconds; must be
   *     finite and {@code >= 0}
   * @return extracted feature vector
   */
  public WingbeatFeatureVector extract(
      TrackedSource source, AudioBlock block, int channel, double trackDurationSeconds) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(block, "block");
    if (channel < 0 || channel >= block.channels()) {
      throw new IllegalArgumentException(
          "channel " + channel + " out of range for block with " + block.channels() + " channels");
    }
    validateDuration(trackDurationSeconds);

    float[] magnitudes = computeMagnitudes(block, channel);
    double sampleRate = block.format().sampleRate();

    double centroid = spectralCentroid(magnitudes, sampleRate);
    double bandwidth = spectralBandwidth(magnitudes, sampleRate, centroid);
    double snr = signalToNoiseRatio(magnitudes, sampleRate, source.frequencyHz());
    List<Double> amplitudes = harmonicAmplitudes(magnitudes, sampleRate, source.frequencyHz());
    List<Double> ratios = harmonicRatios(amplitudes);
    double jitter = Math.sqrt(source.frequencyVarianceHzSquared());

    return new WingbeatFeatureVector(
        source.frequencyHz(),
        amplitudes,
        ratios,
        centroid,
        bandwidth,
        0.0,
        jitter,
        0.0,
        snr,
        trackDurationSeconds,
        source.confidence());
  }

  private float[] computeMagnitudes(AudioBlock block, int channel) {
    float[] samples = block.channelView(channel);
    float[] re = new float[fftSize];
    float[] im = new float[fftSize];
    int copied = Math.min(samples.length, fftSize);
    System.arraycopy(samples, 0, re, 0, copied);
    applyHannWindow(re, copied);
    fft.forward(re, im);
    float[] magnitudes = new float[fftSize / 2 + 1];
    fft.magnitudesOneSided(re, im, magnitudes);
    return magnitudes;
  }

  private double spectralCentroid(float[] magnitudes, double sampleRate) {
    int lowBin = frequencyToBin(searchBand.lowHz(), sampleRate);
    int highBin = Math.min(magnitudes.length - 1, frequencyToBin(searchBand.highHz(), sampleRate));
    double weightedFreq = 0.0;
    double totalWeight = 0.0;
    for (int bin = lowBin; bin <= highBin; bin++) {
      double freq = binToFrequency(bin, sampleRate);
      double mag = magnitudes[bin];
      weightedFreq += freq * mag;
      totalWeight += mag;
    }
    return totalWeight > 0.0 ? weightedFreq / totalWeight : searchBand.lowHz();
  }

  private double spectralBandwidth(float[] magnitudes, double sampleRate, double centroidHz) {
    int lowBin = frequencyToBin(searchBand.lowHz(), sampleRate);
    int highBin = Math.min(magnitudes.length - 1, frequencyToBin(searchBand.highHz(), sampleRate));
    double weightedVariance = 0.0;
    double totalWeight = 0.0;
    for (int bin = lowBin; bin <= highBin; bin++) {
      double freq = binToFrequency(bin, sampleRate);
      double mag = magnitudes[bin];
      double deviation = freq - centroidHz;
      weightedVariance += deviation * deviation * mag;
      totalWeight += mag;
    }
    return totalWeight > 0.0 ? Math.sqrt(weightedVariance / totalWeight) : 0.0;
  }

  private double signalToNoiseRatio(float[] magnitudes, double sampleRate, double fundamentalHz) {
    int lowBin = frequencyToBin(searchBand.lowHz(), sampleRate);
    int highBin = Math.min(magnitudes.length - 1, frequencyToBin(searchBand.highHz(), sampleRate));
    if (lowBin >= highBin) {
      return 0.0;
    }
    int peakBin = Math.min(highBin, Math.max(lowBin, frequencyToBin(fundamentalHz, sampleRate)));
    double peakMagnitude = magnitudes[peakBin];
    int count = highBin - lowBin + 1;
    float[] band = Arrays.copyOfRange(magnitudes, lowBin, lowBin + count);
    Arrays.sort(band);
    double noise = band[count / 2];
    return noise > 0.0 ? peakMagnitude / noise : 0.0;
  }

  private List<Double> harmonicAmplitudes(
      float[] magnitudes, double sampleRate, double fundamentalHz) {
    List<Double> amplitudes = new ArrayList<>(harmonicCount);
    for (int h = 1; h <= harmonicCount; h++) {
      int bin = frequencyToBin(fundamentalHz * h, sampleRate);
      if (bin < 0 || bin >= magnitudes.length) {
        amplitudes.add(0.0);
      } else {
        amplitudes.add((double) magnitudes[bin]);
      }
    }
    return amplitudes;
  }

  private static List<Double> harmonicRatios(List<Double> amplitudes) {
    if (amplitudes.size() < 2 || amplitudes.get(0) <= 0.0) {
      return List.of();
    }
    List<Double> ratios = new ArrayList<>(amplitudes.size() - 1);
    double fundamental = amplitudes.get(0);
    for (int i = 1; i < amplitudes.size(); i++) {
      ratios.add(amplitudes.get(i) / fundamental);
    }
    return ratios;
  }

  private int frequencyToBin(double frequencyHz, double sampleRate) {
    return (int) Math.round(frequencyHz * fftSize / sampleRate);
  }

  private double binToFrequency(int bin, double sampleRate) {
    return bin * sampleRate / fftSize;
  }

  private static void applyHannWindow(float[] samples, int frames) {
    if (frames <= 1) {
      return;
    }
    for (int i = 0; i < frames; i++) {
      samples[i] *= (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (frames - 1)));
    }
  }

  private static void validateDuration(double trackDurationSeconds) {
    if (!Double.isFinite(trackDurationSeconds) || trackDurationSeconds < 0.0) {
      throw new IllegalArgumentException("trackDurationSeconds must be finite and >= 0");
    }
  }
}
