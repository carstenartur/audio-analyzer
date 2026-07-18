package org.hammer.audio.app;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Forwards maintained browser-workbench routes to the packaged React application shell. */
@Controller
public final class WorkbenchSpaController {

  /** Serves known client-side workbench routes without shadowing workflow API or asset paths. */
  @GetMapping({"/workbench", "/workbench/", "/workbench/{route:[A-Za-z0-9_-]+}"})
  public String workbenchRoute() {
    return "forward:/index.html";
  }
}
