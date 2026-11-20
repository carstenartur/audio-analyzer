package org.hammer.audio.workbench.screenshot;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.util.List;

/** Waits for real, correlated health observations before capturing workbench documentation. */
final class WorkbenchHealthScreenshots {

  private WorkbenchHealthScreenshots() {}

  static void awaitReadiness(Page page, Long expectedRevision) {
    Locator document =
        page.locator("[data-testid='shared-experiment-health'][aria-label='Document readiness']");
    document.waitFor();
    page.waitForCondition(() -> observed(document));
    if (expectedRevision != null) {
      Locator workflow =
          page.locator(
              "[data-testid='shared-experiment-health'][aria-label='Selected workflow readiness']");
      workflow.waitFor();
      page.waitForCondition(
          () ->
              observed(workflow)
                  && expectedRevision
                      .toString()
                      .equals(
                          workflow
                              .locator(
                                  ".experiment-health__summary dt:text-is('Semantic revision') +"
                                      + " dd")
                              .innerText()));
    }
  }

  private static boolean observed(Locator panel) {
    if (panel.locator(".experiment-health__summary").count() != 1) {
      return false;
    }
    String state = panel.locator("[data-testid='shared-experiment-health-state']").innerText();
    return !state.contains("Checking readiness") && !state.contains("STATUS UNAVAILABLE");
  }

  static Page.ScreenshotOptions options(Page page) {
    // Closed details must not project mask rectangles onto unrelated visible controls. Scenario
    // helpers separately normalize visible generated identifiers after their functional assertions.
    return new Page.ScreenshotOptions()
        .setFullPage(false)
        .setMask(List.of(page.locator("[data-screenshot-mask='true']:visible")));
  }
}
