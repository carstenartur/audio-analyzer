package org.hammer.audio.experiment.document.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperimentLocalResourcePolicyTest {
  @TempDir Path directory;

  @Test
  void noRootsRejectsBothExistingAndAbsentPathsWithTheSameFailure() throws Exception {
    Path secret = Files.writeString(directory.resolve("private"), "private host content");
    var policy = ExperimentLocalResourcePolicy.fromConfiguration("");
    String existing =
        assertThrows(SecurityException.class, () -> policy.assets(Map.of("data", secret)))
            .getMessage();
    String absent =
        assertThrows(
                SecurityException.class,
                () -> policy.assets(Map.of("data", directory.resolve("absent"))))
            .getMessage();
    assertEquals(existing, absent);
    assertThrows(SecurityException.class, () -> policy.outputDirectory(directory));
  }

  @Test
  void approvedRootsPermitDescendantsAndRejectTraversalAndSymlinkEscapes() throws Exception {
    Path approved = Files.createDirectory(directory.resolve("approved"));
    Path privateDirectory = Files.createDirectory(directory.resolve("private"));
    Path secret = Files.writeString(privateDirectory.resolve("secret"), "private");
    Path asset = Files.writeString(approved.resolve("asset"), "shareable");
    var policy = new ExperimentLocalResourcePolicy(List.of(approved));
    assertEquals(asset.toRealPath(), policy.assets(Map.of("data", asset)).get("data"));
    assertEquals(
        approved.resolve("new/results"), policy.outputDirectory(approved.resolve("new/results")));
    assertThrows(SecurityException.class, () -> policy.assets(Map.of("data", secret)));
    assertThrows(
        SecurityException.class, () -> policy.outputDirectory(approved.resolve("../private")));
    Path link = Files.createSymbolicLink(approved.resolve("escape"), privateDirectory);
    assertThrows(
        SecurityException.class, () -> policy.assets(Map.of("data", link.resolve("secret"))));
    assertThrows(SecurityException.class, () -> policy.outputDirectory(link));
  }
}
