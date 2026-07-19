package org.hammer.audio.workbench.screenshot;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.testcontainers.containers.GenericContainer;

/**
 * Reusable packaged-application harness for isolated Playwright browser actors.
 *
 * <p>The harness owns one real workbench container and one Chromium process. Every actor receives an
 * isolated browser context, storage partition and trace. Failures publish enough information to
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
    GenericContainer<?> container = WorkbenchContainerFactory.create();
    container.start();
    Playwright playwright = Playwright.create();
    Browser browser =
        playwright
            .chromium()
            .launch(new BrowserType.LaunchOptions().setHeadless(true).setTimeout(60_000));
    return new WorkbenchBrowserHarness(
        container, playwright, browser, configuredArtifactsDirectory());
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
    context.addInitScript(
        "window.sessionStorage.setItem('audio-analyzer.workflow.actor', JSON.stringify("
            + actorJson(actorId, userId, displayName)
            + "));"
            + "window.sessionStorage.removeItem('audio-analyzer.workflow.active-session');");
    context
        .tracing()
        .start(
            new Tracing.StartOptions().setScreenshots(true).setSnapshots(true).setSources(true));

    Page page = context.newPage();
    page.setDefaultTimeout(DEFAULT_TIMEOUT_MILLIS);
    page.setDefaultNavigationTimeout(DEFAULT_TIMEOUT_MILLIS);
    ActorBrowser actor = new ActorBrowser(actorId, context, page);
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

  private static String actorJson(String actorId, String userId, String displayName) {
    return "{actorId:'"
        + javascript(actorId)
        + "',userId:'"
        + javascript(userId)
        + "',displayName:'"
        + javascript(displayName)
        + "'}";
  }

  private static String javascript(String value) {
    return Objects.requireNonNull(value, "value")
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "\\r")
        .replace("\n", "\\n");
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
    }
  }
}
