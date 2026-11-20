package org.hammer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Path2D;
import org.hammer.audio.AudioCaptureService;
import org.hammer.audio.analysis.SpectrumAnalyzer;
import org.hammer.audio.analysis.SpectrumDisplayState;
import org.hammer.audio.analysis.SpectrumSnapshot;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.ui.theme.PlotRenderTheme;

/** Panel for displaying the current FFT magnitude spectrum. */
public final class SpectrumPanel extends javax.swing.JPanel {

  private static final long serialVersionUID = 1L;
  private static final int FFT_SIZE = 1024;
  private static final int RIGHT_MARGIN = 12;
  private static final int TOP_MARGIN = 18;
  private static final int BOTTOM_MARGIN = PlotRenderTheme.BOTTOM_AXIS_MARGIN;
  private static final float MIN_PEAK_MAGNITUDE = 1.0e-6f;
  // Avoid rebuilding the analyzer for insignificant float-format differences.
  private static final float SAMPLE_RATE_TOLERANCE = 0.0001f;

  private AudioCaptureService audioCaptureService;
  private transient SpectrumAnalyzer analyzer;
  private transient SpectrumSnapshot latestSpectrum;
  private transient SpectrumSnapshot frozenSpectrum;
  private final transient SpectrumDisplayState displayState = new SpectrumDisplayState();
  private long latestSpectrumFrameIndex = Long.MIN_VALUE;
  private long latestSpectrumTimestampNanos = Long.MIN_VALUE;
  private long displayStateFrameIndex = Long.MIN_VALUE;
  private boolean frozen;

  public SpectrumPanel() {
    super(true);
    setPreferredSize(new Dimension(320, 180));
    javax.swing.Timer timer =
        new javax.swing.Timer(UiConstants.REFRESH_INTERVAL_MS, e -> repaint());
    timer.start();
  }

  /**
   * Set the audio capture service that supplies audio blocks to analyze.
   *
   * @param service the audio service
   */
  public void setAudioCaptureService(AudioCaptureService service) {
    this.audioCaptureService = service;
    this.analyzer = null;
    this.latestSpectrum = null;
    this.frozenSpectrum = null;
    this.latestSpectrumFrameIndex = Long.MIN_VALUE;
    this.latestSpectrumTimestampNanos = Long.MIN_VALUE;
    this.displayStateFrameIndex = Long.MIN_VALUE;
    this.frozen = false;
    this.displayState.clear();
  }

  /**
   * Freeze or unfreeze the currently displayed spectrum.
   *
   * @param frozen true to freeze the current spectrum
   */
  public void setFrozen(boolean frozen) {
    if (frozen && !this.frozen) {
      frozenSpectrum = getCurrentSpectrum();
    } else if (!frozen) {
      frozenSpectrum = null;
    }
    this.frozen = frozen;
    repaint();
  }

  /**
   * @return the display state, exposing peak-hold and averaging configuration
   */
  public SpectrumDisplayState getDisplayState() {
    return displayState;
  }

  /** Enable or disable the peak-hold trace overlay. */
  public void setPeakHoldEnabled(boolean enabled) {
    displayState.setPeakHoldEnabled(enabled);
    repaint();
  }

  /** Enable or disable the exponential-average trace overlay. */
  public void setAveragingEnabled(boolean enabled) {
    displayState.setAveragingEnabled(enabled);
    repaint();
  }

  /** Reset the peak-hold trace without changing other settings. */
  public void resetPeakHold() {
    displayState.resetPeakHold();
    repaint();
  }

  /**
   * @return current spectrum snapshot, or {@code null} if no audio block is available
   */
  public SpectrumSnapshot getCurrentSpectrum() {
    if (frozen && frozenSpectrum != null) {
      maybeUpdateDisplayState(frozenSpectrum);
      return frozenSpectrum;
    }
    AudioCaptureService service = audioCaptureService;
    if (service == null) {
      maybeUpdateDisplayState(latestSpectrum);
      return latestSpectrum;
    }
    AudioBlock block = service.getLatestBlock();
    if (block == null) {
      maybeUpdateDisplayState(latestSpectrum);
      return latestSpectrum;
    }
    if (latestSpectrum != null
        && latestSpectrumFrameIndex == block.frameIndex()
        && latestSpectrumTimestampNanos == block.timestampNanos()) {
      maybeUpdateDisplayState(latestSpectrum);
      return latestSpectrum;
    }
    SpectrumAnalyzer currentAnalyzer = analyzer;
    if (currentAnalyzer == null
        || currentAnalyzer.fftSize() != FFT_SIZE
        || latestSpectrum == null
        || Math.abs(latestSpectrum.sampleRate() - block.format().sampleRate())
            > SAMPLE_RATE_TOLERANCE) {
      currentAnalyzer = new SpectrumAnalyzer(FFT_SIZE, 0, block.format().sampleRate());
      analyzer = currentAnalyzer;
    }
    latestSpectrum = currentAnalyzer.analyze(block);
    latestSpectrumFrameIndex = block.frameIndex();
    latestSpectrumTimestampNanos = block.timestampNanos();
    maybeUpdateDisplayState(latestSpectrum);
    return latestSpectrum;
  }

  private void maybeUpdateDisplayState(SpectrumSnapshot snapshot) {
    if (snapshot == null) {
      return;
    }
    if (snapshot.sourceFrameIndex() == displayStateFrameIndex) {
      return;
    }
    displayState.update(snapshot);
    displayStateFrameIndex = snapshot.sourceFrameIndex();
  }

  /**
   * @return peak frequency in Hz, or {@code NaN} if no spectrum is available
   */
  public double getPeakFrequencyHz() {
    SpectrumSnapshot spectrum = getCurrentSpectrum();
    if (spectrum == null || spectrum.binCount() <= 1) {
      return Double.NaN;
    }
    int peakBin = -1;
    float peakMagnitude = 0f;
    for (int bin = 1; bin < spectrum.binCount(); bin++) {
      float magnitude = spectrum.magnitude(bin);
      if (magnitude > peakMagnitude) {
        peakMagnitude = magnitude;
        peakBin = bin;
      }
    }
    if (peakBin < 0) {
      return Double.NaN;
    }
    return spectrum.frequencyOfBin(peakBin);
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g.create();
    try {
      PlotRenderTheme.applyQualityRendering(g2);
      paintSpectrum(g2);
    } finally {
      g2.dispose();
    }
  }

  private void paintSpectrum(Graphics2D g) {
    int width = getWidth();
    int height = getHeight();
    String[] magnitudeTicks = {"0 dB", "-40 dB", "-80 dB"};
    int plotX = PlotRenderTheme.leftAxisMargin(g, magnitudeTicks);
    int plotY = TOP_MARGIN;
    int plotWidth = Math.max(1, width - plotX - RIGHT_MARGIN);
    int plotHeight = Math.max(1, height - TOP_MARGIN - BOTTOM_MARGIN);
    Rectangle plotBounds = new Rectangle(plotX, plotY, plotWidth, plotHeight);
    PlotRenderTheme.drawPlotBackground(g, width, height, plotBounds);
    PlotRenderTheme.drawGrid(g, plotBounds, 8, 6);
    PlotRenderTheme.drawTitle(g, plotBounds.x, 14, "Spectrum");
    PlotRenderTheme.drawXAxisLabel(g, plotBounds, "Frequency [Hz]");
    PlotRenderTheme.drawYAxisLabel(g, plotBounds, "Magnitude [dB rel. peak]", magnitudeTicks);
    PlotRenderTheme.drawYTicks(g, plotBounds, new double[] {0.0d, 0.5d, 1.0d}, magnitudeTicks);

    SpectrumSnapshot spectrum = getCurrentSpectrum();
    if (spectrum == null) {
      PlotRenderTheme.drawEmptyState(g, plotBounds, "No spectrum data");
      return;
    }

    float[] magnitudes = spectrum.magnitudesView();
    if (magnitudes.length <= 1) {
      PlotRenderTheme.drawEmptyState(g, plotBounds, "Insufficient FFT bins");
      return;
    }
    float[] peaks = displayState.isPeakHoldEnabled() ? displayState.peakHold().peaksView() : null;
    float[] averaged =
        displayState.isAveragingEnabled() ? displayState.averager().averageView() : null;
    float referenceMagnitude = findReferenceMagnitude(magnitudes);
    if (peaks != null && peaks.length == magnitudes.length) {
      for (float p : peaks) {
        if (p > referenceMagnitude) {
          referenceMagnitude = p;
        }
      }
    }
    drawSpectrumShape(g, plotBounds, magnitudes, referenceMagnitude);

    if (averaged != null && averaged.length == magnitudes.length) {
      drawTrace(g, plotBounds, averaged, referenceMagnitude, PlotRenderTheme.WAVEFORM_RIGHT);
    }
    if (peaks != null && peaks.length == magnitudes.length) {
      drawTrace(g, plotBounds, peaks, referenceMagnitude, PlotRenderTheme.HIGHLIGHT);
    }

    int peakBin = findPeakBin(magnitudes);
    if (peakBin > 0) {
      double peakHz = spectrum.frequencyOfBin(peakBin);
      int peakX = xForBin(plotBounds, peakBin, magnitudes.length);
      double peakNorm =
          PlotRenderTheme.normalizedMagnitude(
              normalizedMagnitude(magnitudes[peakBin], referenceMagnitude));
      int peakY = yForNormalized(plotBounds, peakNorm);
      g.setColor(PlotRenderTheme.HIGHLIGHT);
      g.setStroke(PlotRenderTheme.PEAK_STROKE);
      g.drawLine(peakX, plotBounds.y, peakX, plotBounds.y + plotBounds.height - 1);
      g.fillOval(peakX - 3, peakY - 3, 6, 6);
      PlotRenderTheme.drawLabel(
          g,
          Math.min(plotBounds.x + plotBounds.width - 120, peakX + 6),
          plotBounds.y + 14,
          String.format("Peak %.1f Hz", peakHz));
    }

    double nyquist = spectrum.sampleRate() / 2.0;
    PlotRenderTheme.drawXTicks(
        g,
        plotBounds,
        new double[] {0.0d, 0.5d, 1.0d},
        new String[] {
          "0 Hz", String.format("%.0f Hz", nyquist / 2.0), String.format("%.0f Hz", nyquist)
        });
  }

  private void drawSpectrumShape(
      Graphics2D g, Rectangle plotBounds, float[] magnitudes, float referenceMagnitude) {
    int bins = magnitudes.length;
    Path2D.Double linePath = new Path2D.Double();
    Polygon areaPolygon = new Polygon();
    areaPolygon.addPoint(plotBounds.x, plotBounds.y + plotBounds.height - 1);
    for (int bin = 1; bin < bins; bin++) {
      int x = xForBin(plotBounds, bin, bins);
      int y =
          yForNormalized(
              plotBounds,
              PlotRenderTheme.normalizedMagnitude(
                  normalizedMagnitude(magnitudes[bin], referenceMagnitude)));
      if (bin == 1) {
        linePath.moveTo(x, y);
      } else {
        linePath.lineTo(x, y);
      }
      areaPolygon.addPoint(x, y);
    }
    areaPolygon.addPoint(plotBounds.x + plotBounds.width - 1, plotBounds.y + plotBounds.height - 1);
    g.setColor(PlotRenderTheme.SPECTRUM_FILL);
    g.fillPolygon(areaPolygon);
    g.setColor(PlotRenderTheme.SPECTRUM_LINE);
    g.setStroke(PlotRenderTheme.TRACE_STROKE);
    g.draw(linePath);
  }

  private void drawTrace(
      Graphics2D g,
      Rectangle plotBounds,
      float[] magnitudes,
      float referenceMagnitude,
      Color color) {
    int bins = magnitudes.length;
    Path2D.Double linePath = new Path2D.Double();
    for (int bin = 1; bin < bins; bin++) {
      int x = xForBin(plotBounds, bin, bins);
      int y =
          yForNormalized(
              plotBounds,
              PlotRenderTheme.normalizedMagnitude(
                  normalizedMagnitude(magnitudes[bin], referenceMagnitude)));
      if (bin == 1) {
        linePath.moveTo(x, y);
      } else {
        linePath.lineTo(x, y);
      }
    }
    g.setColor(color);
    g.setStroke(PlotRenderTheme.THIN_TRACE_STROKE);
    g.draw(linePath);
  }

  private static int findPeakBin(float[] magnitudes) {
    int peakBin = -1;
    float peakMagnitude = MIN_PEAK_MAGNITUDE;
    for (int bin = 1; bin < magnitudes.length; bin++) {
      float magnitude = magnitudes[bin];
      if (magnitude > peakMagnitude) {
        peakMagnitude = magnitude;
        peakBin = bin;
      }
    }
    return peakBin;
  }

  private static float findReferenceMagnitude(float[] magnitudes) {
    float reference = 0f;
    for (int bin = 1; bin < magnitudes.length; bin++) {
      reference = Math.max(reference, magnitudes[bin]);
    }
    return Math.max(reference, MIN_PEAK_MAGNITUDE);
  }

  private static float normalizedMagnitude(float magnitude, float referenceMagnitude) {
    if (referenceMagnitude <= 0f) {
      return 0f;
    }
    return magnitude / referenceMagnitude;
  }

  private static int xForBin(Rectangle plotBounds, int bin, int bins) {
    int denominator = Math.max(1, bins - 2);
    double ratio = (bin - 1.0) / denominator;
    return plotBounds.x + (int) Math.round(ratio * (plotBounds.width - 1));
  }

  private static int yForNormalized(Rectangle plotBounds, double normalized) {
    return plotBounds.y
        + plotBounds.height
        - 1
        - (int) Math.round(normalized * (plotBounds.height - 1));
  }
}
