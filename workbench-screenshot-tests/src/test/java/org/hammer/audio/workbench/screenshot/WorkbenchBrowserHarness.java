package org.hammer.audio.workbench.screenshot;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.RequestOptions;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reusable packaged-application harness for isolated Playwright browser actors.
 *
 * <p>The harness owns one real workbench container and one Chromium process. Every actor receives
 * an isolated browser context, storage partition and trace. Failures publish enough information to
 * diagnose browser, transport and server behaviour without rerunning the scenario interactively.
 */
final class WorkbenchBrowserHarness implements AutoCloseable {

  static final int DEFAULT_TIMEOUT_MILLIS = 30_000;
  static final int VIEWPORT_WIDTH = 1440;
  static final int VIEWPORT_HEIGHT = 900;

  private final GenericContainer<?> container;
  private final Playwright playwright;
  private final Browser browser;
  private final Path artifactsDirectory;
  private final List<ActorBrowser> actors = new ArrayList<>();

  private WorkbenchBrowserHarness(
      GenericContainer<?> container,
      Playwright playwright,
      Browser browser,
      Path artifactsDirectory) {
    this.container = Objects.requireNonNull(container, "container");
    this.playwright = Objects.requireNonNull(playwright, "playwright");
    this.browser = Objects.requireNonNull(browser, "browser");
    this.artifactsDirectory = Objects.requireNonNull(artifactsDirectory, "artifactsDirectory");
  }

  static WorkbenchBrowserHarness start() throws IOException {
    return start(WorkbenchContainerFactory.create());
  }

  static WorkbenchBrowserHarness start(GenericContainer<?> container) {
    GenericContainer<?> requiredContainer = Objects.requireNonNull(container, "container");
    requiredContainer.start();
    Playwright playwright = Playwright.create();
    try {
      Browser browser =
          playwright
              .chromium()
              .launch(new BrowserType.LaunchOptions().setHeadless(true).setTimeout(60_000));
      return new WorkbenchBrowserHarness(
          requiredContainer, playwright, browser, configuredArtifactsDirectory());
    } catch (RuntimeException failure) {
      playwright.close();
      if (requiredContainer.isRunning()) {
        requiredContainer.stop();
      }
      throw failure;
    }
  }

  String baseUrl() {
    return WorkbenchContainerFactory.baseUrl(container);
  }

  ActorBrowser openActor(String actorId, String userId, String displayName) {
    BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .setAcceptDownloads(false));
    APIResponse issued =
        context
            .request()
            .post(
                baseUrl() + "/workflow/identity",
                RequestOptions.create().setData(Map.of("displayName", displayName)));
    if (!issued.ok()) {
      context.close();
      throw new IllegalStateException("Could not issue browser identity: " + issued.status());
    }
    String identityJson = issued.text();
    return openIdentity(context, identityJson);
  }

  ActorBrowser resumeActor(IdentitySnapshot snapshot) {
    BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .setAcceptDownloads(false)
                .setStorageState(snapshot.storageState()));
    return openIdentity(context, snapshot.identityJson());
  }

  private ActorBrowser openIdentity(BrowserContext context, String identityJson) {
    String issuedActorId = new JsonMapper().readTree(identityJson).get("actorId").asText();
    context.addInitScript(
        "if (!window.sessionStorage.getItem('audio-analyzer.workflow.actor')) {"
            + "window.sessionStorage.setItem('audio-analyzer.workflow.actor', JSON.stringify("
            + identityJson
            + "));}");
    context
        .tracing()
        .start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true).setSources(true));

    Page page = context.newPage();
    page.setDefaultTimeout(DEFAULT_TIMEOUT_MILLIS);
    page.setDefaultNavigationTimeout(DEFAULT_TIMEOUT_MILLIS);
    ActorBrowser actor = new ActorBrowser(issuedActorId, context, page);
    page.onConsoleMessage(
        message -> actor.diagnostic("console[" + message.type() + "]: " + message.text()));
    page.onPageError(error -> actor.diagnostic("page-error: " + error));
    page.onRequestFailed(
        request ->
            actor.diagnostic(
                "request-failed: "
                    + request.method()
                    + " "
                    + request.url()
                    + " -> "
                    + request.failure()));
    actors.add(actor);
    return actor;
  }

  /** Normalizes only screenshot text, never actual server identities, graph IDs or credentials. */
  static void normalizeIdentityLabels(Page page) {
    page.evaluate(
        """
        () => {
          const panel = document.querySelector('[data-testid="collaboration-panel"]');
          if (!panel) return;
          const walker = document.createTreeWalker(panel, NodeFilter.SHOW_TEXT);
          while (walker.nextNode()) {
            walker.currentNode.textContent = walker.currentNode.textContent
              .replace(/ · [0-9A-F]{6}\\b/g, ' · CLIENT')
              .replace(/actor\\.[a-f0-9-]{36}/g, 'client (masked)')
              .replace(/(node\\.[a-z0-9-]+\\.)[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}/g, '$1example');
          }
          panel.querySelectorAll('time').forEach(element => {
            element.textContent = '19 Jul 2026, 12:00 UTC';
          });
        }
        """);
  }

  void captureFailure(String scenario, Throwable failure) {
    Path scenarioDirectory = artifactsDirectory.resolve(safeFileName(scenario));
    try {
      Files.createDirectories(scenarioDirectory);
      Files.writeString(
          scenarioDirectory.resolve("failure.txt"),
          stackTrace(failure),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
      Files.writeString(
          scenarioDirectory.resolve("server.log"),
          container.getLogs(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
      for (ActorBrowser actor : actors) {
        actor.captureFailure(scenarioDirectory);
      }
    } catch (RuntimeException | IOException diagnosticsFailure) {
      failure.addSuppressed(diagnosticsFailure);
    }
  }

  @Override
  public void close() {
    RuntimeException failure = null;
    for (ActorBrowser actor : List.copyOf(actors)) {
      try {
        actor.close();
      } catch (RuntimeException exception) {
        failure = collect(failure, exception);
      }
    }
    actors.clear();
    try {
      browser.close();
    } catch (RuntimeException exception) {
      failure = collect(failure, exception);
    }
    try {
      playwright.close();
    } catch (RuntimeException exception) {
      failure = collect(failure, exception);
    }
    try {
      if (container.isRunning()) {
        container.stop();
      }
    } catch (RuntimeException exception) {
      failure = collect(failure, exception);
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static Path configuredArtifactsDirectory() {
    String configured = System.getProperty("collaboration.e2e.artifacts.dir");
    if (configured == null || configured.isBlank()) {
      return Path.of("target", "collaboration-e2e-failures").toAbsolutePath();
    }
    return Path.of(configured).toAbsolutePath();
  }

  private static String stackTrace(Throwable failure) {
    StringWriter output = new StringWriter();
    failure.printStackTrace(new PrintWriter(output));
    return output.toString();
  }

  private static String safeFileName(String value) {
    return value.replaceAll("[^a-zA-Z0-9._-]", "-");
  }

  private static RuntimeException collect(
      RuntimeException accumulated, RuntimeException additional) {
    if (accumulated == null) {
      return additional;
    }
    accumulated.addSuppressed(additional);
    return accumulated;
  }

  /** In-memory browser credentials used only to carry the same client across a process restart. */
  record IdentitySnapshot(String storageState, String identityJson) {}

  /** One isolated browser actor and its diagnostics. */
  final class ActorBrowser implements AutoCloseable {
    private final String actorId;
    private final BrowserContext context;
    private final Page page;
    private final List<String> diagnostics = new ArrayList<>();
    private boolean traceStopped;
    private boolean closed;

    private ActorBrowser(String actorId, BrowserContext context, Page page) {
      this.actorId = actorId;
      this.context = context;
      this.page = page;
    }

    IdentitySnapshot identitySnapshot() {
      return new IdentitySnapshot(
          context.storageState(),
          (String) page.evaluate("sessionStorage.getItem('audio-analyzer.workflow.actor')"));
    }

    String actorId() {
      return actorId;
    }

    BrowserContext context() {
      return context;
    }

    Page page() {
      return page;
    }

    private void diagnostic(String value) {
      synchronized (diagnostics) {
        diagnostics.add(value);
      }
    }

    private void captureFailure(Path scenarioDirectory) throws IOException {
      Path actorDirectory = scenarioDirectory.resolve(safeFileName(actorId));
      Files.createDirectories(actorDirectory);
      if (!page.isClosed()) {
        page.screenshot(
            new Page.ScreenshotOptions()
                .setPath(actorDirectory.resolve("page.png"))
                .setFullPage(true));
        Files.writeString(
            actorDirectory.resolve("page.html"),
            page.content(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
      }
      synchronized (diagnostics) {
        Files.write(
            actorDirectory.resolve("browser.log"),
            diagnostics,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
      }
      if (!traceStopped) {
        context
            .tracing()
            .stop(new Tracing.StopOptions().setPath(actorDirectory.resolve("trace.zip")));
        traceStopped = true;
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      if (!traceStopped) {
        context.tracing().stop();
        traceStopped = true;
      }
      context.close();
      actors.remove(this);
    }
  }
}
