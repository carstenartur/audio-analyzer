package org.hammer.audio.workbench.screenshot;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.hammer.audio.acquisition.health.ExperimentHealthState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Packaged-browser evidence for profile edits, real hardware blockers and reproducible simulation.
 */
@Tag("screenshot")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicrophoneExperimentWebIT {

  private GenericContainer<?> container;
  private Playwright playwright;
  private Browser browser;

  @BeforeAll
  void setUp() throws IOException {
    assumeTrue(
        isDockerAvailable(), "Docker is unavailable — skipping microphone experiment browser test");
    String jarPath = System.getProperty("workbench.jar.path");
    assumeTrue(
        jarPath != null && Files.isRegularFile(Path.of(jarPath)),
        "Build the application JAR first");
    container = WorkbenchContainerFactory.create();
    container.start();
    playwright = Playwright.create();
    browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
  }

  @AfterAll
  void tearDown() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
    if (container != null) {
      container.stop();
    }
  }

  @Test
  void profileReadinessSimulationAndEvidenceRemainVisibleInOneWorkflow() throws IOException {
    Map<String, byte[]> screenshots = new LinkedHashMap<>();
    try (Page page = newExperimentPage()) {
      openExperiment(page);
      assertFalse(page.locator("[data-testid='experiment-start']").isEnabled());

      Locator profileSummary = page.locator("[data-testid='experiment-profile-editor'] > summary");
      profileSummary.click();
      assertTrue(page.locator("[data-testid='experiment-profile-name']").isVisible());
      page.locator("[data-testid='experiment-profile-name']").fill("Browser validation array");
      assertFalse(page.locator("[data-testid='experiment-preflight']").isEnabled());
      page.locator("[data-testid='experiment-save-profile']").click();
      page.waitForCondition(
          () ->
              page.locator("[data-testid='experiment-profile']")
                  .innerText()
                  .contains("Browser validation array"));
      profileSummary.click();
      assertFalse(page.locator("[data-testid='experiment-profile-name']").isVisible());
      page.locator("[data-testid='experiment-duration']").fill("1");
      page.locator("[data-testid='experiment-preflight']").click();
      page.waitForCondition(
          () ->
              page.locator("[data-testid='experiment-health-state']").innerText().equals("READY"));
      assertTrue(page.locator("[data-testid='experiment-start']").isEnabled());
      screenshots.put("microphone-experiment-ready.png", screenshot(page));

      page.locator("[data-testid='experiment-source-mode']").selectOption("LIVE");
      assertFalse(page.locator("[data-testid='experiment-start']").isEnabled());
      page.locator("[data-testid='experiment-preflight']").click();
      page.waitForCondition(
          () ->
              page.locator("[data-testid='experiment-health-state']")
                  .innerText()
                  .equals("BLOCKED"));
      assertTrue(page.locator("[data-testid='finding-source.live-device']").isVisible());
      assertFalse(page.locator("[data-testid='experiment-start']").isEnabled());
      screenshots.put("microphone-experiment-blocked.png", screenshot(page));

      page.locator("[data-testid='experiment-source-mode']").selectOption("SIMULATION");
      page.locator("[data-testid='experiment-preflight']").click();
      page.waitForCondition(() -> page.locator("[data-testid='experiment-start']").isEnabled());
      page.locator("[data-testid='experiment-start']").click();
      page.locator("[data-testid='experiment-export']").waitFor();
      assertEquals(
          "COMPLETED", page.locator("[data-testid='experiment-health-state']").innerText());
      assertTrue(
          page.locator("[data-testid='experiment-evidence']")
              .innerText()
              .contains("Complete experiment evidence"));
      assertTrue(
          page.locator("[data-testid='experiment-evidence']").innerText().contains("AVAILABLE"));
      assertEveryActualTrackIsReadable(page);
      // Actual wall-clock overruns vary with CI load. Keep the functional evidence assertions,
      // and cover this warning visibly in the fixed degraded fixture below.
      screenshots.put(
          "microphone-experiment-complete.png",
          screenshot(
              page, "[data-testid='finding-processing.overruns'] { display: none !important; }"));

      Download download =
          page.waitForDownload(() -> page.locator("[data-testid='experiment-export']").click());
      try (ZipFile evidence = new ZipFile(download.path().toFile())) {
        for (String name :
            List.of(
                "experiment.manifest",
                "capture.aarec",
                "localization.csv",
                "summary.md",
                "benchmark.md",
                "health.txt",
                "SHA256SUMS")) {
          assertTrue(evidence.getEntry(name) != null, "Evidence must include " + name);
        }
        String facts =
            new String(
                evidence.getInputStream(evidence.getEntry("health.txt")).readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(facts.contains("evidence.complete=true"));
        assertTrue(facts.contains("processed.frames=16000"));
        assertTrue(facts.contains("recording.integrity=COMPLETE"));
      }
    }
    captureRuntimeStates(screenshots);
    // Collect every new baseline in one CI run even if the first screenshot has no baseline yet.
    assertAll(
        screenshots.entrySet().stream()
            .map(
                entry ->
                    (org.junit.jupiter.api.function.Executable)
                        () ->
                            ScreenshotPipeline.processScreenshot(
                                entry.getKey(), entry.getValue())));
  }

  @Test
  void controlsHaveAccessibleNamesKeyboardAccessAndReadableStateColors() {
    // Accessibility is an independent hard gate, so a failure still allows the functional scenario
    // to collect all six documentation images in the same CI run.
    try (Page page = newExperimentPage()) {
      openExperiment(page);
      assertAccessibleNames(page);
      Locator profileSummary = page.locator("[data-testid='experiment-profile-editor'] > summary");
      // Playwright's implicit-role table does not map native SUMMARY to BUTTON. Check its exact
      // accessible name independently, while preserving the native keyboard interaction below.
      assertThat(profileSummary).hasCount(1);
      assertThat(profileSummary).hasText("Edit geometry, device format and calibration");
      assertThat(profileSummary).hasAccessibleName("Edit geometry, device format and calibration");
      focusWithKeyboard(page, profileSummary);
      assertKeyboardFocusVisible(profileSummary);
      page.keyboard().press("Enter");
      assertTrue(
          page.getByLabel("Profile name", new Page.GetByLabelOptions().setExact(true)).isVisible());
      page.keyboard().press("Enter");
      assertFalse(page.locator("[data-testid='experiment-profile-name']").isVisible());
      page.locator("[data-testid='experiment-duration']").fill("1");
      Locator preflight =
          page.getByRole(
              AriaRole.BUTTON,
              new Page.GetByRoleOptions().setName("Check experiment readiness").setExact(true));
      focusWithKeyboard(page, preflight);
      assertKeyboardFocusVisible(preflight);
      page.keyboard().press("Enter");
      page.waitForCondition(
          () ->
              page.locator("[data-testid='experiment-health-state']").innerText().equals("READY"));
      assertTrue(page.locator("[data-testid='experiment-start']").isEnabled());
      assertContrast(page.locator("[data-testid='experiment-health-state']"));
      assertContrast(page.locator(".experiment-help").first());
      assertContrast(page.getByLabel("Source mode", new Page.GetByLabelOptions().setExact(true)));
      assertContrast(preflight);
      assertContrast(page.locator("[data-testid='experiment-start']"));

      page.locator("[data-testid='experiment-source-mode']").selectOption("LIVE");
      preflight.click();
      page.waitForCondition(
          () ->
              page.locator("[data-testid='experiment-health-state']")
                  .innerText()
                  .equals("BLOCKED"));
      assertFalse(page.locator("[data-testid='experiment-start']").isEnabled());
      assertContrast(page.locator("[data-testid='experiment-health-state']"));
      assertContrast(page.locator("[data-testid='finding-source.live-device'] strong"));
    }
    for (var state : runtimeStates()) {
      try (Page page = newExperimentPage()) {
        openRuntimeState(page, state);
        assertContrast(page.locator("[data-testid='experiment-health-state']"));
        assertContrast(page.locator("[data-testid='experiment-runtime-strip'] strong"));
      }
    }
  }

  private void assertEveryActualTrackIsReadable(Page page) {
    var response =
        page.context()
            .request()
            .get(WorkbenchContainerFactory.baseUrl(container) + "/experiment-operations/status");
    assertTrue(response.ok());
    var tracks = new JsonMapper().readTree(response.text()).get("tracks");
    assertTrue(
        tracks.isArray() && !tracks.isEmpty(), "Simulation must produce localization tracks");
    assertEquals(
        tracks.size(), page.locator("[data-testid='experiment-track-table'] tbody tr").count());
    assertEquals(tracks.size(), page.locator("[data-testid^='experiment-track-marker-']").count());
    for (var track : tracks) {
      int id = track.get("id").intValue();
      Locator row = page.locator("[data-testid='experiment-track-row-" + id + "']");
      assertTrue(row.isVisible(), "Every actual track needs its own readable row");
      assertEquals(Integer.toString(id), row.locator("th").innerText());
      Locator marker = page.locator("[data-testid='experiment-track-marker-" + id + "']");
      assertEquals("experiment-track-title-" + id, marker.getAttribute("aria-labelledby"));
      assertEquals(1, marker.locator("title").count());
      String title = marker.locator("title").textContent();
      assertTrue(title != null && title.startsWith("Track " + id + " · "));
    }
  }

  private Page newExperimentPage() {
    return browser.newPage(new Browser.NewPageOptions().setLocale("en-US").setTimezoneId("UTC"));
  }

  private void openExperiment(Page page) {
    page.setViewportSize(1440, 1000);
    page.setDefaultTimeout(30_000);
    page.navigate(WorkbenchContainerFactory.baseUrl(container) + "/workbench/experiment");
    page.locator("[data-testid='experiment-operations']").waitFor();
    page.waitForCondition(
        () -> page.locator("[data-testid='experiment-profile'] option").count() > 0);
  }

  private static List<ExperimentHealthState> runtimeStates() {
    return List.of(
        ExperimentHealthState.RUNNING,
        ExperimentHealthState.DEGRADED,
        ExperimentHealthState.INCOMPLETE);
  }

  private void openRuntimeState(Page page, ExperimentHealthState state) {
    String body =
        JsonMapper.builder()
            .findAndAddModules()
            .build()
            .writeValueAsString(MicrophoneExperimentHealthFixtures.snapshot(state));
    page.route(
        "**/experiment-operations/status",
        route ->
            route.fulfill(
                new Route.FulfillOptions().setContentType("application/json").setBody(body)));
    openExperiment(page);
    page.waitForCondition(
        () ->
            page.locator("[data-testid='experiment-health-state']")
                .innerText()
                .equals(state.name()));
  }

  private void captureRuntimeStates(Map<String, byte[]> screenshots) {
    for (var state : runtimeStates()) {
      try (Page page = newExperimentPage()) {
        openRuntimeState(page, state);
        assertFalse(page.locator("[data-testid='experiment-start']").isEnabled());
        assertEquals(
            state != ExperimentHealthState.INCOMPLETE,
            page.locator("[data-testid='experiment-stop']").isEnabled());
        if (state == ExperimentHealthState.INCOMPLETE) {
          assertTrue(
              page.locator("[data-testid='experiment-evidence']")
                  .innerText()
                  .contains("Incomplete experiment evidence"));
          assertTrue(page.locator("[data-testid='finding-recording.incomplete']").isVisible());
        } else {
          assertTrue(
              page.locator("[data-testid='experiment-runtime-strip']")
                  .innerText()
                  .contains("● Recording"));
        }
        String suffix =
            state == ExperimentHealthState.RUNNING
                ? "recording"
                : state.name().toLowerCase(java.util.Locale.ROOT);
        if (state == ExperimentHealthState.DEGRADED) {
          assertTrue(page.locator("[data-testid='finding-processing.overruns']").isVisible());
        }
        // All storage, rate, time, path and timing values are fixed in these typed snapshots.
        // Keep them visible so the documentation demonstrates the actual runtime status fields.
        screenshots.put("microphone-experiment-" + suffix + ".png", screenshot(page, "", false));
      }
    }
  }

  private static void assertAccessibleNames(Page page) {
    for (var control :
        Map.of(
                "Experiment name", AriaRole.TEXTBOX,
                "Source mode", AriaRole.COMBOBOX,
                "Microphone-array profile", AriaRole.COMBOBOX,
                "Intended duration (seconds)", AriaRole.SPINBUTTON)
            .entrySet()) {
      String label = control.getKey();
      Locator input = page.getByLabel(label, new Page.GetByLabelOptions().setExact(true));
      assertEquals(1, input.count(), "Expected one setting with the exact label: " + label);
      assertTrue(input.isVisible());
      assertEquals(
          1,
          page.getByRole(
                  control.getValue(), new Page.GetByRoleOptions().setName(label).setExact(true))
              .count(),
          "Expected one control with the exact accessible name: " + label);
    }
    for (String name :
        List.of(
            "Check experiment readiness",
            "Start experiment",
            "Stop & finalize",
            "Export diagnostic snapshot")) {
      assertEquals(
          1,
          page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name).setExact(true))
              .count());
    }
    Locator badge = page.locator("[data-testid='experiment-health-state']");
    assertEquals("polite", badge.getAttribute("aria-live"));
    assertEquals("true", badge.getAttribute("aria-atomic"));
  }

  private static void focusWithKeyboard(Page page, Locator target) {
    for (int step = 0; step < 40; step++) {
      if (Boolean.TRUE.equals(target.evaluate("element => element === document.activeElement"))) {
        return;
      }
      page.keyboard().press("Tab");
    }
    assertTrue(
        Boolean.TRUE.equals(target.evaluate("element => element === document.activeElement")),
        "The experiment control must be reachable with Tab");
  }

  private static void assertKeyboardFocusVisible(Locator target) {
    assertTrue(
        Boolean.TRUE.equals(
            target.evaluate(
                """
                element => {
                  const style = getComputedStyle(element);
                  return element.matches(':focus-visible') && style.outlineStyle !== 'none'
                    && parseFloat(style.outlineWidth) >= 2;
                }
                """)),
        "Keyboard focus must have a visible outline");
    assertContrast(target, "outlineColor", 3.0);
  }

  private static void assertContrast(Locator target) {
    assertContrast(target, "color", 4.5);
  }

  private static void assertContrast(Locator target, String property, double minimum) {
    // Composite the actual computed solid backgrounds, including transparent badges and labels.
    double ratio =
        ((Number)
                target.evaluate(
                    """
                    (element, property) => {
                      const rgba = color => color.match(/[\\d.]+/g).map(Number);
                      const over = (front, back) => front.slice(0, 3).map(
                        (channel, index) => channel * (front[3] ?? 1) + back[index] * (1 - (front[3] ?? 1)));
                      const parents = [];
                      for (let node = element; node; node = node.parentElement) parents.unshift(node);
                      let background = [255, 255, 255];
                      for (const node of parents) background = over(rgba(getComputedStyle(node).backgroundColor), background);
                      const foreground = over(rgba(getComputedStyle(element)[property]), background);
                      const luminance = rgb => rgb.map(channel => {
                        const value = channel / 255;
                        return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
                      }).reduce((sum, channel, index) => sum + channel * [0.2126, 0.7152, 0.0722][index], 0);
                      const values = [luminance(foreground), luminance(background)].sort((a, b) => a - b);
                      return (values[1] + 0.05) / (values[0] + 0.05);
                    }
                    """,
                    property))
            .doubleValue();
    assertTrue(
        ratio >= minimum,
        "Computed " + property + " contrast must reach " + minimum + ":1; was " + ratio);
  }

  private static byte[] screenshot(Page page) {
    return screenshot(page, "");
  }

  private static byte[] screenshot(Page page, String style) {
    return screenshot(page, style, true);
  }

  private static byte[] screenshot(Page page, String style, boolean maskVolatileValues) {
    var options = new Page.ScreenshotOptions().setFullPage(true).setStyle(style);
    if (maskVolatileValues) {
      options.setMask(List.of(page.locator("[data-screenshot-mask='true']:visible")));
    }
    return page.screenshot(options);
  }

  private static boolean isDockerAvailable() {
    try {
      return DockerClientFactory.instance().isDockerAvailable();
    } catch (Exception exception) {
      return false;
    }
  }
}
