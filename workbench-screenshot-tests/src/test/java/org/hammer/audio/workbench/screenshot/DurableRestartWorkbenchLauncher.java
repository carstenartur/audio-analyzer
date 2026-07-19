package org.hammer.audio.workbench.screenshot;

import org.hammer.audio.app.WorkbenchApplication;
import org.springframework.boot.SpringApplication;

/** Test-only launcher that adds durable-restart observation adapters to the packaged application. */
public final class DurableRestartWorkbenchLauncher {

  private DurableRestartWorkbenchLauncher() {}

  /** Starts the real workbench plus the test-module-only outbox publisher configuration. */
  public static void main(String[] args) {
    SpringApplication.run(
        new Class<?>[] {WorkbenchApplication.class, DurableRestartTestConfiguration.class}, args);
  }
}
