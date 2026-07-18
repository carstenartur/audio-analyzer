package org.hammer.audio.workbench.screenshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opentest4j.TestAbortedException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/** Integration scenario that generates the documented live-collaboration workbench screenshot. */
@Tag("screenshot")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkbenchCollaborationScreenshotIT {

  private static final int VIEWPORT_WIDTH = 1440;
  private static final int VIEWPORT_HEIGHT = 900;
  private static final int PAGE_LOAD_TIMEOUT_MS = 30_000;
  private static final String SESSION_ID = "docs-collaboration";
  private static final String ACTOR_INIT_SCRIPT =
      "window.sessionStorage.setItem('audio-analyzer.workflow.actor',"
          + " JSON.stringify({actorId:'actor-docs',userId:'user-docs',"
          + "displayName:'Documentation User'}));";

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
    } catch (IllegalStateException exception) {
      throw new TestAbortedException(exception.getMessage(), exception);
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

  /** Creates a deterministic live session, applies two operations and captures the resulting UI. */
  @Test
  void liveSessionShowsServerOwnedCollaborationState() throws IOException {
    String baseUrl = WorkbenchContainerFactory.baseUrl(container);

    try (Page page = browser.newPage()) {
      page.setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
      page.setDefaultTimeout(PAGE_LOAD_TIMEOUT_MS);
      page.addInitScript(ACTOR_INIT_SCRIPT);
      page.navigate(baseUrl + "/");
      page.waitForLoadState();
      disableAnimations(page);

      page.locator("[data-testid='session-id-input']").fill(SESSION_ID);
      page.locator("[data-testid='workflow-name-input']").fill("Documented collaboration workflow");
      page.locator("[data-testid='create-session-button']").click();

      Locator activeSession = page.locator("[data-testid='active-session-id']");
      activeSession.waitFor(new Locator.WaitForOptions().setTimeout(PAGE_LOAD_TIMEOUT_MS));
      assertEquals(SESSION_ID, activeSession.innerText());
      assertEquals(
          "SHARED_SESSION_PERSONAL_UNDO",
          page.locator("[data-testid='active-session-mode']").innerText());
      assertTrue(page.locator("[data-testid='participant-actor-docs']").isVisible());

      Locator connectionState = page.locator("[data-testid='connection-state']");
      page.waitForCondition(() -> "live".equals(connectionState.innerText()));

      page.locator("[data-testid='palette-node-synthetic-signal-generator']").click();
      page.waitForCondition(
          () -> "1".equals(page.locator("[data-testid='semantic-revision']").innerText()));
      page.locator("[data-testid='palette-node-gain']").click();
      page.waitForCondition(
          () -> "2".equals(page.locator("[data-testid='semantic-revision']").innerText()));

      assertTrue(
          page.locator("[data-testid^='node-node.synthetic-signal-generator.']").isVisible(),
          "Accepted generator node must be rendered from the server projection");
      assertTrue(
          page.locator("[data-testid^='node-node.gain.']").isVisible(),
          "Accepted gain node must be rendered from the server projection");
      assertEquals("accepted", page.locator("[data-testid='command-state']").innerText());

      byte[] pngBytes = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
      ScreenshotPipeline.processScreenshot("collaboration-session.png", pngBytes);
    }
  }

  private static void disableAnimations(Page page) {
    page.addStyleTag(
        new Page.AddStyleTagOptions()
            .setContent(
                "*, *::before, *::after {"
                    + " animation: none !important;"
                    + " transition: none !important;"
                    + " caret-color: transparent !important;"
                    + " }"));
  }

  private static boolean isDockerAvailable() {
    try {
      return DockerClientFactory.instance().isDockerAvailable();
    } catch (Exception exception) {
      return false;
    }
  }

  private static boolean isJarAvailable() {
    String jarPath = System.getProperty("workbench.jar.path");
    return jarPath != null && !jarPath.isBlank() && Files.exists(Path.of(jarPath));
  }
}
