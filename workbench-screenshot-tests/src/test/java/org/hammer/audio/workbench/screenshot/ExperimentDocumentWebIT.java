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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opentest4j.TestAbortedException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/** Packaged-browser verification of safe portable experiment-document preview and apply. */
@Tag("screenshot")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExperimentDocumentWebIT {

  private static final int PAGE_LOAD_TIMEOUT_MS = 30_000;
  private static final String EXPECTED_HASH =
      "6e71c3fea28e56f89f43257749b0bac7f8cf288b424749bca4a83c1f40a61cfd";

  private GenericContainer<?> container;
  private Playwright playwright;
  private Browser browser;

  @BeforeAll
  void setUp() throws IOException {
    assumeTrue(
        isDockerAvailable(), "Docker is not available — skipping experiment document browser test");
    assumeTrue(isJarAvailable(), "audio-app JAR not found — build the project first");
    assumeTrue(Files.isRegularFile(fixture()), "minimal.audioexp fixture is unavailable");
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

  /** Preview is non-mutating; explicit confirmation applies but never executes the setup. */
  @Test
  void minimalDocumentPreviewAndConfirmedApplyUseServerAuthoritativeWorkflow() {
    String baseUrl = WorkbenchContainerFactory.baseUrl(container);
    try (Page page = browser.newPage()) {
      page.setViewportSize(1440, 900);
      page.setDefaultTimeout(PAGE_LOAD_TIMEOUT_MS);
      page.navigate(baseUrl + "/");
      page.waitForLoadState();

      Locator graphNodes = page.locator(".react-flow__node");
      graphNodes.first().waitFor(new Locator.WaitForOptions().setTimeout(PAGE_LOAD_TIMEOUT_MS));
      int initialNodeCount = graphNodes.count();
      assertTrue(initialNodeCount > 0, "seed workflow should contain graph nodes");

      Locator panel = page.locator("[data-testid='experiment-document-panel']");
      panel.waitFor(new Locator.WaitForOptions().setTimeout(PAGE_LOAD_TIMEOUT_MS));
      panel.locator("summary").click();
      page.locator("[data-testid='experiment-document-file']").setInputFiles(fixture());
      page.locator("[data-testid='experiment-document-preview']").click();

      Locator preview = page.locator("[data-testid='experiment-document-preview-result']");
      preview.waitFor(new Locator.WaitForOptions().setTimeout(PAGE_LOAD_TIMEOUT_MS));
      String text = preview.innerText();
      assertTrue(text.contains("Portable example"));
      assertTrue(text.contains("Portable example (0 nodes, 0 edges)"));
      assertTrue(text.contains(EXPECTED_HASH));
      assertTrue(text.contains("available"));
      assertTrue(text.contains("compatible"));
      assertEquals(initialNodeCount, graphNodes.count(), "preview must not mutate the graph");
      assertEquals(0, page.locator("[data-testid='experiment-document-execute']").count());
      assertTrue(page.locator("[data-testid='experiment-document-normalize']").isEnabled());

      Locator apply = page.locator("[data-testid='experiment-document-apply']");
      assertTrue(apply.isEnabled());
      AtomicBoolean confirmedNoExecution = new AtomicBoolean(false);
      page.onceDialog(
          dialog -> {
            confirmedNoExecution.set(dialog.message().contains("does not execute"));
            dialog.accept();
          });
      apply.click();
      page.waitForCondition(() -> page.locator(".react-flow__node").count() == 0);

      assertTrue(confirmedNoExecution.get());
      assertEquals(0, page.locator(".react-flow__node").count());
      assertEquals(0, page.locator("[data-testid='experiment-document-execute']").count());
    }
  }

  private static Path fixture() {
    return Path.of(
            System.getProperty("maven.multiModuleProjectDirectory", ".."),
            "docs",
            "examples",
            "minimal.audioexp")
        .toAbsolutePath()
        .normalize();
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
