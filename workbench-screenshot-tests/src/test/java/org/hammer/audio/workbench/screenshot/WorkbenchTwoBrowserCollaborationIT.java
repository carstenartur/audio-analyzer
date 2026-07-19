package org.hammer.audio.workbench.screenshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opentest4j.TestAbortedException;
import org.testcontainers.DockerClientFactory;

/** Cross-process evidence for two isolated browsers using the packaged collaboration platform. */
@Tag("collaboration-e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkbenchTwoBrowserCollaborationIT {

  private static final String PERSONAL_SESSION_ID = "e2e-personal-history";
  private static final String SHARED_SESSION_ID = "e2e-shared-history";
  private static final String GENERATOR_SELECTOR =
      "[data-testid^='node-node.synthetic-signal-generator.']";

  private WorkbenchBrowserHarness harness;

  @BeforeAll
  void setUp() throws IOException {
    assumeTrue(isDockerAvailable(), "Docker is not available — skipping collaboration E2E tests");
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
  void personalHistoryConvergesAcrossPresenceConflictReconnectReloadUndoAndRedo() throws Throwable {
    String scenario = "personal-history-convergence";
    try (WorkbenchBrowserHarness.ActorBrowser alice =
            harness.openActor("actor-e2e-alice", "user-e2e-alice", "Alice E2E");
        WorkbenchBrowserHarness.ActorBrowser bob =
            harness.openActor("actor-e2e-bob", "user-e2e-bob", "Bob E2E")) {
      open(alice.page());
      open(bob.page());
      createSession(alice.page(), PERSONAL_SESSION_ID, "SHARED_SESSION_PERSONAL_UNDO");
      joinSession(bob.page(), PERSONAL_SESSION_ID);
      assertParticipants(alice.page(), "actor-e2e-alice", "actor-e2e-bob");
      assertParticipants(bob.page(), "actor-e2e-alice", "actor-e2e-bob");

      alice.page().locator("[data-testid='palette-node-synthetic-signal-generator']").click();
      waitForRevision(alice.page(), 1);
      waitForRevision(bob.page(), 1);
      Locator aliceNode = alice.page().locator(GENERATOR_SELECTOR).first();
      Locator bobNode = bob.page().locator(GENERATOR_SELECTOR).first();
      aliceNode.waitFor();
      bobNode.waitFor();
      assertEquals(1, bob.page().locator(GENERATOR_SELECTOR).count());

      String nodeTestId = aliceNode.getAttribute("data-testid");
      String nodeId = nodeTestId.substring("node-".length());
      aliceNode.click();
      Locator remotePresence = bob.page().locator("[data-testid='presence-actor-e2e-alice']");
      remotePresence.waitFor();
      bob.page().waitForCondition(() -> remotePresence.innerText().contains("selection=" + nodeId));
      String projectionJson = sessionProjectionJson(bob.page(), PERSONAL_SESSION_ID);
      assertFalse(projectionJson.contains("selection"));
      assertFalse(projectionJson.contains("actor-e2e-alice"));

      Map<?, ?> staleResult = submitStaleOperation(bob.page(), PERSONAL_SESSION_ID);
      assertEquals(409, ((Number) staleResult.get("status")).intValue());
      Map<?, ?> staleProblem = (Map<?, ?>) staleResult.get("problem");
      assertEquals("WORKFLOW_SESSION_REVISION_CONFLICT", staleProblem.get("code"));
      assertEquals(0, alice.page().locator("[data-testid='node-node.e2e.stale']").count());
      assertEquals(0, bob.page().locator("[data-testid='node-node.e2e.stale']").count());
      assertRevision(alice.page(), 1);
      assertRevision(bob.page(), 1);

      clickWhenEnabled(alice.page(), "[data-testid='personal-undo-preview-button']");
      confirmHistoryDialog(alice.page(), false);
      waitForRevision(alice.page(), 2);
      waitForRevision(bob.page(), 2);
      bob.page().waitForCondition(() -> bob.page().locator(GENERATOR_SELECTOR).count() == 0);

      clickWhenEnabled(alice.page(), "[data-testid='redo-preview-button']");
      confirmHistoryDialog(alice.page(), false);
      waitForRevision(alice.page(), 3);
      waitForRevision(bob.page(), 3);
      bob.page().locator(GENERATOR_SELECTOR).first().waitFor();
      assertEquals(1, bob.page().locator(GENERATOR_SELECTOR).count());

      bob.context().setOffline(true);
      bob.page()
          .waitForCondition(
              () ->
                  !"live"
                      .equals(bob.page().locator("[data-testid='connection-state']").innerText()));
      bob.context().setOffline(false);
      waitForLive(bob.page());
      assertRevision(bob.page(), 3);
      assertEquals(1, bob.page().locator(GENERATOR_SELECTOR).count());

      bob.page().reload();
      waitForActiveSession(bob.page(), PERSONAL_SESSION_ID);
      waitForLive(bob.page());
      waitForRevision(bob.page(), 3);
      assertEquals(1, bob.page().locator(GENERATOR_SELECTOR).count());
      assertEquals(
          "ready", bob.page().locator("[data-testid='history-controller-state']").innerText());
    } catch (Throwable failure) {
      harness.captureFailure(scenario, failure);
      throw failure;
    }
  }

  @Test
  void sharedUndoRequiresExplicitTargetAndAcknowledgementBeforeBothClientsConverge() throws Throwable {
    String scenario = "shared-undo-confirmation";
    try (WorkbenchBrowserHarness.ActorBrowser owner =
            harness.openActor("actor-e2e-owner", "user-e2e-owner", "Owner E2E");
        WorkbenchBrowserHarness.ActorBrowser reviewer =
            harness.openActor("actor-e2e-reviewer", "user-e2e-reviewer", "Reviewer E2E")) {
      open(owner.page());
      open(reviewer.page());
      createSession(owner.page(), SHARED_SESSION_ID, "SHARED_SESSION_SHARED_UNDO");
      joinSession(reviewer.page(), SHARED_SESSION_ID);

      owner.page().locator("[data-testid='palette-node-synthetic-signal-generator']").click();
      waitForRevision(owner.page(), 1);
      waitForRevision(reviewer.page(), 1);
      reviewer.page().locator(GENERATOR_SELECTOR).first().waitFor();

      Locator previewButton =
          reviewer.page().locator("[data-testid='shared-undo-preview-button']");
      previewButton.waitFor();
      assertFalse(previewButton.isEnabled(), "Shared undo must not auto-select a target");
      Locator target = reviewer.page().locator("input[name='shared-history-target']").first();
      target.waitFor();
      target.check();
      assertTrue(previewButton.isEnabled());
      previewButton.click();

      Locator dialog = reviewer.page().locator("[data-testid='history-preview-dialog']");
      dialog.waitFor();
      Locator confirm = reviewer.page().locator("[data-testid='history-confirm-button']");
      assertFalse(confirm.isEnabled(), "Shared undo requires explicit acknowledgement");
      reviewer.page().locator("[data-testid='shared-undo-confirmation']").check();
      assertTrue(confirm.isEnabled());
      confirm.click();

      waitForRevision(owner.page(), 2);
      waitForRevision(reviewer.page(), 2);
      owner.page().waitForCondition(() -> owner.page().locator(GENERATOR_SELECTOR).count() == 0);
      reviewer.page().waitForCondition(() -> reviewer.page().locator(GENERATOR_SELECTOR).count() == 0);

      clickWhenEnabled(reviewer.page(), "[data-testid='redo-preview-button']");
      confirmHistoryDialog(reviewer.page(), false);
      waitForRevision(owner.page(), 3);
      waitForRevision(reviewer.page(), 3);
      owner.page().locator(GENERATOR_SELECTOR).first().waitFor();
      reviewer.page().locator(GENERATOR_SELECTOR).first().waitFor();
      assertEquals(1, owner.page().locator(GENERATOR_SELECTOR).count());
      assertEquals(1, reviewer.page().locator(GENERATOR_SELECTOR).count());
    } catch (Throwable failure) {
      harness.captureFailure(scenario, failure);
      throw failure;
    }
  }

  private void open(Page page) {
    page.navigate(harness.baseUrl() + "/");
    page.waitForLoadState();
  }

  private static void createSession(Page page, String sessionId, String mode) {
    page.locator("[data-testid='session-id-input']").fill(sessionId);
    page.locator("[data-testid='session-mode-select']").selectOption(mode);
    page.locator("[data-testid='workflow-name-input']").fill("E2E " + sessionId);
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

  private static void assertRevision(Page page, int revision) {
    assertEquals(
        Integer.toString(revision), page.locator("[data-testid='semantic-revision']").innerText());
  }

  private static void assertParticipants(Page page, String firstActorId, String secondActorId) {
    page.locator("[data-testid='participant-" + firstActorId + "']").waitFor();
    page.locator("[data-testid='participant-" + secondActorId + "']").waitFor();
  }

  private static void clickWhenEnabled(Page page, String selector) {
    Locator button = page.locator(selector);
    button.waitFor();
    page.waitForCondition(button::isEnabled);
    button.click();
  }

  private static void confirmHistoryDialog(Page page, boolean acknowledgeSharedUndo) {
    page.locator("[data-testid='history-preview-dialog']").waitFor();
    if (acknowledgeSharedUndo) {
      page.locator("[data-testid='shared-undo-confirmation']").check();
    }
    Locator confirm = page.locator("[data-testid='history-confirm-button']");
    page.waitForCondition(confirm::isEnabled);
    confirm.click();
  }

  private static String sessionProjectionJson(Page page, String sessionId) {
    return (String)
        page.evaluate(
            """
            async sessionId => JSON.stringify(
              await (await fetch(`/workflow/sessions/${encodeURIComponent(sessionId)}/projection`)).json()
            )
            """,
            sessionId);
  }

  @SuppressWarnings("unchecked")
  private static Map<?, ?> submitStaleOperation(Page page, String sessionId) {
    return (Map<?, ?>)
        page.evaluate(
            """
            async input => {
              const response = await fetch(
                `/workflow/sessions/${encodeURIComponent(input.sessionId)}/operations`,
                {
                  method: 'POST',
                  headers: {'Content-Type': 'application/json', Accept: 'application/json'},
                  body: JSON.stringify({
                    mode: 'SHARED_SESSION_PERSONAL_UNDO',
                    actor: input.actor,
                    expectedRevision: 0,
                    operation: {
                      type: 'CreateNode',
                      operationId: 'operation.e2e.stale',
                      catalogType: 'gain',
                      nodeId: 'node.e2e.stale'
                    }
                  })
                }
              );
              return {status: response.status, problem: await response.json()};
            }
            """,
            Map.of(
                "sessionId",
                sessionId,
                "actor",
                Map.of(
                    "actorId",
                    "actor-e2e-bob",
                    "userId",
                    "user-e2e-bob",
                    "displayName",
                    "Bob E2E")));
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
