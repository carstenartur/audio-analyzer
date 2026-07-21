package org.hammer.audio.workbench.screenshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** Packaged-browser evidence for exact stored-branch semantic conflict resolution. */
@Tag("collaboration-e2e")
class WorkbenchWorkflowMergeIT {

  private static final String TARGET_BRANCH = "main";
  private static final String REMOTE_BRANCH = "feature";
  private static final String TARGET_NODE_ID = "seed.gain";
  private static final String PROPERTY_KEY = "merge.value";

  @TempDir Path dataDirectory;

  @BeforeAll
  static void prerequisites() {
    assumeTrue(isDockerAvailable(), "Docker is not available — skipping workflow merge E2E");
    assumeTrue(pathPropertyExists("workbench.jar.path"), "audio-app JAR is not available");
    assumeTrue(
        pathPropertyExists("workbench.test.classes.dir"),
        "compiled browser-test classes are not available");
  }

  @Test
  void previewsResolvesCommitsAndLoadsExactSemanticMerge() throws Exception {
    try (WorkbenchBrowserHarness harness =
            WorkbenchBrowserHarness.start(
                WorkbenchContainerFactory.createDurableRestart(dataDirectory, false));
        WorkbenchBrowserHarness.ActorBrowser actor =
            harness.openActor("actor-merge", "user-merge", "Merge E2E")) {
      Page page = actor.page();
      page.navigate(harness.baseUrl() + "/");
      page.waitForLoadState();

      String baseCommit = checkpoint(page, TARGET_BRANCH, "Merge base", "2026-07-21T10:00:00Z");
      createBranch(page, baseCommit);
      updateProperty(page, null, "local", "operation.merge.local");
      String localCommit =
          checkpoint(page, TARGET_BRANCH, "Local merge change", "2026-07-21T10:01:00Z");
      loadCommit(page, baseCommit);
      updateProperty(page, null, "remote", "operation.merge.remote");
      String remoteCommit =
          checkpoint(page, REMOTE_BRANCH, "Remote merge change", "2026-07-21T10:02:00Z");
      page.reload();
      page.waitForLoadState();

      page.locator("[data-testid='workflow-merge-toggle']").click();
      page.locator("[data-testid='merge-target-branch']").fill(TARGET_BRANCH);
      page.locator("[data-testid='merge-remote-branch']").fill(REMOTE_BRANCH);
      page.locator("[data-testid='merge-load-histories']").click();
      waitForMergeStatus(page, "Loaded 2 target and 2 remote checkpoints.");
      assertEquals(baseCommit, selectedValue(page, "merge-base-commit"));
      assertEquals(localCommit, selectedValue(page, "merge-local-commit"));
      assertEquals(remoteCommit, selectedValue(page, "merge-remote-commit"));

      page.locator("[data-testid='merge-preview']").click();
      Locator preview = page.locator("[data-testid='merge-preview-result']");
      preview.waitFor();
      Locator conflict = page.locator("[data-testid^='merge-conflict-']").first();
      conflict.waitFor();
      assertTrue(conflict.innerText().contains("DIVERGENT_VALUE"));
      assertTrue(conflict.innerText().contains(PROPERTY_KEY));
      assertTrue(conflict.innerText().contains("local"));
      assertTrue(conflict.innerText().contains("remote"));

      Locator decision = page.locator("[data-testid^='merge-resolution-']").first();
      decision.selectOption("CUSTOM");
      page.locator("[data-testid^='merge-custom-']").first().fill("resolved");
      page.locator("[data-testid='merge-author']").fill("Merge E2E");
      page.locator("[data-testid='merge-message']").fill("Resolve packaged semantic conflict");
      Locator commitButton = page.locator("[data-testid='merge-commit']");
      page.waitForCondition(commitButton::isEnabled);
      commitButton.click();

      Locator result = page.locator("[data-testid='merge-commit-result']");
      result.waitFor();
      waitForMergeStatus(page, "Created merge checkpoint");
      String mergedCommit = historyCommitIds(page, TARGET_BRANCH).getFirst();
      assertTrue(result.innerText().contains(mergedCommit));
      assertEquals(localCommit, historyCommitIds(page, TARGET_BRANCH).get(1));

      page.waitForNavigation(
          () -> page.locator("[data-testid='merge-load-result']").click());
      page.waitForLoadState();
      page.locator("[data-testid='workbench-title']").waitFor();
      page.waitForCondition(
          () -> "resolved".equals(currentNodeProperties(page, TARGET_NODE_ID).get(PROPERTY_KEY)));
      assertEquals("resolved", currentNodeProperties(page, TARGET_NODE_ID).get(PROPERTY_KEY));
      assertEquals(mergedCommit, historyCommitIds(page, TARGET_BRANCH).getFirst());
    }
  }

  @SuppressWarnings("unchecked")
  private static String checkpoint(Page page, String branch, String message, String timestamp) {
    Map<?, ?> response =
        (Map<?, ?>)
            page.evaluate(
                """
                async input => {
                  const response = await fetch('/workflow/checkpoints', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json', Accept: 'application/json'},
                    body: JSON.stringify({
                      branch: input.branch,
                      author: 'workflow-merge-e2e',
                      message: input.message,
                      timestamp: input.timestamp
                    })
                  });
                  return {status: response.status, body: await response.json()};
                }
                """,
                Map.of("branch", branch, "message", message, "timestamp", timestamp));
    assertEquals(200, number(response, "status"), response.toString());
    return text((Map<?, ?>) response.get("body"), "commitId");
  }

  private static void createBranch(Page page, String baseCommit) {
    long status =
        ((Number)
                page.evaluate(
                    """
                    async input => {
                      const response = await fetch('/workflow/history/branches', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json', Accept: 'application/json'},
                        body: JSON.stringify({
                          sourceBranch: input.sourceBranch,
                          newBranch: input.newBranch,
                          fromCommitId: input.fromCommitId
                        })
                      });
                      return response.status;
                    }
                    """,
                    Map.of(
                        "sourceBranch", TARGET_BRANCH,
                        "newBranch", REMOTE_BRANCH,
                        "fromCommitId", baseCommit)))
            .longValue();
    assertEquals(200, status);
  }

  private static void updateProperty(
      Page page, String previousValue, String newValue, String operationId) {
    long status =
        ((Number)
                page.evaluate(
                    """
                    async input => {
                      const operation = {
                        type: 'UpdateProperty',
                        operationId: input.operationId,
                        target: 'NODE',
                        targetId: input.targetId,
                        propertyKey: input.propertyKey,
                        newValue: input.newValue
                      };
                      if (input.hasPreviousValue) {
                        operation.previousValue = input.previousValue;
                      }
                      const response = await fetch('/workflow/operations', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json', Accept: 'application/json'},
                        body: JSON.stringify(operation)
                      });
                      return response.status;
                    }
                    """,
                    Map.of(
                        "operationId", operationId,
                        "targetId", TARGET_NODE_ID,
                        "propertyKey", PROPERTY_KEY,
                        "hasPreviousValue", previousValue != null,
                        "previousValue", previousValue == null ? "" : previousValue,
                        "newValue", newValue)))
            .longValue();
    assertEquals(200, status);
  }

  private static void loadCommit(Page page, String commitId) {
    long status =
        ((Number)
                page.evaluate(
                    """
                    async commitId => {
                      const response = await fetch('/workflow/load', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json', Accept: 'application/json'},
                        body: JSON.stringify({commitId})
                      });
                      return response.status;
                    }
                    """,
                    commitId))
            .longValue();
    assertEquals(200, status);
  }

  private static String selectedValue(Page page, String testId) {
    return page.locator("[data-testid='" + testId + "']").inputValue();
  }

  private static void waitForMergeStatus(Page page, String expected) {
    page.waitForCondition(
        () -> page.locator("[data-testid='merge-status']").innerText().contains(expected));
  }

  @SuppressWarnings("unchecked")
  private static List<String> historyCommitIds(Page page, String branch) {
    return (List<String>)
        page.evaluate(
            """
            async branch => {
              const response = await fetch(`/workflow/history?branch=${encodeURIComponent(branch)}&limit=10`, {
                headers: {Accept: 'application/json'}
              });
              const history = await response.json();
              return history.map(entry => entry.commitId);
            }
            """,
            branch);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> currentNodeProperties(Page page, String nodeId) {
    return (Map<String, String>)
        page.evaluate(
            """
            async nodeId => {
              const response = await fetch('/workflow/projection', {headers: {Accept: 'application/json'}});
              const projection = await response.json();
              return projection.nodes.find(node => node.id === nodeId).properties;
            }
            """,
            nodeId);
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
