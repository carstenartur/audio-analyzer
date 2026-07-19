package org.hammer.audio.workbench.screenshot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
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

/**
 * Generates documentation screenshots for durable personal and shared semantic history controls.
 */
@Tag("screenshot")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkbenchHistoryScreenshotIT {

  private static final String PERSONAL_SESSION_ID = "docs-personal-history";
  private static final String SHARED_SESSION_ID = "docs-shared-history";

  private WorkbenchBrowserHarness harness;

  @BeforeAll
  void setUp() throws IOException {
    assumeTrue(
        isDockerAvailable(), "Docker is not available — skipping screenshot integration tests");
    assumeTrue(isJarAvailable(), "audio-app JAR not found — build the project first");
    try {
      harness = WorkbenchBrowserHarness.start();
    } catch (IllegalStateException exception) {
      throw new TestAbortedException(exception.getMessage(), exception);
    }
  }

  @AfterAll
  void tearDown() {
    if (harness != null) {
      harness.close();
    }
  }

  @Test
  void personalUndoPreviewShowsServerOwnedImpactBeforeConfirmation() throws Throwable {
    String scenario = "personal-undo-preview-screenshot";
    WorkbenchBrowserHarness.ActorBrowser researcher =
        harness.openActor("actor-docs-researcher", "user-docs-researcher", "Audio Researcher");
    try {
      Page page = researcher.page();
      open(page);
      createSession(page, PERSONAL_SESSION_ID, "SHARED_SESSION_PERSONAL_UNDO");

      page.locator("[data-testid='palette-node-synthetic-signal-generator']").click();
      waitForRevision(page, 1);
      page.locator("[data-testid='palette-node-gain']").click();
      waitForRevision(page, 2);
      waitForCapabilityRevision(page, 2);

      clickWhenEnabled(page, "[data-testid='personal-undo-preview-button']");
      Locator dialog = page.locator("[data-testid='history-preview-dialog']");
      dialog.waitFor();
      assertTrue(dialog.innerText().contains("Confirm undo"));
      assertTrue(dialog.innerText().contains("Audio Researcher"));
      assertTrue(page.locator("[data-testid='history-confirm-button']").isEnabled());
      normalizeHistoryTimestamps(page);

      disableAnimations(page);
      byte[] pngBytes = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
      ScreenshotPipeline.processScreenshot("collaboration-personal-undo-preview.png", pngBytes);
    } catch (Throwable failure) {
      harness.captureFailure(scenario, failure);
      throw failure;
    } finally {
      researcher.close();
    }
  }

  @Test
  void sharedUndoPreviewShowsExplicitTargetAndAcknowledgement() throws Throwable {
    String scenario = "shared-undo-preview-screenshot";
    WorkbenchBrowserHarness.ActorBrowser owner =
        harness.openActor("actor-docs-owner", "user-docs-owner", "Workflow Owner");
    WorkbenchBrowserHarness.ActorBrowser reviewer =
        harness.openActor("actor-docs-reviewer", "user-docs-reviewer", "Reviewing Engineer");
    try {
      open(owner.page());
      open(reviewer.page());
      createSession(owner.page(), SHARED_SESSION_ID, "SHARED_SESSION_SHARED_UNDO");
      joinSession(reviewer.page(), SHARED_SESSION_ID);

      owner.page().locator("[data-testid='palette-node-synthetic-signal-generator']").click();
      waitForRevision(owner.page(), 1);
      waitForRevision(reviewer.page(), 1);
      waitForCapabilityRevision(reviewer.page(), 1);

      Locator previewButton = reviewer.page().locator("[data-testid='shared-undo-preview-button']");
      previewButton.waitFor();
      assertFalse(previewButton.isEnabled());
      Locator target = reviewer.page().locator("input[name='shared-history-target']").first();
      target.waitFor();
      target.check();
      reviewer.page().waitForCondition(previewButton::isEnabled);
      previewButton.click();

      Locator dialog = reviewer.page().locator("[data-testid='history-preview-dialog']");
      dialog.waitFor();
      Locator acknowledgement = reviewer.page().locator("[data-testid='shared-undo-confirmation']");
      acknowledgement.waitFor();
      assertFalse(acknowledgement.isChecked());
      assertFalse(reviewer.page().locator("[data-testid='history-confirm-button']").isEnabled());
      assertTrue(dialog.innerText().contains("Workflow Owner"));
      normalizeHistoryTimestamps(reviewer.page());

      disableAnimations(reviewer.page());
      byte[] pngBytes = reviewer.page().screenshot(new Page.ScreenshotOptions().setFullPage(false));
      ScreenshotPipeline.processScreenshot("collaboration-shared-undo-preview.png", pngBytes);
    } catch (Throwable failure) {
      harness.captureFailure(scenario, failure);
      throw failure;
    } finally {
      reviewer.close();
      owner.close();
    }
  }

  private void open(Page page) {
    page.navigate(harness.baseUrl() + "/");
    page.waitForLoadState();
    disableAnimations(page);
  }

  private static void createSession(Page page, String sessionId, String mode) {
    page.locator("[data-testid='session-id-input']").fill(sessionId);
    page.locator("[data-testid='session-mode-select']").selectOption(mode);
    page.locator("[data-testid='workflow-name-input']").fill("Documented audio workflow");
    page.locator("[data-testid='create-session-button']").click();
    waitForActiveSession(page, sessionId);
    waitForLive(page);
  }

  private static void joinSession(Page page, String sessionId) {
    page.locator("[data-testid='session-id-input']").fill(sessionId);
    page.locator("[data-testid='join-session-button']").click();
    waitForActiveSession(page, sessionId);
    waitForLive(page);
  }

  private static void waitForActiveSession(Page page, String sessionId) {
    Locator activeSession = page.locator("[data-testid='active-session-id']");
    activeSession.waitFor();
    page.waitForCondition(() -> sessionId.equals(activeSession.innerText()));
  }

  private static void waitForLive(Page page) {
    Locator connection = page.locator("[data-testid='connection-state']");
    connection.waitFor();
    page.waitForCondition(() -> "live".equals(connection.innerText()));
  }

  private static void waitForRevision(Page page, int revision) {
    Locator revisionValue = page.locator("[data-testid='semantic-revision']");
    revisionValue.waitFor();
    String expected = Integer.toString(revision);
    page.waitForCondition(() -> expected.equals(revisionValue.innerText()));
  }

  private static void waitForCapabilityRevision(Page page, int revision) {
    Locator capability = page.locator("[data-testid='history-capability-revision']");
    capability.waitFor();
    String expected = "Capability revision " + revision;
    page.waitForCondition(() -> expected.equals(capability.innerText()));
  }

  private static void clickWhenEnabled(Page page, String selector) {
    Locator button = page.locator(selector);
    button.waitFor();
    page.waitForCondition(button::isEnabled);
    button.click();
  }

  private static void normalizeHistoryTimestamps(Page page) {
    page.evaluate(
        "document.querySelectorAll('time.history-timestamp').forEach(element => "
            + "{ element.textContent = '19 Jul 2026, 12:00 UTC'; });");
    assertTrue(page.locator("time.history-timestamp").count() > 0);
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
