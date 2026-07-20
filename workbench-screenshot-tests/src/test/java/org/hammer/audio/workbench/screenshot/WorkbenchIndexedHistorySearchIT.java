package org.hammer.audio.workbench.screenshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

/** Packaged-browser evidence for exact-commit indexed workflow history search. */
@Tag("collaboration-e2e")
class WorkbenchIndexedHistorySearchIT {

  private static final String HISTORICAL_TERM = "wingbeathistorybaseline";
  private static final String AUTHOR_EMAIL = "indexed-history-e2e@audio-analyzer.invalid";
  private static final String LATER_NODE_ID = "node.e2e.indexed-history.later-gain";
  private static final String LATER_NODE_SELECTOR = "[data-testid='node-" + LATER_NODE_ID + "']";
  private static final String ACTIVE_SESSION_STORAGE_KEY = "audio-analyzer.workflow.active-session";

  @TempDir Path dataDirectory;

  @BeforeAll
  static void prerequisites() {
    assumeTrue(isDockerAvailable(), "Docker is not available — skipping indexed history E2E");
    assumeTrue(pathPropertyExists("workbench.jar.path"), "audio-app JAR is not available");
    assumeTrue(
        pathPropertyExists("workbench.test.classes.dir"),
        "compiled browser-test classes are not available");
  }

  @Test
  void searchesWithStructuredFiltersAndLoadsTheExactAuthoritativeCommit() throws Exception {
    try (WorkbenchBrowserHarness harness =
            WorkbenchBrowserHarness.start(
                WorkbenchContainerFactory.createDurableRestart(dataDirectory, false));
        WorkbenchBrowserHarness.ActorBrowser actor =
            harness.openActor("actor-index-search", "user-index-search", "Index Search E2E")) {
      Page page = actor.page();
      page.navigate(harness.baseUrl() + "/");
      page.waitForLoadState();

      String historicalCommit =
          checkpoint(page, "Indexed history " + HISTORICAL_TERM, "2026-07-19T13:00:00Z");
      applyLaterOperation(page);
      checkpoint(page, "Later unrelated checkpoint", "2026-07-19T13:01:00Z");
      page.reload();
      page.locator(LATER_NODE_SELECTOR).waitFor();

      Locator toggle = page.locator("[data-testid='indexed-history-toggle']");
      toggle.waitFor();
      toggle.click();
      page.locator("[data-testid='indexed-history-query']").fill(HISTORICAL_TERM);
      page.locator("[data-testid='indexed-history-author']").fill("nobody@example.org");
      page.locator("[data-testid='indexed-history-path']").fill("workflow");
      page.locator("[data-testid='indexed-history-from']").fill("2026-07-19T12:59");
      page.locator("[data-testid='indexed-history-to']").fill("2026-07-19T13:00");
      page.locator("[data-testid='indexed-history-search']").click();
      waitForStatus(page, "No indexed checkpoints match these filters.");
      assertEquals(0, page.locator("[data-testid^='indexed-history-load-']").count());

      page.locator("[data-testid='indexed-history-author']").fill(AUTHOR_EMAIL);
      page.locator("[data-testid='indexed-history-search']").click();

      Locator exactLoad =
          page.locator("[data-testid='indexed-history-load-" + historicalCommit + "']");
      exactLoad.waitFor();
      assertEquals(1, exactLoad.count());
      assertTrue(
          page.locator("[data-testid='indexed-history-results']")
              .innerText()
              .contains(historicalCommit.substring(0, 12)));

      page.evaluate(
          "input => sessionStorage.setItem(input.key, input.value)",
          Map.of("key", ACTIVE_SESSION_STORAGE_KEY, "value", "remembered-live-session"));
      exactLoad.click();
      Locator error = page.locator("[data-testid='indexed-history-error']");
      error.waitFor();
      assertTrue(error.innerText().contains("Leave collaboration session remembered-live-session"));
      assertEquals(1, page.locator(LATER_NODE_SELECTOR).count());

      page.evaluate("key => sessionStorage.removeItem(key)", ACTIVE_SESSION_STORAGE_KEY);
      exactLoad.click();
      page.waitForCondition(() -> page.locator(LATER_NODE_SELECTOR).count() == 0);
      page.locator("[data-testid='workbench-title']").waitFor();
      assertFalse(currentProjectionNodeIds(page).contains(LATER_NODE_ID));
    }
  }

  private static void waitForStatus(Page page, String expected) {
    page.waitForCondition(
        () ->
            page.locator("[data-testid='indexed-history-status']").innerText().contains(expected));
  }

  @SuppressWarnings("unchecked")
  private static String checkpoint(Page page, String message, String timestamp) {
    Map<?, ?> response =
        (Map<?, ?>)
            page.evaluate(
                """
                async input => {
                  const response = await fetch('/workflow/checkpoints', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json', Accept: 'application/json'},
                    body: JSON.stringify({
                      branch: 'main',
                      author: 'indexed-history-e2e',
                      message: input.message,
                      timestamp: input.timestamp
                    })
                  });
                  return {status: response.status, body: await response.json()};
                }
                """,
                Map.of("message", message, "timestamp", timestamp));
    assertEquals(200, number(response, "status"));
    return text((Map<?, ?>) response.get("body"), "commitId");
  }

  private static void applyLaterOperation(Page page) {
    Number status =
        (Number)
            page.evaluate(
                """
                async nodeId => {
                  const response = await fetch('/workflow/operations', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json', Accept: 'application/json'},
                    body: JSON.stringify({
                      type: 'CreateNode',
                      operationId: 'operation.e2e.indexed-history.later-gain',
                      catalogType: 'gain',
                      nodeId
                    })
                  });
                  return response.status;
                }
                """,
                LATER_NODE_ID);
    assertEquals(200, status.intValue());
  }

  @SuppressWarnings("unchecked")
  private static List<String> currentProjectionNodeIds(Page page) {
    return (List<String>)
        page.evaluate(
            """
            async () => {
              const response = await fetch('/workflow/projection', {headers: {Accept: 'application/json'}});
              const projection = await response.json();
              return projection.nodes.map(node => node.id);
            }
            """);
  }

  private static long number(Map<?, ?> map, String key) {
    return ((Number) map.get(key)).longValue();
  }

  private static String text(Map<?, ?> map, String key) {
    return String.valueOf(map.get(key));
  }

  private static boolean pathPropertyExists(String property) {
    String value = System.getProperty(property);
    return value != null && Files.exists(Path.of(value));
  }

  private static boolean isDockerAvailable() {
    try {
      return DockerClientFactory.instance().isDockerAvailable();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }
}
