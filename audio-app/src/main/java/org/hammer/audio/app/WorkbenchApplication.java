package org.hammer.audio.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the workflow workbench.
 *
 * <p>Starts an embedded Tomcat server with the workflow editor REST API and optional static-file
 * serving. Configuration is driven by {@code application.properties} and command-line overrides:
 *
 * <ul>
 *   <li>{@code server.port} — TCP port (default: 8080)
 *   <li>{@code workbench.data.dir} — JGit bare repository path; omit for in-memory mode
 *   <li>{@code workbench.static.dir} — filesystem path for static web content; omit to serve from
 *       the built-in classpath UI ({@code /workbench-ui/})
 * </ul>
 *
 * <p>Example — in-memory mode (demo / tests):
 *
 * <pre>{@code
 * java -cp app.jar:lib/* org.hammer.audio.app.WorkbenchApplication
 * }</pre>
 *
 * <p>Example — persistent mode (production):
 *
 * <pre>{@code
 * java -cp app.jar:lib/* org.hammer.audio.app.WorkbenchApplication \
 *     --workbench.data.dir=/data/workbench.git
 * }</pre>
 */
@SpringBootApplication(
    scanBasePackages = {"org.hammer.audio.app", "org.hammer.audio.workflow.editor.http"})
public class WorkbenchApplication {

  /**
   * Application entry point.
   *
   * @param args Spring Boot command-line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(WorkbenchApplication.class, args);
  }
}
