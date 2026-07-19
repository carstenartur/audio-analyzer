package org.hammer.audio.workbench.screenshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/** Factory for containers that run the packaged workbench HTTP server. */
public final class WorkbenchContainerFactory {

  /** Port exposed by the workbench HTTP server inside the container. */
  public static final int WORKBENCH_PORT = 8080;

  private static final String LAUNCHER_CLASS = "org.hammer.audio.app.WorkbenchApplication";
  private static final String DATA_MOUNT = "/data";
  private static final String PUBLICATIONS_FILE = DATA_MOUNT + "/published-outbox.tsv";

  private WorkbenchContainerFactory() {}

  /** Creates the normal in-memory workbench container used by screenshots and Stage 1 E2E tests. */
  public static GenericContainer<?> create() throws IOException {
    return baseContainer(image(LAUNCHER_CLASS, false))
        .withCommand(
            "--server.port=" + WORKBENCH_PORT, "--workbench.static.dir=/app/static");
  }

  /**
   * Creates one process in the durable-restart scenario.
   *
   * <p>Successive containers receive the same host directory, so H2, Hibernate collaboration rows and
   * Hibernate-backed JGit objects survive a complete JVM/container restart. The publisher remains
   * disabled in the first process and is explicitly enabled only after restart.
   */
  static GenericContainer<?> createDurableRestart(
      Path dataDirectory, boolean publisherEnabled) throws IOException {
    Files.createDirectories(dataDirectory);
    return baseContainer(image(DurableRestartWorkbenchLauncher.class.getName(), true))
        .withFileSystemBind(
            dataDirectory.toAbsolutePath().toString(), DATA_MOUNT, BindMode.READ_WRITE)
        .withCommand(durableArguments(publisherEnabled));
  }

  /** Returns the host file containing test-observed published durable envelopes. */
  static Path durablePublicationsFile(Path dataDirectory) {
    return dataDirectory.resolve("published-outbox.tsv");
  }

  /** Returns the base URL of a started workbench container. */
  public static String baseUrl(GenericContainer<?> container) {
    return "http://" + container.getHost() + ":" + container.getMappedPort(WORKBENCH_PORT);
  }

  private static GenericContainer<?> baseContainer(ImageFromDockerfile image) {
    return new GenericContainer<>(image)
        .withExposedPorts(WORKBENCH_PORT)
        .waitingFor(
            Wait.forHttp("/workflow/projection")
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofSeconds(120)));
  }

  private static ImageFromDockerfile image(String launcherClass, boolean includeTestClasses)
      throws IOException {
    Path jarPath = requirePath("workbench.jar.path");
    Path libDir = requirePath("workbench.lib.dir");
    Path staticDir = requirePath("workbench.static.dir");
    Path testClasses =
        includeTestClasses ? requirePath("workbench.test.classes.dir") : null;
    String classpath =
        includeTestClasses
            ? "/app/test-classes:/app/app.jar:/app/lib/*"
            : "/app/app.jar:/app/lib/*";

    StringBuilder dockerfile =
        new StringBuilder()
            .append("FROM eclipse-temurin:21-jre-alpine\n")
            .append("WORKDIR /app\n")
            .append("COPY app.jar /app/app.jar\n")
            .append("COPY lib /app/lib\n")
            .append("COPY static /app/static\n");
    if (includeTestClasses) {
      dockerfile.append("COPY test-classes /app/test-classes\n");
    }
    dockerfile
        .append("EXPOSE ")
        .append(WORKBENCH_PORT)
        .append('\n')
        .append("ENTRYPOINT [\"java\",\"-cp\",\"")
        .append(classpath)
        .append("\",\"")
        .append(launcherClass)
        .append("\"]\n");

    ImageFromDockerfile image =
        new ImageFromDockerfile()
            .withFileFromString("Dockerfile", dockerfile.toString())
            .withFileFromPath("app.jar", jarPath)
            .withFileFromPath("lib", libDir)
            .withFileFromPath("static", staticDir);
    if (testClasses != null) {
      image.withFileFromPath("test-classes", testClasses);
    }
    return image;
  }

  private static String[] durableArguments(boolean publisherEnabled) {
    List<String> arguments = new ArrayList<>();
    arguments.add("--server.port=" + WORKBENCH_PORT);
    arguments.add("--workbench.static.dir=/app/static");
    arguments.add("--workbench.persistence.mode=hibernate");
    arguments.add("--workbench.persistence.repository-name=durable-restart-e2e");
    arguments.add("--workbench.persistence.migrations.enabled=true");
    arguments.add("--workbench.persistence.schema-action=validate");
    arguments.add("--spring.datasource.url=jdbc:h2:file:/data/audio-analyzer;AUTO_SERVER=FALSE");
    arguments.add("--spring.datasource.driver-class-name=org.h2.Driver");
    arguments.add("--spring.datasource.username=sa");
    arguments.add("--spring.datasource.password=");
    arguments.add("--workbench.collaboration.outbox.poll-interval-ms=100");
    arguments.add("--workbench.collaboration.outbox.dispatcher-id=durable-restart-e2e");
    arguments.add("--test.outbox.publisher.enabled=" + publisherEnabled);
    arguments.add("--test.outbox.publications.path=" + PUBLICATIONS_FILE);
    return arguments.toArray(String[]::new);
  }

  private static Path requirePath(String property) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "System property '"
              + property
              + "' is not set. Run browser tests via Maven Failsafe with the screenshot-tests profile.");
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
