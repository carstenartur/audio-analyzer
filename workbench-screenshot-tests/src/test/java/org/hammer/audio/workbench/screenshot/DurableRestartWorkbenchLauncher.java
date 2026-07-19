package org.hammer.audio.workbench.screenshot;

import org.hammer.audio.app.WorkbenchApplication;
import org.springframework.boot.SpringApplication;

/**
 * Test-only launcher that adds durable-restart observation adapters to the packaged application.
 */
public final class DurableRestartWorkbenchLauncher {

  private DurableRestartWorkbenchLauncher() {}

  /** Starts the real workbench and registers the test publisher before context refresh. */
  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(WorkbenchApplication.class);
    application.addInitializers(DurableRestartTestConfiguration::registerPublisher);
    application.run(args);
  }
}
