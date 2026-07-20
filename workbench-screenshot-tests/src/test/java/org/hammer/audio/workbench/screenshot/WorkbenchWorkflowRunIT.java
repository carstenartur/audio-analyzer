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
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opentest4j.TestAbortedException;
import org.testcontainers.DockerClientFactory;

/** Browser evidence that remote edits cannot mutate an already captured immutable workflow run. */
@Tag("collaboration-e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkbenchWorkflowRunIT {

  private static final String SESSION_ID = "e2e-immutable-run";
  private static final String MODE = "SHARED_SESSION_PERSONAL_UNDO";
  private static final Actor ALICE = new Actor("actor-run-alice", "user-run-alice", "Alice Run E2E");
  private static final Actor BOB = new Actor("actor-run-bob", "user-run-bob", "Bob Run E2E");

  private WorkbenchBrowserHarness harness;

  @BeforeAll
  void setUp() throws IOException {
    assumeTrue(isDockerAvailable(), "Docker is not available — skipping workflow run E2E test");
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
  void remoteEditAfterServerCaptureCannotMutateDisplayedRun() throws Throwable {
    String scenario = "immutable-run-survives-remote-edit";
    WorkbenchBrowserHarness.ActorBrowser alice =
        harness.openActor(ALICE.actorId(), ALICE.userId(), ALICE.displayName());
    WorkbenchBrowserHarness.ActorBrowser bob =
        harness.openActor(BOB.actorId(), BOB.userId(), BOB.displayName());
    try {
      open(alice.page());
      open(bob.page());
      createSession(alice.page());
      joinSession(bob.page());
      buildExecutableWorkflow(alice.page());
      waitForRevision(alice.page(), 11);
      waitForRevision(bob.page(), 11);

      installRunResponseGate(alice.page());
      Locator runCurrent = alice.page().locator("[data-testid='run-current-revision']");
      runCurrent.waitFor();
      assertTrue(runCurrent.isEnabled());
      runCurrent.click();
      awaitServerCapture(alice.page());

      Locator paletteGain = alice.page().locator("[data-testid='palette-node-gain']");
      paletteGain.waitFor();
      assertTrue(paletteGain.isEnabled(), "Editing must remain available while a run starts");

      updateGain(bob.page(), 11, "0.5", "0.25", "operation.run.remote-edit.1");
      waitForRevision(alice.page(), 12);
      waitForRevision(bob.page(), 12);
      releaseRunResponse(alice.page());

      Locator record = alice.page().locator("[data-testid='run-record']");
      record.waitFor();
      alice.page().waitForCondition(() -> record.innerText().contains("revision 11"));
      assertEquals("COMPUTATION", alice.page().locator("[data-testid='run-mode']").innerText());
      String fingerprint = alice.page().locator("[data-testid='run-fingerprint']").innerText();
      assertEquals(64, fingerprint.length());

      Locator result = alice.page().locator("[data-testid='run-result']");
      result.waitFor();
      alice.page().waitForCondition(() -> result.innerText().contains("Result: COMPLETED"));
      assertTrue(result.innerText().contains("output.digest.sha256"));

      updateGain(bob.page(), 12, "0.25", "0.125", "operation.run.remote-edit.2");
      waitForRevision(alice.page(), 13);
      waitForRevision(bob.page(), 13);
      assertEquals(fingerprint, alice.page().locator("[data-testid='run-fingerprint']").innerText());
      assertTrue(record.innerText().contains("revision 11"));
      assertFalse(record.innerText().contains("revision 13"));
    } catch (Throwable failure) {
      harness.captureFailure(scenario, failure);
      throw failure;
    } finally {
      bob.close();
      alice.close();
    }
  }

  private void open(Page page) {
    page.navigate(harness.baseUrl() + "/");
    page.waitForLoadState();
  }

  private static void createSession(Page page) {
    page.locator("[data-testid='session-id-input']").fill(SESSION_ID);
    page.locator("[data-testid='session-mode-select']").selectOption(MODE);
    page.locator("[data-testid='workflow-name-input']").fill("Immutable run E2E");
    page.locator("[data-testid='create-session-button']").click();
    waitForActiveSession(page);
    waitForLive(page);
  }

  private static void joinSession(Page page) {
    page.locator("[data-testid='session-id-input']").fill(SESSION_ID);
    page.locator("[data-testid='join-session-button']").click();
    waitForActiveSession(page);
    waitForLive(page);
  }

  private static void buildExecutableWorkflow(Page page) {
    submitOperation(
        page,
        ALICE,
        0,
        Map.of(
            "type", "CreateNode",
            "operationId", "operation.run.create-generator",
            "catalogType", "synthetic-signal-generator",
            "nodeId", "node.run.generator"));
    submitOperation(
        page,
        ALICE,
        1,
        Map.of(
            "type", "CreateNode",
            "operationId", "operation.run.create-gain",
            "catalogType", "gain",
            "nodeId", "node.run.gain"));
    updateProperty(page, ALICE, 2, "node.run.generator", "signal.waveform", null, "sine", 1);
    updateProperty(page, ALICE, 3, "node.run.generator", "signal.frequency-hz", null, "1000", 2);
    updateProperty(page, ALICE, 4, "node.run.generator", "signal.phase-radians", null, "0", 3);
    updateProperty(page, ALICE, 5, "node.run.generator", "signal.amplitude", null, "0.5", 4);
    updateProperty(page, ALICE, 6, "node.run.generator", "signal.sample-rate-hz", null, "8000", 5);
    updateProperty(page, ALICE, 7, "node.run.generator", "signal.channels", null, "1", 6);
    updateProperty(page, ALICE, 8, "node.run.generator", "signal.frame-count", null, "4096", 7);
    updateProperty(page, ALICE, 9, "node.run.gain", "gain.factor", null, "0.5", 8);
    submitOperation(
        page,
        ALICE,
        10,
        Map.of(
            "type",
            "ConnectPorts",
            "operationId",
            "operation.run.connect",
            "edge",
            Map.of(
                "id",
                "edge.run.generator-gain",
                "sourceNodeId",
                "node.run.generator",
                "sourcePortId",
                "signal-out",
                "targetNodeId",
                "node.run.gain",
                "targetPortId",
                "audio-in")));
  }

  private static void updateGain(
      Page page, int revision, String previous, String value, String operationId) {
    Map<String, Object> operation = new LinkedHashMap<>();
    operation.put("type", "UpdateProperty");
    operation.put("operationId", operationId);
    operation.put("target", "NODE");
    operation.put("targetId", "node.run.gain");
    operation.put("propertyKey", "gain.factor");
    operation.put("previousValue", previous);
    operation.put("newValue", value);
    submitOperation(page, BOB, revision, operation);
  }

  private static void updateProperty(
      Page page,
      Actor actor,
      int revision,
      String nodeId,
      String key,
      String previous,
      String value,
      int sequence) {
    Map<String, Object> operation = new LinkedHashMap<>();
    operation.put("type", "UpdateProperty");
    operation.put("operationId", "operation.run.property." + sequence + "." + actor.actorId());
    operation.put("target", "NODE");
    operation.put("targetId", nodeId);
    operation.put("propertyKey", key);
    if (previous != null) {
      operation.put("previousValue", previous);
    }
    operation.put("newValue", value);
    submitOperation(page, actor, revision, operation);
  }

  @SuppressWarnings("unchecked")
  private static void submitOperation(
      Page page, Actor actor, int expectedRevision, Map<String, ?> operation) {
    Map<String, Object> response =
        (Map<String, Object>)
            page.evaluate(
                """
                async input => {
                  const response = await fetch(
                    `/workflow/sessions/${encodeURIComponent(input.sessionId)}/operations`,
                    {
                      method: 'POST',
                      headers: {'Content-Type': 'application/json', Accept: 'application/json'},
                      body: JSON.stringify({
                        mode: input.mode,
                        actor: input.actor,
                        expectedRevision: input.expectedRevision,
                        operation: input.operation
                      })
                    }
                  );
                  const body = await response.json();
                  return {status: response.status, body};
                }
                """,
                Map.of(
                    "sessionId", SESSION_ID,
                    "mode", MODE,
                    "actor", actor.toMap(),
                    "expectedRevision", expectedRevision,
                    "operation", operation));
    assertEquals(200, ((Number) response.get("status")).intValue(), response.toString());
  }

  private static void installRunResponseGate(Page page) {
    page.evaluate(
        """
        () => {
          const originalFetch = window.fetch.bind(window);
          let release;
          const gate = new Promise(resolve => { release = resolve; });
          window.__immutableRunGate = {captured: false, release};
          window.fetch = async (...args) => {
            const input = args[0];
            const init = args[1] ?? {};
            const url = typeof input === 'string' ? input : input.url;
            const method = (init.method ?? (typeof input === 'string' ? 'GET' : input.method)).toUpperCase();
            const response = await originalFetch(...args);
            const pathname = new URL(url, window.location.href).pathname;
            if (method === 'POST' && pathname === '/workflow/runs') {
              window.__immutableRunGate.captured = true;
              await gate;
            }
            return response;
          };
        }
        """);
  }

  private static void awaitServerCapture(Page page) {
    page.waitForCondition(
        () ->
            Boolean.TRUE.equals(
                page.evaluate("() => window.__immutableRunGate?.captured === true")));
  }

  private static void releaseRunResponse(Page page) {
    page.evaluate("() => window.__immutableRunGate.release()");
  }

  private static void waitForActiveSession(Page page) {
    Locator activeSession = page.locator("[data-testid='active-session-id']");
    activeSession.waitFor();
    page.waitForCondition(() -> SESSION_ID.equals(activeSession.innerText()));
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

  private static boolean isDockerAvailable() {
    try {
      return DockerClientFactory.instance().isDockerAvailable();
    } catch (Exception exception) {
      return false;
    }
  }

  private static boolean isJarAvailable() {
    String jarPath = System.getProperty("workbench.jar");
    return jarPath != null && Files.isRegularFile(Path.of(jarPath));
  }

  private record Actor(String actorId, String userId, String displayName) {
    Map<String, String> toMap() {
      return Map.of("actorId", actorId, "userId", userId, "displayName", displayName);
    }
  }
}
