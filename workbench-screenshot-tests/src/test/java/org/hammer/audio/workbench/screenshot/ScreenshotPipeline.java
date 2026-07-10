package org.hammer.audio.workbench.screenshot;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.imageio.ImageIO;

/**
 * Utilities for capturing, storing and comparing workbench screenshots.
 *
 * <h2>Modes</h2>
 *
 * <dl>
 *   <dt>Verify mode (default)
 *   <dd>Screenshot is written to a temporary output directory. If a committed baseline exists under
 *       {@code docs/assets/screenshots/workbench/} the generated screenshot is compared
 *       pixel-by-pixel against the baseline. The build fails if the pixel-difference ratio exceeds
 *       {@link #tolerancePct()} or if the baseline is missing.
 *   <dt>Update mode ({@code -DupdateScreenshots=true})
 *   <dd>Screenshot is written directly to {@code docs/assets/screenshots/workbench/}. The test
 *       always passes. Use this mode intentionally when a UI change should update committed
 *       documentation screenshots.
 * </dl>
 *
 * <h2>Volatile region masking</h2>
 *
 * <p>Volatile regions (status bars, timestamps, live metrics) must be masked before comparison. The
 * workbench HTML page does not display volatile data in the seed workflow, so full-page comparison
 * is safe for the default scenarios. If future scenarios include volatile regions, mark the
 * corresponding DOM elements with {@code data-screenshot-mask="true"} and pass their bounding boxes
 * to {@link #compareScreenshots} as masked regions.
 *
 * <p>The masking/determinism rules are documented in {@code docs/workbench-screenshot-pipeline.md}.
 */
public final class ScreenshotPipeline {

  private static final String PROP_UPDATE = "updateScreenshots";
  private static final String PROP_TOLERANCE = "screenshot.tolerance.pct";
  private static final String PROP_DOCS_DIR = "docs.screenshots.dir";

  private ScreenshotPipeline() {}

  /**
   * Returns {@code true} when the screenshot pipeline is running in update mode.
   *
   * @return {@code true} if {@code -DupdateScreenshots=true} was set
   */
  public static boolean isUpdateMode() {
    return Boolean.parseBoolean(System.getProperty(PROP_UPDATE, "false"));
  }

  /**
   * Returns the configured pixel-difference tolerance percentage.
   *
   * @return tolerance in the range 0–100
   */
  public static int tolerancePct() {
    return Integer.parseInt(System.getProperty(PROP_TOLERANCE, "2"));
  }

  /**
   * Returns the committed documentation screenshots directory.
   *
   * @return path to {@code docs/assets/screenshots/workbench/}
   */
  public static Path docsScreenshotsDir() {
    String dir = System.getProperty(PROP_DOCS_DIR);
    if (dir == null || dir.isBlank()) {
      throw new IllegalStateException("System property '" + PROP_DOCS_DIR + "' is not set");
    }
    return Path.of(dir);
  }

  /**
   * Processes a screenshot captured by Playwright.
   *
   * <p>In update mode the screenshot is written to the committed docs path and the method returns
   * without comparison.
   *
   * <p>In verify mode the screenshot is written to a temp file and compared against the committed
   * baseline. The method throws {@link AssertionError} if the baseline is missing or if the
   * pixel-difference ratio exceeds the configured tolerance.
   *
   * @param screenshotName file name without directory, e.g. {@code "initial-load.png"}
   * @param pngBytes raw PNG bytes captured by Playwright
   * @throws IOException if reading/writing screenshot files fails
   */
  public static void processScreenshot(String screenshotName, byte[] pngBytes) throws IOException {
    if (isUpdateMode()) {
      writeUpdatedScreenshot(screenshotName, pngBytes);
    } else {
      verifyScreenshot(screenshotName, pngBytes);
    }
  }

  private static void writeUpdatedScreenshot(String name, byte[] pngBytes) throws IOException {
    Path docsDir = docsScreenshotsDir();
    Files.createDirectories(docsDir);
    Path target = docsDir.resolve(name);
    Files.write(target, pngBytes);
    System.out.println("[screenshot-pipeline] Updated: " + target);
  }

  private static void verifyScreenshot(String name, byte[] pngBytes) throws IOException {
    Path baseline = docsScreenshotsDir().resolve(name);
    if (!Files.exists(baseline)) {
      // Write generated screenshot to target directory for debugging
      writeGeneratedForDebug(name, pngBytes);
      throw new AssertionError(
          "Missing baseline screenshot: "
              + baseline
              + ". Run with -DupdateScreenshots=true to create the initial baseline.");
    }

    BufferedImage expected = ImageIO.read(baseline.toFile());
    BufferedImage actual = ImageIO.read(new ByteArrayInputStream(pngBytes));

    if (expected == null) {
      throw new AssertionError("Cannot read baseline screenshot: " + baseline);
    }
    if (actual == null) {
      throw new AssertionError("Cannot decode generated screenshot for: " + name);
    }

    ComparisonResult result = compareScreenshots(expected, actual, tolerancePct());
    if (!result.passed()) {
      writeGeneratedForDebug(name, pngBytes);
      throw new AssertionError(
          "Screenshot mismatch for '"
              + name
              + "': "
              + result.diffPercent()
              + "% pixels differ (tolerance: "
              + tolerancePct()
              + "%). "
              + "Generated screenshot written to target/screenshot-failures/. "
              + "Run with -DupdateScreenshots=true to update the baseline.");
    }
    System.out.println(
        "[screenshot-pipeline] Verified: " + name + " (" + result.diffPercent() + "% diff)");
  }

  private static void writeGeneratedForDebug(String name, byte[] pngBytes) {
    try {
      Path failuresDir = Path.of("target/screenshot-failures");
      Files.createDirectories(failuresDir);
      Files.write(failuresDir.resolve(name), pngBytes);
    } catch (IOException ex) {
      System.err.println(
          "[screenshot-pipeline] Could not write debug screenshot: " + ex.getMessage());
    }
  }

  /**
   * Copies the generated screenshot to the documented path for CI artifact upload.
   *
   * <p>This method is called on test failure so that the generated screenshots are always available
   * as build artifacts, making UI regressions inspectable.
   *
   * @param screenshotName file name, e.g. {@code "initial-load.png"}
   * @param pngBytes raw PNG bytes
   * @throws IOException if writing fails
   */
  public static void saveFailureArtifact(String screenshotName, byte[] pngBytes)
      throws IOException {
    Path dir = Path.of("target/screenshot-failures");
    Files.createDirectories(dir);
    Files.copy(
        new ByteArrayInputStream(pngBytes),
        dir.resolve(screenshotName),
        StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Compares two images pixel-by-pixel.
   *
   * @param expected committed baseline image
   * @param actual generated screenshot
   * @param tolerancePct allowed percentage of differing pixels (0–100)
   * @return comparison result
   */
  static ComparisonResult compareScreenshots(
      BufferedImage expected, BufferedImage actual, int tolerancePct) {
    if (expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()) {
      return new ComparisonResult(false, 100.0);
    }
    int width = expected.getWidth();
    int height = expected.getHeight();
    long diffPixels = 0;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        if (!pixelsMatch(expected.getRGB(x, y), actual.getRGB(x, y))) {
          diffPixels++;
        }
      }
    }
    long totalPixels = (long) width * height;
    double diffPct = totalPixels == 0 ? 0.0 : (diffPixels * 100.0 / totalPixels);
    return new ComparisonResult(diffPct <= tolerancePct, diffPct);
  }

  private static boolean pixelsMatch(int rgb1, int rgb2) {
    // Allow up to 8 per-channel difference to tolerate minor rendering variations
    int dr = Math.abs(((rgb1 >> 16) & 0xff) - ((rgb2 >> 16) & 0xff));
    int dg = Math.abs(((rgb1 >> 8) & 0xff) - ((rgb2 >> 8) & 0xff));
    int db = Math.abs((rgb1 & 0xff) - (rgb2 & 0xff));
    return dr <= 8 && dg <= 8 && db <= 8;
  }

  /**
   * Result of a pixel-by-pixel screenshot comparison.
   *
   * @param passed whether the comparison passed within the configured tolerance
   * @param diffPercent percentage of pixels that differ
   */
  record ComparisonResult(boolean passed, double diffPercent) {}
}
