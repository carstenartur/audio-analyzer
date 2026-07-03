package org.hammer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class DocImageRendererTest {

  // -------------------------------------------------------------------------
  // Dashboard screenshot (1600 × 1000)
  // -------------------------------------------------------------------------

  @Test
  void dashboardScreenshotHasExpectedSizeAndVisibleContent() {
    BufferedImage image = DocImageRenderer.renderDashboardScreenshot();

    assertEquals(1600, image.getWidth());
    assertEquals(1000, image.getHeight());
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth(), image.getHeight()));
  }

  @Test
  void dashboardScreenshotHasNonBlankTopRegion() {
    BufferedImage image = DocImageRenderer.renderDashboardScreenshot();
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth(), image.getHeight() / 3));
  }

  @Test
  void dashboardScreenshotHasNonBlankMiddleRegion() {
    BufferedImage image = DocImageRenderer.renderDashboardScreenshot();
    int third = image.getHeight() / 3;
    assertTrue(hasBrightContentInRegion(image, 0, third, image.getWidth(), third));
  }

  @Test
  void dashboardScreenshotHasNonBlankBottomRegion() {
    BufferedImage image = DocImageRenderer.renderDashboardScreenshot();
    int third = image.getHeight() / 3;
    assertTrue(
        hasBrightContentInRegion(
            image, 0, 2 * third, image.getWidth(), image.getHeight() - 2 * third));
  }

  // -------------------------------------------------------------------------
  // Simulation workbench (760 × 480)
  // -------------------------------------------------------------------------

  @Test
  void simulationWorkbenchHasExpectedSizeAndVisibleContent() {
    BufferedImage image = DocImageRenderer.renderSimulationWorkbench();

    assertEquals(760, image.getWidth());
    assertEquals(480, image.getHeight());
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth(), image.getHeight()));
  }

  @Test
  void simulationWorkbenchHasNonBlankTopRegion() {
    BufferedImage image = DocImageRenderer.renderSimulationWorkbench();
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth(), image.getHeight() / 3));
  }

  @Test
  void simulationWorkbenchHasNonBlankMiddleRegion() {
    BufferedImage image = DocImageRenderer.renderSimulationWorkbench();
    int third = image.getHeight() / 3;
    assertTrue(hasBrightContentInRegion(image, 0, third, image.getWidth(), third));
  }

  // -------------------------------------------------------------------------
  // Playback explorer (760 × 320)
  // -------------------------------------------------------------------------

  @Test
  void playbackExplorerHasExpectedSizeAndVisibleContent() {
    BufferedImage image = DocImageRenderer.renderPlaybackExplorer();

    assertEquals(760, image.getWidth());
    assertEquals(320, image.getHeight());
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth(), image.getHeight()));
  }

  @Test
  void playbackExplorerHasNonBlankTopRegion() {
    BufferedImage image = DocImageRenderer.renderPlaybackExplorer();
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth(), image.getHeight() / 3));
  }

  @Test
  void playbackExplorerHasNonBlankBottomRegion() {
    BufferedImage image = DocImageRenderer.renderPlaybackExplorer();
    int third = image.getHeight() / 3;
    assertTrue(
        hasBrightContentInRegion(
            image, 0, 2 * third, image.getWidth(), image.getHeight() - 2 * third));
  }

  // -------------------------------------------------------------------------
  // Imported recording workbench (760 × 480)
  // -------------------------------------------------------------------------

  @Test
  void importedRecordingWorkbenchHasExpectedSizeAndVisibleContent() {
    BufferedImage image = DocImageRenderer.renderImportedRecordingWorkbench();

    assertEquals(760, image.getWidth());
    assertEquals(480, image.getHeight());
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth(), image.getHeight()));
  }

  @Test
  void importedRecordingWorkbenchHasNonBlankLeftPanel() {
    BufferedImage image = DocImageRenderer.renderImportedRecordingWorkbench();
    // Left panel (feature table) spans roughly the first 55 % of the width
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth() / 2, image.getHeight()));
  }

  @Test
  void importedRecordingWorkbenchHasNonBlankRightPanel() {
    BufferedImage image = DocImageRenderer.renderImportedRecordingWorkbench();
    // Right panel (harmonic chart + calibration) spans roughly the last 45 % of the width
    int half = image.getWidth() / 2;
    assertTrue(
        hasBrightContentInRegion(image, half, 0, image.getWidth() - half, image.getHeight()));
  }

  // -------------------------------------------------------------------------
  // Generator calibration (760 × 480)
  // -------------------------------------------------------------------------

  @Test
  void generatorCalibrationHasExpectedSizeAndVisibleContent() {
    BufferedImage image = DocImageRenderer.renderGeneratorCalibration();

    assertEquals(760, image.getWidth());
    assertEquals(480, image.getHeight());
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth(), image.getHeight()));
  }

  @Test
  void generatorCalibrationHasNonBlankChartArea() {
    BufferedImage image = DocImageRenderer.renderGeneratorCalibration();
    // Chart area is the central region (title bar at top, legend at bottom)
    int top = image.getHeight() / 5;
    int chartH = (image.getHeight() * 3) / 5;
    assertTrue(hasBrightContentInRegion(image, 0, top, image.getWidth(), chartH));
  }

  // -------------------------------------------------------------------------
  // Feature images (760 × 320 each)
  // -------------------------------------------------------------------------

  @Test
  void triggerImageHasExpectedSizeAndVisibleContent() {
    BufferedImage image = DocImageRenderer.renderTrigger();

    assertEquals(760, image.getWidth());
    assertEquals(320, image.getHeight());
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth(), image.getHeight()));
  }

  @Test
  void triggerImageHasNonBlankTopRegion() {
    BufferedImage image = DocImageRenderer.renderTrigger();
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth(), image.getHeight() / 3));
  }

  @Test
  void triggerImageHasNonBlankMiddleRegion() {
    BufferedImage image = DocImageRenderer.renderTrigger();
    int third = image.getHeight() / 3;
    assertTrue(hasBrightContentInRegion(image, 0, third, image.getWidth(), third));
  }

  @Test
  void spectrumPeakHoldImageHasExpectedSizeAndVisibleContent() {
    BufferedImage image = DocImageRenderer.renderSpectrumPeakHold();

    assertEquals(760, image.getWidth());
    assertEquals(320, image.getHeight());
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth(), image.getHeight()));
  }

  @Test
  void spectrumPeakHoldImageHasNonBlankPlotArea() {
    BufferedImage image = DocImageRenderer.renderSpectrumPeakHold();
    // Plot occupies most of the central region
    int margin = image.getHeight() / 6;
    assertTrue(
        hasBrightContentInRegion(
            image, 0, margin, image.getWidth(), image.getHeight() - 2 * margin));
  }

  @Test
  void recordingFormatImageHasExpectedSizeAndVisibleContent() {
    BufferedImage image = DocImageRenderer.renderRecordingFormat();

    assertEquals(760, image.getWidth());
    assertEquals(320, image.getHeight());
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth(), image.getHeight()));
  }

  @Test
  void recordingFormatImageHasNonBlankTopRegion() {
    BufferedImage image = DocImageRenderer.renderRecordingFormat();
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth(), image.getHeight() / 3));
  }

  @Test
  void abComparisonImageHasExpectedSizeAndVisibleContent() {
    BufferedImage image = DocImageRenderer.renderAbComparison();

    assertEquals(760, image.getWidth());
    assertEquals(320, image.getHeight());
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth(), image.getHeight()));
  }

  @Test
  void abComparisonImageHasNonBlankLeftPlot() {
    BufferedImage image = DocImageRenderer.renderAbComparison();
    // Left spectrum plot is in roughly the left half
    assertTrue(hasBrightContentInRegion(image, 0, 0, image.getWidth() / 2, image.getHeight()));
  }

  @Test
  void abComparisonImageHasNonBlankRightPlot() {
    BufferedImage image = DocImageRenderer.renderAbComparison();
    // Right spectrum plot is in roughly the right half
    int half = image.getWidth() / 2;
    assertTrue(
        hasBrightContentInRegion(image, half, 0, image.getWidth() - half, image.getHeight()));
  }

  // -------------------------------------------------------------------------
  // drawClippedString layout guard tests
  // -------------------------------------------------------------------------

  @Test
  void clipStringShortTextPassesThroughUnchanged() {
    FontMetrics fm = fontMetrics(Font.SANS_SERIF, 12);
    String text = "Hello";
    int maxWidth = fm.stringWidth(text) + 10;

    String result = DocImageRenderer.clipString(fm, text, maxWidth);

    assertEquals(text, result, "Short text that fits must be returned unchanged");
  }

  @Test
  void clipStringLongTextIsTruncatedWithEllipsis() {
    FontMetrics fm = fontMetrics(Font.SANS_SERIF, 12);
    String longText = "This is a very long string that will not fit";
    int maxWidth = 40;
    assertTrue(
        fm.stringWidth(longText) > maxWidth,
        "Precondition: long text must exceed maxWidth to exercise truncation");

    String result = DocImageRenderer.clipString(fm, longText, maxWidth);

    assertTrue(result.endsWith("…"), "Truncated text must end with an ellipsis");
    assertTrue(fm.stringWidth(result) <= maxWidth, "Truncated text width must not exceed maxWidth");
  }

  @Test
  void clipStringResultNeverExceedsMaxWidth() {
    FontMetrics fm = fontMetrics(Font.MONOSPACED, 14);
    String text = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    int maxWidth = fm.stringWidth("AA");
    assertTrue(fm.stringWidth(text) > maxWidth, "Precondition: text must exceed maxWidth");

    String result = DocImageRenderer.clipString(fm, text, maxWidth);

    assertTrue(fm.stringWidth(result) <= maxWidth, "Clipped string width must not exceed maxWidth");
  }

  @Test
  void clipStringEmptyTextReturnsEmpty() {
    FontMetrics fm = fontMetrics(Font.SANS_SERIF, 12);

    String result = DocImageRenderer.clipString(fm, "", 100);

    assertEquals("", result, "Empty input must produce empty output");
  }

  @Test
  void clipStringFitsUnchangedWhenMaxWidthIsLarge() {
    FontMetrics fm = fontMetrics(Font.SANS_SERIF, 12);
    String text = "Short";

    String result = DocImageRenderer.clipString(fm, text, 1000);

    assertEquals(text, result, "Text that easily fits must be returned unchanged");
  }

  @Test
  void clipStringNarrowMaxWidthYieldsEllipsisOnly() {
    FontMetrics fm = fontMetrics(Font.SANS_SERIF, 12);
    // maxWidth=1 is narrower than even the ellipsis; the ellipsis is always returned as a
    // safe fallback.
    String result = DocImageRenderer.clipString(fm, "Hello", 1);

    assertEquals("…", result, "When maxWidth is narrower than the ellipsis, return ellipsis only");
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /**
   * Returns true if any sampled pixel within the given rectangle has a bright channel (> 120).
   * Samples every 10 pixels in both axes to keep the check fast.
   */
  private static boolean hasBrightContentInRegion(
      BufferedImage image, int x0, int y0, int regionW, int regionH) {
    int xEnd = Math.min(x0 + regionW, image.getWidth());
    int yEnd = Math.min(y0 + regionH, image.getHeight());
    for (int y = y0; y < yEnd; y += 10) {
      for (int x = x0; x < xEnd; x += 10) {
        Color color = new Color(image.getRGB(x, y));
        if (color.getRed() > 120 || color.getGreen() > 120 || color.getBlue() > 120) {
          return true;
        }
      }
    }
    return false;
  }

  /** Returns a {@link FontMetrics} for the given font family and size. */
  private static FontMetrics fontMetrics(String family, int size) {
    BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = scratch.createGraphics();
    g.setFont(new Font(family, Font.PLAIN, size));
    FontMetrics fm = g.getFontMetrics();
    g.dispose();
    return fm;
  }
}
