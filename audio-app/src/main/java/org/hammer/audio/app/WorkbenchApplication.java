package org.hammer.audio.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 4.1 entry point for the workflow workbench.
 *
 * <p>Starts the embedded servlet container with the workflow editor and collaboration-session REST
 * APIs. Configuration is driven by {@code application.properties} and command-line overrides:
 *
 * <ul>
 *   <li>{@code server.port} — TCP port (default: 8080)
 *   <li>{@code workbench.persistence.mode} — memory, hibernate or filesystem
 *   <li>{@code workbench.static.dir} — filesystem path for static web content; omit to serve from
 *       the built-in classpath UI ({@code /workbench-ui/})
 * </ul>
 *
 * <p>Example — in-memory mode:
 *
 * <pre>{@code
 * java -jar audio-app-0.0.4-SNAPSHOT-workbench.jar
 * }</pre>
 *
 * <p>Persistent startup examples are documented in {@code docs/workbench-hibernate-persistence.md}.
 */
@SpringBootApplication(
    scanBasePackages = {
      "org.hammer.audio.app",
      "org.hammer.audio.experiment.document.http",
      "org.hammer.audio.workflow.editor.http"
    })
public class WorkbenchApplication {

  /** Starts the Spring Boot workbench application. */
  public static void main(String[] args) {
    SpringApplication.run(WorkbenchApplication.class, args);
  }
}
