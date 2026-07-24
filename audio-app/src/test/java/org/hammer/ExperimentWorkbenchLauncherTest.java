package org.hammer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExperimentWorkbenchLauncherTest {

  @Test
  void startupArgumentsInspectEveryValidNonBlankPath() {
    ArrayList<Path> inspected = new ArrayList<>();
    String invalidPath = "invalid" + Character.MIN_VALUE;

    ExperimentWorkbenchLauncher.inspectStartupArguments(
        new String[] {
          "first.audioexp", " ", null, invalidPath, "nested/second.audioexp"
        },
        inspected::add);

    assertEquals(
        List.of(Path.of("first.audioexp"), Path.of("nested/second.audioexp")), inspected);
  }
}
