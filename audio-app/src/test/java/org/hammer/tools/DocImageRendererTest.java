package org.hammer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class DocImageRendererTest {

  @Test
  void dashboardScreenshotHasExpectedSizeAndVisibleContent() {
    BufferedImage image = DocImageRenderer.renderDashboardScreenshot();

    assertEquals(1600, image.getWidth());
    assertEquals(1000, image.getHeight());
    assertTrue(hasBrightContent(image));
  }

  @Test
  void simulationWorkbenchHasExpectedSizeAndVisibleContent() {
    BufferedImage image = DocImageRenderer.renderSimulationWorkbench();

    assertEquals(760, image.getWidth());
    assertEquals(480, image.getHeight());
    assertTrue(hasBrightContent(image));
  }

  @Test
  void playbackExplorerHasExpectedSizeAndVisibleContent() {
    BufferedImage image = DocImageRenderer.renderPlaybackExplorer();

    assertEquals(760, image.getWidth());
    assertEquals(320, image.getHeight());
    assertTrue(hasBrightContent(image));
  }

  @Test
  void importedRecordingWorkbenchHasExpectedSizeAndVisibleContent() {
    BufferedImage image = DocImageRenderer.renderImportedRecordingWorkbench();

    assertEquals(760, image.getWidth());
    assertEquals(480, image.getHeight());
    assertTrue(hasBrightContent(image));
  }

  @Test
  void generatorCalibrationHasExpectedSizeAndVisibleContent() {
    BufferedImage image = DocImageRenderer.renderGeneratorCalibration();

    assertEquals(760, image.getWidth());
    assertEquals(480, image.getHeight());
    assertTrue(hasBrightContent(image));
  }

  private static boolean hasBrightContent(BufferedImage image) {
    for (int y = 0; y < image.getHeight(); y += 20) {
      for (int x = 0; x < image.getWidth(); x += 20) {
        Color color = new Color(image.getRGB(x, y));
        if (color.getRed() > 120 || color.getGreen() > 120 || color.getBlue() > 120) {
          return true;
        }
      }
    }
    return false;
  }
}
