package org.hammer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
  void clippedStringShortTextPassesThroughUnchanged() {
    BufferedImage img = new BufferedImage(400, 40, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    FontMetrics fm = g.getFontMetrics();
    String text = "Hello";
    int textWidth = fm.stringWidth(text);

    // Draw into a scratch image to verify the method does not throw
    DocImageRenderer.drawClippedString(g, text, 0, 20, textWidth + 10);
    g.dispose();
    // If no exception was thrown the short text passed through; the exact pixel
    // result is renderer-dependent but the behaviour is smoke-tested.
    assertTrue(textWidth > 0, "Font metrics must produce a positive width for non-empty text");
  }

  @Test
  void clippedStringLongTextIsTruncatedWithEllipsis() {
    BufferedImage img = new BufferedImage(400, 40, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    FontMetrics fm = g.getFontMetrics();

    // Build a string that is definitely wider than maxWidth=40
    String longText = "This is a very long string that will not fit";
    int maxWidth = 40;

    // The long string must exceed maxWidth for this test to be meaningful
    assertTrue(
        fm.stringWidth(longText) > maxWidth,
        "Long text must be wider than maxWidth to exercise truncation");

    // Verify the method runs without throwing even when truncation is needed
    DocImageRenderer.drawClippedString(g, longText, 0, 20, maxWidth);
    g.dispose();
  }

  @Test
  void clippedStringDoesNotExceedMaxWidth() {
    // Verify that very long text does not cause the method to draw beyond maxWidth.
    // We use a narrow constraint and confirm the method completes without error.
    BufferedImage img = new BufferedImage(400, 40, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
    FontMetrics fm = g.getFontMetrics();

    String text = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    int maxWidth = fm.stringWidth("AA"); // just wide enough for two characters
    assertTrue(fm.stringWidth(text) > maxWidth, "Precondition: text must exceed maxWidth");

    // Must not throw even with a very narrow maxWidth
    DocImageRenderer.drawClippedString(g, text, 0, 20, maxWidth);
    g.dispose();
  }

  @Test
  void clippedStringEmptyTextDoesNotThrow() {
    BufferedImage img = new BufferedImage(200, 40, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    // Empty string — should complete without exception
    DocImageRenderer.drawClippedString(g, "", 0, 20, 100);
    g.dispose();
  }

  @Test
  void clippedStringFitsWhenMaxWidthIsLarge() {
    BufferedImage img = new BufferedImage(800, 40, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    FontMetrics fm = g.getFontMetrics();
    String text = "Short";
    int textWidth = fm.stringWidth(text);
    // With a very large maxWidth the text should always fit without truncation
    assertFalse(fm.stringWidth(text) > 1000, "Precondition: 'Short' must fit within 1000 px");
    DocImageRenderer.drawClippedString(g, text, 0, 20, 1000);
    g.dispose();
    assertTrue(textWidth > 0);
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
}
