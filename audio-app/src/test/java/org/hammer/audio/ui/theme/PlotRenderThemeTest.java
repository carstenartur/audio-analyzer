package org.hammer.audio.ui.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PlotRenderThemeTest {

  @Test
  void xAxisTitleHasItsOwnLineBelowTickLabels() {
    Rectangle plot = new Rectangle(80, 24, 200, 100);
    Rectangle ticks =
        renderedBounds(
            g ->
                PlotRenderTheme.drawXTicks(g, plot, new double[] {0.5}, new String[] {"11025 Hz"}));
    Rectangle title =
        renderedBounds(g -> PlotRenderTheme.drawXAxisLabel(g, plot, "Frequency [Hz]"));

    assertTrue(title.y >= ticks.y + ticks.height + 4, "Axis title must not overlap tick labels");
    assertTrue(title.y + title.height <= 168, "Axis title must fit within the bottom margin");
  }

  @Test
  void yAxisTitleHasItsOwnColumnBeforeFrequencyTicks() {
    assertYAxisColumnsSeparated(new String[] {"22.1 kHz"});
  }

  @Test
  void yAxisMarginGrowsForWideFrequencyTickLabels() {
    Rectangle plot = assertYAxisColumnsSeparated(new String[] {"192000.000 Hz"});
    assertTrue(
        plot.x > PlotRenderTheme.LEFT_AXIS_MARGIN,
        () -> "Wide ticks must reserve more than the minimum margin: plot=" + plot);
  }

  private static Rectangle assertYAxisColumnsSeparated(String[] labels) {
    BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    Rectangle plot;
    try {
      PlotRenderTheme.applyQualityRendering(graphics);
      plot = new Rectangle(PlotRenderTheme.leftAxisMargin(graphics, labels), 24, 200, 100);
    } finally {
      graphics.dispose();
    }
    Rectangle ticks =
        renderedBounds(g -> PlotRenderTheme.drawYTicks(g, plot, new double[] {0.5}, labels));
    Rectangle title =
        renderedBounds(g -> PlotRenderTheme.drawYAxisLabel(g, plot, "Frequency [Hz]", labels));

    assertTrue(
        title.x + title.width + 4 <= ticks.x,
        () ->
            "Axis title must not overlap frequency ticks: title="
                + title
                + ", ticks="
                + ticks
                + ", measured gap="
                + (ticks.x - title.x - title.width)
                + " px, required=4 px, plot="
                + plot);
    assertTrue(
        title.x >= 4,
        () ->
            "Axis title must remain inside the left image margin: title="
                + title
                + ", plot="
                + plot);
    return plot;
  }

  @Test
  void magnitudeToDb_appliesFloorForVerySmallValues() {
    double db = PlotRenderTheme.magnitudeToDb(0f);
    assertEquals(-80.0d, db, 1e-9);
  }

  @Test
  void magnitudeToDb_scalesExpectedReferenceValues() {
    assertEquals(0.0d, PlotRenderTheme.magnitudeToDb(1f), 1e-6);
    assertEquals(-20.0d, PlotRenderTheme.magnitudeToDb(0.1f), 1e-6);
  }

  @Test
  void normalizedDb_clampsToZeroAndOne() {
    assertEquals(0.0d, PlotRenderTheme.normalizedDb(-120.0d), 1e-9);
    assertEquals(0.0d, PlotRenderTheme.normalizedDb(-80.0d), 1e-9);
    assertEquals(1.0d, PlotRenderTheme.normalizedDb(0.0d), 1e-9);
    assertTrue(PlotRenderTheme.normalizedDb(-40.0d) > 0.4d);
    assertTrue(PlotRenderTheme.normalizedDb(-40.0d) < 0.6d);
  }

  @Test
  void tickLabelsRenderInsideReservedMargins() {
    BufferedImage image = new BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    Rectangle plot = new Rectangle(56, 24, 140, 90);
    try {
      PlotRenderTheme.applyQualityRendering(g);
      PlotRenderTheme.drawPlotBackground(g, image.getWidth(), image.getHeight(), plot);
      PlotRenderTheme.drawXTicks(
          g, plot, new double[] {0.0d, 0.5d, 1.0d}, new String[] {"0 Hz", "11 kHz", "22 kHz"});
      PlotRenderTheme.drawYTicks(
          g, plot, new double[] {0.0d, 0.5d, 1.0d}, new String[] {"0 dB", "-40 dB", "-80 dB"});
    } finally {
      g.dispose();
    }

    assertTrue(hasNonBackgroundPixel(image, 0, 0, plot.x, image.getHeight()));
    assertTrue(
        hasNonBackgroundPixel(
            image,
            0,
            plot.y + plot.height,
            image.getWidth(),
            image.getHeight() - plot.y - plot.height));
  }

  @Test
  void tickRendererRejectsMismatchedLabels() {
    BufferedImage image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      Rectangle plot = new Rectangle(32, 16, 72, 40);
      assertThrows(
          IllegalArgumentException.class,
          () -> PlotRenderTheme.drawXTicks(g, plot, new double[] {0.0d}, new String[] {"0", "1"}));
      assertThrows(
          IllegalArgumentException.class,
          () -> PlotRenderTheme.drawYTicks(g, plot, new double[] {0.0d}, new String[] {"0", "1"}));
    } finally {
      g.dispose();
    }
  }

  private static boolean hasNonBackgroundPixel(
      BufferedImage image, int startX, int startY, int width, int height) {
    int background = PlotRenderTheme.PANEL_BACKGROUND.getRGB() & 0x00ffffff;
    for (int y = startY; y < startY + height; y++) {
      for (int x = startX; x < startX + width; x++) {
        if ((image.getRGB(x, y) & 0x00ffffff) != background) {
          return true;
        }
      }
    }
    return false;
  }

  private static Rectangle renderedBounds(Consumer<Graphics2D> drawing) {
    BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      PlotRenderTheme.applyQualityRendering(graphics);
      drawing.accept(graphics);
    } finally {
      graphics.dispose();
    }
    Rectangle bounds = null;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if ((image.getRGB(x, y) >>> 24) != 0) {
          Rectangle pixel = new Rectangle(x, y, 1, 1);
          bounds = bounds == null ? pixel : bounds.union(pixel);
        }
      }
    }
    assertTrue(bounds != null, "The drawing must contain visible pixels");
    return bounds;
  }
}
