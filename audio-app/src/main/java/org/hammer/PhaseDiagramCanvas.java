package org.hammer;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.logging.Logger;
import javax.swing.JPanel;
import org.hammer.audio.AudioCaptureService;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.snapshot.PhaseScopeSnapshot;
import org.hammer.audio.ui.theme.PlotRenderTheme;

/**
 * Canvas for displaying phase diagram (X-Y plot of two channels).
 *
 * <p>Refactored to use AudioCaptureService and WaveformModel instead of direct singleton access.
 * Uses thread-safe model snapshots for rendering.
 *
 * @author chammer
 */
public class PhaseDiagramCanvas extends JPanel {

  private static final Logger LOGGER = Logger.getLogger(PhaseDiagramCanvas.class.getName());
  private static final int RIGHT_MARGIN = 12;
  private static final int TOP_MARGIN = 20;
  private static final int BOTTOM_MARGIN = PlotRenderTheme.BOTTOM_AXIS_MARGIN;

  private AudioCaptureService audioCaptureService;

  public PhaseDiagramCanvas() {
    super(true);
    // Timer to periodically repaint at consistent interval for smooth display updates
    LOGGER.info("PhaseDiagramCanvas created");
    javax.swing.Timer t = new javax.swing.Timer(UiConstants.REFRESH_INTERVAL_MS, e -> repaint());
    t.start();
  }

  /**
   * Set the audio capture service to use for phase diagram data.
   *
   * @param service the AudioCaptureService
   */
  public void setAudioCaptureService(AudioCaptureService service) {
    LOGGER.info("AudioCaptureService set: " + (service != null));
    this.audioCaptureService = service;
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g.create();
    try {
      PlotRenderTheme.applyQualityRendering(g2);
      paintPhaseScope(g2);
    } finally {
      g2.dispose();
    }
  }

  private void paintPhaseScope(Graphics2D g2) {
    if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
      LOGGER.fine("paintComponent called");
    }
    int width = Math.max(1, getWidth());
    int height = Math.max(1, getHeight());
    String[] amplitudeTicks = {"+1", "0", "-1"};
    int leftMargin = PlotRenderTheme.leftAxisMargin(g2, amplitudeTicks);
    Rectangle plotBounds =
        new Rectangle(
            leftMargin,
            TOP_MARGIN,
            Math.max(1, width - leftMargin - RIGHT_MARGIN),
            Math.max(1, height - TOP_MARGIN - BOTTOM_MARGIN));
    PlotRenderTheme.drawPlotBackground(g2, getWidth(), getHeight(), plotBounds);
    PlotRenderTheme.drawGrid(g2, plotBounds, 6, 6);
    drawReferenceGrid(g2, plotBounds);
    PlotRenderTheme.drawTitle(g2, 10, 16, "Phase Scope");
    PlotRenderTheme.drawXAxisLabel(g2, plotBounds, "Left amplitude [-1..1]");
    PlotRenderTheme.drawYAxisLabel(g2, plotBounds, "Right amplitude [-1..1]", amplitudeTicks);
    PlotRenderTheme.drawXTicks(
        g2, plotBounds, new double[] {0.0d, 0.5d, 1.0d}, new String[] {"-1", "0", "+1"});
    PlotRenderTheme.drawYTicks(g2, plotBounds, new double[] {0.0d, 0.5d, 1.0d}, amplitudeTicks);

    if (audioCaptureService == null) {
      LOGGER.warning("paintComponent: audioCaptureService is null");
      PlotRenderTheme.drawEmptyState(g2, plotBounds, "No audio service connected");
      return;
    }

    AudioBlock latestBlock = audioCaptureService.getLatestBlock();
    if (latestBlock == null) {
      PlotRenderTheme.drawEmptyState(g2, plotBounds, "Waiting for stereo samples...");
      return;
    }
    PhaseScopeSnapshot snapshot = PhaseScopeSnapshot.fromBlock(latestBlock);
    if (snapshot.frames() == 0) {
      PlotRenderTheme.drawEmptyState(g2, plotBounds, "Stereo data unavailable");
      return;
    }
    drawPhaseTrace(g2, plotBounds, snapshot);
  }

  private void drawReferenceGrid(Graphics2D g2, Rectangle plotBounds) {
    int centerX = plotBounds.x + plotBounds.width / 2;
    int centerY = plotBounds.y + plotBounds.height / 2;
    int radius = Math.max(4, Math.min(plotBounds.width, plotBounds.height) / 2 - 12);
    g2.setColor(PlotRenderTheme.AXIS_COLOR);
    g2.setStroke(PlotRenderTheme.AXIS_STROKE);
    g2.drawLine(plotBounds.x + 6, centerY, plotBounds.x + plotBounds.width - 6, centerY);
    g2.drawLine(centerX, plotBounds.y + 6, centerX, plotBounds.y + plotBounds.height - 6);
    g2.setStroke(PlotRenderTheme.GRID_STROKE);
    for (int step = 1; step <= 4; step++) {
      int currentRadius = radius * step / 4;
      g2.setColor(step == 4 ? PlotRenderTheme.GRID_MAJOR : PlotRenderTheme.GRID_MINOR);
      g2.drawOval(
          centerX - currentRadius, centerY - currentRadius, 2 * currentRadius, 2 * currentRadius);
    }
  }

  private void drawPhaseTrace(Graphics2D g2, Rectangle plotBounds, PhaseScopeSnapshot snapshot) {
    float[] left = snapshot.leftView();
    float[] right = snapshot.rightView();
    int pointCount = Math.min(left.length, right.length);
    if (pointCount < 2) {
      return;
    }

    int centerX = plotBounds.x + plotBounds.width / 2;
    int centerY = plotBounds.y + plotBounds.height / 2;
    int radius = Math.max(4, Math.min(plotBounds.width, plotBounds.height) / 2 - 12);
    int[] xPoints = new int[pointCount];
    int[] yPoints = new int[pointCount];
    for (int i = 0; i < pointCount; i++) {
      double xNorm = Math.max(-1d, Math.min(1d, left[i]));
      double yNorm = Math.max(-1d, Math.min(1d, right[i]));
      xPoints[i] = centerX + (int) Math.round(xNorm * radius);
      yPoints[i] = centerY - (int) Math.round(yNorm * radius);
    }

    g2.setColor(PlotRenderTheme.PHASE_TRACE);
    g2.setStroke(PlotRenderTheme.THIN_TRACE_STROKE);
    g2.drawPolyline(xPoints, yPoints, pointCount);
  }
}
