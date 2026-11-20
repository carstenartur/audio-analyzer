package org.hammer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import org.hammer.audio.AudioCaptureService;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.spectrogram.SpectrogramAnalyzer;
import org.hammer.audio.spectrogram.SpectrogramFrame;
import org.hammer.audio.spectrogram.SpectrogramHistory;
import org.hammer.audio.ui.theme.PlotRenderTheme;

/**
 * Realtime spectrogram / waterfall panel. Displays a rolling time history of FFT magnitude frames
 * as a heatmap (time on the horizontal axis, frequency on the vertical axis, magnitude as colour).
 *
 * <p>New frames are pushed on the right edge; the oldest visible frame is at the left edge. Each
 * column of the offscreen image corresponds to one historical frame. The panel uses a single shared
 * {@link BufferedImage} backing buffer so the hot rendering path performs no allocations once the
 * image is sized.
 */
public final class SpectrogramPanel extends javax.swing.JPanel {

  private static final long serialVersionUID = 1L;
  private static final int FFT_SIZE = 1024;
  private static final int HISTORY_FRAMES = 256;
  // Leaves room for frequency tick labels and the rotated Y-axis title.
  private static final int LEFT_MARGIN = PlotRenderTheme.LEFT_AXIS_MARGIN;
  private static final int RIGHT_MARGIN = 12;
  private static final int TOP_MARGIN = 18;
  private static final int BOTTOM_MARGIN = PlotRenderTheme.BOTTOM_AXIS_MARGIN;
  private static final float SAMPLE_RATE_TOLERANCE = 0.0001f;

  private AudioCaptureService audioCaptureService;
  private transient SpectrogramAnalyzer analyzer;
  private long lastAnalyzedFrameIndex = Long.MIN_VALUE;
  private long lastAnalyzedTimestampNanos = Long.MIN_VALUE;
  private float analyzerSampleRate = -1f;
  private boolean frozen;
  private transient BufferedImage heatmapBuffer;

  /** Create an empty spectrogram panel. */
  public SpectrogramPanel() {
    super(true);
    setPreferredSize(new Dimension(420, 220));
    javax.swing.Timer timer =
        new javax.swing.Timer(UiConstants.REFRESH_INTERVAL_MS, e -> repaint());
    timer.start();
  }

  /**
   * Set the audio capture service that supplies audio blocks.
   *
   * @param service the audio service
   */
  public void setAudioCaptureService(AudioCaptureService service) {
    this.audioCaptureService = service;
    this.analyzer = null;
    this.analyzerSampleRate = -1f;
    this.lastAnalyzedFrameIndex = Long.MIN_VALUE;
    this.lastAnalyzedTimestampNanos = Long.MIN_VALUE;
    this.frozen = false;
  }

  /**
   * Freeze or unfreeze the spectrogram. While frozen new audio blocks are not ingested.
   *
   * @param frozen true to freeze
   */
  public void setFrozen(boolean frozen) {
    this.frozen = frozen;
    repaint();
  }

  /**
   * @return the current spectrogram history (live or frozen), or {@code null} if no audio has been
   *     analyzed yet
   */
  public SpectrogramHistory getHistory() {
    pullLatestIntoHistory();
    return analyzer == null ? null : analyzer.history();
  }

  /**
   * @return a defensive snapshot list of the frames in the rolling history, in chronological order
   */
  public List<SpectrogramFrame> snapshotFrames() {
    SpectrogramHistory history = getHistory();
    return history == null ? List.of() : history.snapshot();
  }

  private void pullLatestIntoHistory() {
    if (frozen) {
      return;
    }
    AudioCaptureService service = audioCaptureService;
    if (service == null) {
      return;
    }
    AudioBlock block = service.getLatestBlock();
    if (block == null) {
      return;
    }
    if (block.frameIndex() == lastAnalyzedFrameIndex
        && block.timestampNanos() == lastAnalyzedTimestampNanos) {
      return;
    }
    float sampleRate = block.format().sampleRate();
    if (analyzer == null
        || analyzer.fftSize() != FFT_SIZE
        || Math.abs(analyzerSampleRate - sampleRate) > SAMPLE_RATE_TOLERANCE) {
      analyzer = new SpectrogramAnalyzer(FFT_SIZE, 0, sampleRate, HISTORY_FRAMES);
      analyzerSampleRate = sampleRate;
    }
    analyzer.analyze(block);
    lastAnalyzedFrameIndex = block.frameIndex();
    lastAnalyzedTimestampNanos = block.timestampNanos();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g.create();
    try {
      PlotRenderTheme.applyQualityRendering(g2);
      paintSpectrogram(g2);
    } finally {
      g2.dispose();
    }
  }

  private void paintSpectrogram(Graphics2D g) {
    int width = getWidth();
    int height = getHeight();
    int plotX = LEFT_MARGIN;
    int plotY = TOP_MARGIN;
    int plotWidth = Math.max(1, width - LEFT_MARGIN - RIGHT_MARGIN);
    int plotHeight = Math.max(1, height - TOP_MARGIN - BOTTOM_MARGIN);
    Rectangle plotBounds = new Rectangle(plotX, plotY, plotWidth, plotHeight);
    PlotRenderTheme.drawPlotBackground(g, width, height, plotBounds);
    PlotRenderTheme.drawTitle(g, plotBounds.x, 14, frozen ? "Spectrogram (frozen)" : "Spectrogram");

    pullLatestIntoHistory();
    SpectrogramHistory history = analyzer == null ? null : analyzer.history();
    if (history == null || history.isEmpty()) {
      PlotRenderTheme.drawGrid(g, plotBounds, 8, 6);
      drawAxisLabels(g, plotBounds, null);
      PlotRenderTheme.drawEmptyState(g, plotBounds, "No spectrogram data");
      return;
    }

    renderHeatmapInto(history);
    if (heatmapBuffer != null) {
      g.drawImage(
          heatmapBuffer,
          plotBounds.x,
          plotBounds.y,
          plotBounds.x + plotBounds.width,
          plotBounds.y + plotBounds.height,
          0,
          0,
          heatmapBuffer.getWidth(),
          heatmapBuffer.getHeight(),
          null);
    }
    PlotRenderTheme.drawGrid(g, plotBounds, 8, 6);
    drawAxisLabels(g, plotBounds, history);
  }

  private void renderHeatmapInto(SpectrogramHistory history) {
    int frames = history.size();
    int bins = history.binCount();
    if (frames < 1 || bins < 2) {
      return;
    }
    if (heatmapBuffer == null
        || heatmapBuffer.getWidth() != frames
        || heatmapBuffer.getHeight() != bins - 1) {
      heatmapBuffer = new BufferedImage(frames, bins - 1, BufferedImage.TYPE_INT_RGB);
    }
    for (int x = 0; x < frames; x++) {
      SpectrogramFrame frame = history.frameAt(x);
      float[] magnitudes = frame.magnitudesView();
      int rowCount = bins - 1;
      for (int y = 0; y < rowCount; y++) {
        // Flip vertically: high frequencies at top, low frequencies at bottom.
        int bin = rowCount - y; // bins 1..rowCount (skip DC)
        if (bin >= magnitudes.length) {
          bin = magnitudes.length - 1;
        }
        double norm = PlotRenderTheme.normalizedMagnitude(magnitudes[bin]);
        heatmapBuffer.setRGB(x, y, magnitudeColor(norm));
      }
    }
  }

  private static int magnitudeColor(double normalized) {
    double v = Math.max(0.0, Math.min(1.0, normalized));
    // Approximate a viridis-like ramp: dark blue → cyan → green → yellow → orange → red.
    double r;
    double g;
    double b;
    if (v < 0.25) {
      double t = v / 0.25;
      r = 0.05 + 0.20 * t;
      g = 0.02 + 0.10 * t;
      b = 0.20 + 0.55 * t;
    } else if (v < 0.5) {
      double t = (v - 0.25) / 0.25;
      r = 0.25 - 0.20 * t;
      g = 0.12 + 0.45 * t;
      b = 0.75 - 0.30 * t;
    } else if (v < 0.75) {
      double t = (v - 0.5) / 0.25;
      r = 0.05 + 0.80 * t;
      g = 0.57 + 0.30 * t;
      b = 0.45 - 0.40 * t;
    } else {
      double t = (v - 0.75) / 0.25;
      r = 0.85 + 0.15 * t;
      g = 0.87 - 0.55 * t;
      b = 0.05;
    }
    int ri = clampByte((int) Math.round(r * 255.0));
    int gi = clampByte((int) Math.round(g * 255.0));
    int bi = clampByte((int) Math.round(b * 255.0));
    return (ri << 16) | (gi << 8) | bi;
  }

  private static int clampByte(int v) {
    if (v < 0) {
      return 0;
    }
    if (v > 255) {
      return 255;
    }
    return v;
  }

  private void drawAxisLabels(Graphics2D g, Rectangle plotBounds, SpectrogramHistory history) {
    g.setColor(Color.WHITE);
    PlotRenderTheme.drawYAxisLabel(g, plotBounds, "Frequency [Hz]");
    if (history != null) {
      float nyquist = history.sampleRate() / 2.0f;
      PlotRenderTheme.drawYTicks(
          g,
          plotBounds,
          new double[] {0.0d, 0.5d, 1.0d},
          new String[] {
            String.format("%.0f Hz", nyquist), String.format("%.0f Hz", nyquist / 2.0f), "0 Hz"
          });
      PlotRenderTheme.drawXTicks(
          g, plotBounds, new double[] {0.0d, 0.5d, 1.0d}, spectrogramTimeLabels(history));
    }
    PlotRenderTheme.drawXAxisLabel(g, plotBounds, "Time [frames; older → newer]");
  }

  /**
   * Builds left/middle/right time tick labels for the visible spectrogram history.
   *
   * @param history rolling spectrogram history; may be empty during startup
   * @return relative seconds labels when frame indices are available, otherwise frame-count labels
   */
  private String[] spectrogramTimeLabels(SpectrogramHistory history) {
    if (history == null || history.size() <= 1) {
      return new String[] {"0", "0", "0"};
    }
    long startFrame = history.frameAt(0).sourceFrameIndex();
    long endFrame = history.frameAt(history.size() - 1).sourceFrameIndex();
    double durationSeconds =
        Math.max(0.0d, (endFrame - startFrame) / (double) history.sampleRate());
    if (durationSeconds > 0.0d) {
      return new String[] {
        "0.00 s",
        String.format("%.2f s", durationSeconds / 2.0d),
        String.format("%.2f s", durationSeconds)
      };
    }
    int lastFrame = history.size() - 1;
    return new String[] {"0", Integer.toString(lastFrame / 2), Integer.toString(lastFrame)};
  }
}
