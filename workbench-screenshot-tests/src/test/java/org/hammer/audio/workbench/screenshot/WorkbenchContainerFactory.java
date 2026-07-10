package org.hammer.audio.workbench.screenshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Factory that builds and starts a Testcontainers container running the workbench HTTP server.
 *
 * <p>The container is built from the pre-compiled {@code audio-app} JAR and its runtime
 * dependencies. Paths are injected via Maven Failsafe system properties:
 *
 * <ul>
 *   <li>{@code workbench.jar.path} — path to {@code audio-app-*.jar}
 *   <li>{@code workbench.lib.dir} — path to the {@code target/lib/} dependency directory
 *   <li>{@code workbench.static.dir} — path to the workbench static HTML directory
 * </ul>
 *
 * <p>The container exposes port {@value #WORKBENCH_PORT} and is considered ready once {@code GET
 * /workflow/projection} returns HTTP 200.
 */
public final class WorkbenchContainerFactory {

  /** Port exposed by the workbench HTTP server inside the container. */
  public static final int WORKBENCH_PORT = 8080;

  private static final String LAUNCHER_CLASS =
      "org.hammer.audio.workflow.editor.http.WorkflowHttpServerLauncher";

  private WorkbenchContainerFactory() {}

  /**
   * Creates a new, not-yet-started container for the workbench server.
   *
   * <p>The Docker image is built from the pre-compiled artifacts located via system properties. If
   * any required artifact path is missing the method throws {@link IllegalStateException}.
   *
   * @return configured container ready to be started
   * @throws IOException if a temporary staging directory cannot be created
   */
  public static GenericContainer<?> create() throws IOException {
    Path jarPath = requirePath("workbench.jar.path");
    Path libDir = requirePath("workbench.lib.dir");
    Path staticDir = requirePath("workbench.static.dir");

    String dockerfileContent =
        "FROM eclipse-temurin:21-jre-alpine\n"
            + "WORKDIR /app\n"
            + "COPY app.jar /app/app.jar\n"
            + "COPY lib /app/lib\n"
            + "COPY static /app/static\n"
            + "EXPOSE "
            + WORKBENCH_PORT
            + "\n"
            + "ENTRYPOINT [\"java\","
            + " \"-cp\", \"/app/app.jar:/app/lib/*\","
            + " \""
            + LAUNCHER_CLASS
            + "\","
            + " \""
            + WORKBENCH_PORT
            + "\","
            + " \"/app/static\"]\n";

    ImageFromDockerfile image =
        new ImageFromDockerfile()
            .withFileFromString("Dockerfile", dockerfileContent)
            .withFileFromPath("app.jar", jarPath)
            .withFileFromPath("lib", libDir)
            .withFileFromPath("static", staticDir);

    return new GenericContainer<>(image)
        .withExposedPorts(WORKBENCH_PORT)
        .waitingFor(
            Wait.forHttp("/workflow/projection")
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofSeconds(90)));
  }

  /**
   * Returns the base URL of the workbench running inside the given container.
   *
   * @param container a started workbench container
   * @return base URL, e.g. {@code http://localhost:32769}
   */
  public static String baseUrl(GenericContainer<?> container) {
    return "http://" + container.getHost() + ":" + container.getMappedPort(WORKBENCH_PORT);
  }

  private static Path requirePath(String property) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "System property '"
              + property
              + "' is not set. "
              + "Run screenshot tests via Maven Failsafe with the screenshot-tests profile.");
    }
    Path path = Path.of(value);
    if (!Files.exists(path)) {
      throw new IllegalStateException(
          "Path referenced by system property '"
              + property
              + "' does not exist: "
              + path
              + ". Build the project first with 'mvn package -pl audio-app -am'.");
    }
    return path;
  }
}
