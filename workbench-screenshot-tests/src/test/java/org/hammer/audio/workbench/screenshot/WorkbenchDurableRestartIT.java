package org.hammer.audio.workbench.screenshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

/** Full packaged-process restart evidence for durable collaboration, outbox and JGit state. */
@Tag("collaboration-e2e")
class WorkbenchDurableRestartIT {

  private static final String SESSION_ID = "e2e-durable-restart";
  private static final String OPERATION_ID = "operation.e2e.durable.create";
  private static final String NODE_ID = "node.e2e.durable.generator";
  private static final String LEGACY_NODE_ID = "node.e2e.legacy.after-checkpoint";
  private static final String NODE_SELECTOR = "[data-testid='node-" + NODE_ID + "']";
  private static final Duration PUBLICATION_TIMEOUT = Duration.ofSeconds(30);

  @TempDir Path dataDirectory;

  @BeforeAll
  static void prerequisites() {
    assumeTrue(isDockerAvailable(), "Docker is not available — skipping durable E2E tests");
    assumeTrue(pathPropertyExists("workbench.jar.path"), "audio-app JAR is not available");
    assumeTrue(
        pathPropertyExists("workbench.test.classes.dir"),
        "compiled browser-test classes are not available");
  }

  @Test
  void durableStateCheckpointAndPendingOutboxSurviveCompleteProcessRestart() throws Throwable {
    Path publications = WorkbenchContainerFactory.durablePublicationsFile(dataDirectory);
    String acceptedEventId;
    long acceptedSequence;
    String oldCommitId;
    String newCommitId;

    try (WorkbenchBrowserHarness first =
            WorkbenchBrowserHarness.start(
                WorkbenchContainerFactory.createDurableRestart(dataDirectory, false));
        WorkbenchBrowserHarness.ActorBrowser actor =
            first.openActor("actor-e2e-durable", "user-e2e-durable", "Durable E2E")) {
      Page page = actor.page();
      try {
        open(first, page);
        createSession(page);
        prepareAcceptedEvent(page);

        Map<?, ?> accepted = submitSessionOperation(page, 0);
        assertEquals(200, number(accepted, "status"));
        waitForRevision(page, 1);
        page.locator(NODE_SELECTOR).waitFor();
        Map<?, ?> event = awaitAcceptedEvent(page);
        acceptedEventId = text(event, "eventId");
        acceptedSequence = number(event, "sequence");
        assertEquals(OPERATION_ID, text(event, "operationId"));
        assertEquals(1, number(event, "revision"));

        Map<?, ?> duplicate = submitSessionOperation(page, 0);
        assertEquals(200, number(duplicate, "status"));
        assertRevision(page, 1);
        assertEquals(1, page.locator(NODE_SELECTOR).count());

        oldCommitId = checkpoint(page, "Durable E2E before legacy edit", "2026-07-19T12:00:00Z");
        applyLegacyOperation(page);
        newCommitId = checkpoint(page, "Durable E2E after legacy edit", "2026-07-19T12:01:00Z");
        assertFalse(Files.exists(publications));
      } catch (Throwable failure) {
        captureDurableFailure("process-1", first, publications, failure);
        throw failure;
      }
    }

    assertFalse(Files.exists(publications));
    try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
      dataDirectory.register(
          watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
      try (WorkbenchBrowserHarness second =
              WorkbenchBrowserHarness.start(
                  WorkbenchContainerFactory.createDurableRestart(dataDirectory, true));
          WorkbenchBrowserHarness.ActorBrowser actor =
              second.openActor("actor-e2e-durable", "user-e2e-durable", "Durable E2E")) {
        Page page = actor.page();
        try {
          open(second, page);
          Map<String, Object> recoveredMetadata = inspectSession(page);
          assertEquals(
              1,
              number(recoveredMetadata, "revision"),
              "Recovered server session revision before browser join");
          assertTrue(
              projectionNodeIds(sessionProjection(page)).contains(NODE_ID),
              "Recovered server projection must contain the durable node before browser join");
          joinSession(page);
          waitForRevision(page, 1);
          page.locator(NODE_SELECTOR).waitFor();
          assertEquals(1, page.locator(NODE_SELECTOR).count());

          Map<?, ?> duplicate = submitSessionOperation(page, 0);
          assertEquals(200, number(duplicate, "status"));
          assertRevision(page, 1);
          assertEquals(1, page.locator(NODE_SELECTOR).count());

          List<Map<String, Object>> history = checkpointHistory(page);
          assertTrue(containsCommit(history, oldCommitId));
          assertTrue(containsCommit(history, newCommitId));
          assertFalse(projectionNodeIds(loadCommit(page, oldCommitId)).contains(LEGACY_NODE_ID));
          assertTrue(projectionNodeIds(loadCommit(page, newCommitId)).contains(LEGACY_NODE_ID));

          List<String> published = awaitPublication(watchService, publications, acceptedEventId);
          List<String> matching =
              published.stream().filter(line -> line.startsWith(acceptedEventId + "\t")).toList();
          assertEquals(1, matching.size());
          String[] fields = matching.getFirst().split("\t", 7);
          assertEquals(SESSION_ID, fields[1]);
          assertEquals(Long.toString(acceptedSequence), fields[2]);
          assertEquals("1", fields[3]);
          assertEquals("WORKFLOW_OPERATION_ACCEPTED", fields[4]);
        } catch (Throwable failure) {
          captureDurableFailure("process-2", second, publications, failure);
          throw failure;
        }
      }
    }
  }

  private static void open(WorkbenchBrowserHarness harness, Page page) {
    page.navigate(harness.baseUrl() + "/");
    page.waitForLoadState();
  }

  private static void createSession(Page page) {
    page.locator("[data-testid='session-id-input']").fill(SESSION_ID);
    page.locator("[data-testid='session-mode-select']")
        .selectOption("SHARED_SESSION_PERSONAL_UNDO");
    page.locator("[data-testid='workflow-name-input']").fill("Durable restart workflow");
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

  private static void waitForActiveSession(Page page) {
    Locator session = page.locator("[data-testid='active-session-id']");
    session.waitFor();
    page.waitForCondition(() -> SESSION_ID.equals(session.innerText()));
  }

  private static void waitForLive(Page page) {
    Locator connection = page.locator("[data-testid='connection-state']");
    connection.waitFor();
    page.waitForCondition(() -> "live".equals(connection.innerText()));
  }

  private static void waitForRevision(Page page, int revision) {
    Locator value = page.locator("[data-testid='semantic-revision']");
    value.waitFor();
    String expected = Integer.toString(revision);
    page.waitForCondition(() -> expected.equals(value.innerText()));
  }

  private static void assertRevision(Page page, int revision) {
    assertEquals(
        Integer.toString(revision), page.locator("[data-testid='semantic-revision']").innerText());
  }

  private static void prepareAcceptedEvent(Page page) {
    page.evaluate(
        """
        input => {
          const source = new EventSource(
            `/workflow/sessions/${encodeURIComponent(input.sessionId)}/events?afterSequence=0`
          );
          window.__durableAcceptedEvent = new Promise((resolve, reject) => {
            const timeout = window.setTimeout(() => {
              source.close();
              reject(new Error('Timed out waiting for durable accepted event'));
            }, 30000);
            source.addEventListener('OPERATION_ACCEPTED', event => {
              const value = JSON.parse(event.data);
              if (value.operationId === input.operationId) {
                window.clearTimeout(timeout);
                source.close();
                resolve(value);
              }
            });
          });
        }
        """,
        Map.of("sessionId", SESSION_ID, "operationId", OPERATION_ID));
  }

  @SuppressWarnings("unchecked")
  private static Map<?, ?> awaitAcceptedEvent(Page page) {
    return (Map<?, ?>) page.evaluate("() => window.__durableAcceptedEvent");
  }

  @SuppressWarnings("unchecked")
  private static Map<?, ?> submitSessionOperation(Page page, int expectedRevision) {
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
                    expectedRevision: input.expectedRevision,
                    operation: {
                      type: 'CreateNode',
                      operationId: input.operationId,
                      catalogType: 'synthetic-signal-generator',
                      nodeId: input.nodeId
                    }
                  })
                }
              );
              return {status: response.status, body: await response.json()};
            }
            """,
            Map.of(
                "sessionId",
                SESSION_ID,
                "operationId",
                OPERATION_ID,
                "nodeId",
                NODE_ID,
                "expectedRevision",
                expectedRevision,
                "actor",
                Map.of(
                    "actorId",
                    "actor-e2e-durable",
                    "userId",
                    "user-e2e-durable",
                    "displayName",
                    "Durable E2E")));
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
                      author: 'durable-e2e',
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

  private static void applyLegacyOperation(Page page) {
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
                      operationId: 'operation.e2e.legacy.after-checkpoint',
                      catalogType: 'gain',
                      nodeId
                    })
                  });
                  return response.status;
                }
                """,
                LEGACY_NODE_ID);
    assertEquals(200, status.intValue());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> inspectSession(Page page) {
    return (Map<String, Object>)
        page.evaluate(
            """
            async sessionId => await (
              await fetch(`/workflow/sessions/${encodeURIComponent(sessionId)}`)
            ).json()
            """,
            SESSION_ID);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> sessionProjection(Page page) {
    return (Map<String, Object>)
        page.evaluate(
            """
            async sessionId => await (
              await fetch(`/workflow/sessions/${encodeURIComponent(sessionId)}/projection`)
            ).json()
            """,
            SESSION_ID);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> checkpointHistory(Page page) {
    return (List<Map<String, Object>>)
        page.evaluate(
            """
            async () => await (
              await fetch('/workflow/history?branch=main&limit=20')
            ).json()
            """);
  }

  private static boolean containsCommit(List<Map<String, Object>> history, String commitId) {
    return history.stream().anyMatch(entry -> commitId.equals(entry.get("commitId")));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadCommit(Page page, String commitId) {
    return (Map<String, Object>)
        page.evaluate(
            """
            async commitId => await (
              await fetch('/workflow/load', {
                method: 'POST',
                headers: {'Content-Type': 'application/json', Accept: 'application/json'},
                body: JSON.stringify({commitId})
              })
            ).json()
            """,
            commitId);
  }

  @SuppressWarnings("unchecked")
  private static List<String> projectionNodeIds(Map<String, Object> projection) {
    return ((List<Map<String, Object>>) projection.get("nodes"))
        .stream().map(node -> String.valueOf(node.get("id"))).toList();
  }

  private static List<String> awaitPublication(
      WatchService watchService, Path publications, String eventId) throws IOException {
    long deadline = System.nanoTime() + PUBLICATION_TIMEOUT.toNanos();
    while (true) {
      List<String> lines = publicationLines(publications);
      if (lines.stream().anyMatch(line -> line.startsWith(eventId + "\t"))) {
        return lines;
      }
      long remaining = deadline - System.nanoTime();
      assertTrue(remaining > 0, "Durable outbox event was not published after restart");
      WatchKey key;
      try {
        key = watchService.poll(remaining, TimeUnit.NANOSECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for durable publication", exception);
      }
      assertNotNull(key, "Durable outbox event was not published after restart");
      key.pollEvents();
      assertTrue(key.reset(), "Durable publication directory became unavailable");
    }
  }

  private static List<String> publicationLines(Path publications) throws IOException {
    return Files.exists(publications)
        ? Files.readAllLines(publications, StandardCharsets.UTF_8)
        : List.of();
  }

  private static int number(Map<?, ?> map, String field) {
    return ((Number) map.get(field)).intValue();
  }

  private static String text(Map<?, ?> map, String field) {
    Object value = map.get(field);
    assertNotNull(value, "Missing field " + field);
    return value.toString();
  }

  private void captureDurableFailure(
      String stage, WorkbenchBrowserHarness harness, Path publications, Throwable failure) {
    harness.captureFailure("durable-restart-" + stage, failure);
    Path diagnostics = Path.of("target", "durable-restart-diagnostics", stage).toAbsolutePath();
    try {
      Files.createDirectories(diagnostics);
      try (Stream<Path> paths = Files.walk(dataDirectory)) {
        List<String> inventory = paths.sorted().map(this::describeDurablePath).toList();
        Files.write(diagnostics.resolve("durable-files.tsv"), inventory, StandardCharsets.UTF_8);
      }
      if (Files.exists(publications)) {
        Files.copy(
            publications,
            diagnostics.resolve("published-outbox.tsv"),
            StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException | RuntimeException diagnosticsFailure) {
      failure.addSuppressed(diagnosticsFailure);
    }
  }

  private String describeDurablePath(Path path) {
    Path relative = dataDirectory.relativize(path);
    try {
      if (Files.isDirectory(path)) {
        return relative + "	directory";
      }
      return relative + "	file	" + Files.size(path) + " bytes";
    } catch (IOException exception) {
      return relative + "	unreadable	" + exception.getMessage();
    }
  }

  private static boolean isDockerAvailable() {
    try {
      return DockerClientFactory.instance().isDockerAvailable();
    } catch (Exception exception) {
      return false;
    }
  }

  private static boolean pathPropertyExists(String property) {
    String value = System.getProperty(property);
    return value != null && !value.isBlank() && Files.exists(Path.of(value));
  }
}
