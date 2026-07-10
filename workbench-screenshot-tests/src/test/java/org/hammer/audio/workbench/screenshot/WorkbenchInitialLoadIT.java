package org.hammer.audio.workbench.screenshot;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opentest4j.TestAbortedException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * Integration tests for the workbench UI that also capture documentation screenshots.
 *
 * <h2>Prerequisites</h2>
 *
 * <p>These tests require Docker to be available. On machines without Docker all tests are skipped
 * gracefully via {@link org.junit.jupiter.api.Assumptions#assumeTrue}.
 *
 * <h2>Running the tests</h2>
 *
 * <pre>
 * # Verify mode (default) — compare generated screenshots against committed baselines:
 * mvn -Pscreenshot-tests verify
 *
 * # Update mode — regenerate committed documentation screenshots after intentional UI changes:
 * mvn -Pscreenshot-tests verify -DupdateScreenshots=true
 * </pre>
 *
 * <h2>Screenshot locations</h2>
 *
 * <p>Committed documentation screenshots are stored under {@code
 * docs/assets/screenshots/workbench/}. Generated screenshots and failure artifacts are written to
 * {@code workbench-screenshot-tests/target/screenshot-failures/}.
 *
 * <h2>Volatile UI regions</h2>
 *
 * <p>The seed workflow served by {@code WorkflowHttpServerLauncher} contains no volatile data (no
 * timestamps, no live telemetry). The workbench HTML page disables all CSS animations. Full-page
 * screenshots are therefore deterministic.
 */
@Tag("screenshot")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkbenchInitialLoadIT {

  private static final int VIEWPORT_WIDTH = 1280;
  private static final int VIEWPORT_HEIGHT = 720;
  private static final int PAGE_LOAD_TIMEOUT_MS = 15_000;
  private static final String EXPECTED_STATUS_PREFIX = "Loaded:";

  private GenericContainer<?> container;
  private Playwright playwright;
  private Browser browser;

  @BeforeAll
  void setUp() throws IOException {
    assumeTrue(
        isDockerAvailable(), "Docker is not available — skipping screenshot integration tests");
    assumeTrue(isJarAvailable(), "audio-app JAR not found — build the project first");

    try {
      container = WorkbenchContainerFactory.create();
    } catch (IllegalStateException ex) {
      throw new TestAbortedException(ex.getMessage(), ex);
    }
    container.start();

    playwright = Playwright.create();
    browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
  }

  @AfterAll
  void tearDown() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
    if (container != null && container.isRunning()) {
      container.stop();
    }
  }

  /**
   * Verifies that the workbench loads with the seed workflow and captures an initial-load
   * documentation screenshot.
   *
   * <p>Scenario: the workbench is opened at the root URL, the node palette is loaded from the
   * catalog API, and the graph is populated from the workflow projection API. The seed workflow
   * contains three nodes ({@code Synthetic Signal Generator → Gain → Localization}) connected by
   * two typed edges.
   */
  @Test
  void workbenchInitialLoadShowsSeedWorkflow() throws IOException {
    String baseUrl = WorkbenchContainerFactory.baseUrl(container);

    try (Page page = browser.newPage()) {
      page.setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);

      // Navigate to the workbench
      page.navigate(baseUrl + "/");
      page.waitForLoadState();

      // Disable blinking cursors and any remaining animations before capture
      page.addStyleTag(
          new Page.AddStyleTagOptions()
              .setContent(
                  "*, *::before, *::after {"
                      + " animation: none !important;"
                      + " transition: none !important;"
                      + " caret-color: transparent !important;"
                      + " }"));

      // Assert: node palette is visible
      Locator palette = page.locator("[data-testid='node-palette']");
      palette.waitFor(new Locator.WaitForOptions().setTimeout(PAGE_LOAD_TIMEOUT_MS));
      assertTrue(palette.isVisible(), "Node palette must be visible");

      // Assert: catalog nodes loaded (at least one palette entry)
      Locator catalogList = page.locator("[data-testid='catalog-list']");
      catalogList.waitFor(new Locator.WaitForOptions().setTimeout(PAGE_LOAD_TIMEOUT_MS));
      List<String> catalogTypes = page.locator("[data-testid^='palette-node-']").allInnerTexts();
      assertNotNull(catalogTypes, "Catalog list must not be null");
      assertTrue(catalogTypes.size() >= 3, "At least 3 catalog node types must be visible");

      // Assert: graph canvas is visible
      Locator graphCanvas = page.locator("[data-testid='graph-canvas']");
      graphCanvas.waitFor(new Locator.WaitForOptions().setTimeout(PAGE_LOAD_TIMEOUT_MS));

      // Assert: workbench title is present
      Locator title = page.locator("[data-testid='workbench-title']");
      assertNotNull(title.innerText(), "Workbench title must be present");

      // Assert: seed graph nodes are rendered
      Locator seedInput = page.locator("[data-testid='node-seed.input']");
      Locator seedGain = page.locator("[data-testid='node-seed.gain']");
      Locator seedOutput = page.locator("[data-testid='node-seed.output']");
      seedInput.waitFor(new Locator.WaitForOptions().setTimeout(PAGE_LOAD_TIMEOUT_MS));
      assertTrue(seedInput.isVisible(), "Seed input node must be visible");
      assertTrue(seedGain.isVisible(), "Seed gain node must be visible");
      assertTrue(seedOutput.isVisible(), "Seed output node must be visible");

      // Assert: typed port handles are visible on seed.gain node
      Locator gainInputPorts = page.locator("[data-testid='input-ports-seed.gain']");
      Locator gainOutputPorts = page.locator("[data-testid='output-ports-seed.gain']");
      assertTrue(gainInputPorts.isVisible(), "Gain node input ports must be visible");
      assertTrue(gainOutputPorts.isVisible(), "Gain node output ports must be visible");

      // Assert: status bar shows loaded state (not an error)
      Locator statusMsg = page.locator("[data-testid='status-message']");
      String statusText = statusMsg.innerText();
      assertTrue(
          statusText.startsWith(EXPECTED_STATUS_PREFIX),
          "Status message must indicate successful load, got: " + statusText);

      // Capture full-page screenshot
      byte[] pngBytes = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));

      // Process screenshot: write to docs (update mode) or compare against baseline (verify mode)
      ScreenshotPipeline.processScreenshot("initial-load.png", pngBytes);
    }
  }

  /**
   * Verifies that the node palette shows all catalog nodes including the first experiment catalog
   * nodes and captures a node-palette documentation screenshot.
   */
  @Test
  void nodePaletteShowsExperimentCatalogNodes() throws IOException {
    String baseUrl = WorkbenchContainerFactory.baseUrl(container);

    try (Page page = browser.newPage()) {
      page.setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
      page.navigate(baseUrl + "/");
      page.waitForLoadState();

      page.addStyleTag(
          new Page.AddStyleTagOptions()
              .setContent(
                  "*, *::before, *::after {"
                      + " animation: none !important;"
                      + " transition: none !important; }"));

      // Wait for the catalog to load
      Locator recordingInputEntry = page.locator("[data-testid='palette-node-recording-input']");
      recordingInputEntry.waitFor(new Locator.WaitForOptions().setTimeout(PAGE_LOAD_TIMEOUT_MS));
      assertTrue(recordingInputEntry.isVisible(), "Recording-input palette node must be visible");

      // Assert typed port info is shown
      String entryText = recordingInputEntry.innerText();
      assertTrue(entryText.contains("Recording Input"), "Entry must show label");

      // Capture palette screenshot
      Locator palette = page.locator("[data-testid='node-palette']");
      byte[] pngBytes = palette.screenshot();

      ScreenshotPipeline.processScreenshot("node-palette.png", pngBytes);
    }
  }

  private static boolean isDockerAvailable() {
    try {
      return DockerClientFactory.instance().isDockerAvailable();
    } catch (Exception ex) {
      return false;
    }
  }

  private static boolean isJarAvailable() {
    String jarPath = System.getProperty("workbench.jar.path");
    if (jarPath == null || jarPath.isBlank()) {
      return false;
    }
    return Files.exists(Path.of(jarPath));
  }
}
