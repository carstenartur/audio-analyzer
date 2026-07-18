package org.hammer.audio.app;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class ProductionFrontendPackagingTest {

  private static final String HASHED_ASSET_PATTERN = ".*-[A-Za-z0-9_-]{6,}\\.(?:js|css)$";

  @Test
  void maintainedFrontendIsAvailableFromDependencyClasspathWithCacheSafeAssets()
      throws IOException {
    Resource index = new ClassPathResource("workbench-ui/index.html");
    assertTrue(index.exists(), "packaged React application shell is missing");

    String html = index.getContentAsString(UTF_8);
    assertTrue(html.contains("/assets/"));
    assertFalse(html.contains("workflow-editor-spike"));

    Resource[] scripts =
        new PathMatchingResourcePatternResolver()
            .getResources("classpath*:workbench-ui/assets/*.js");
    Resource[] styles =
        new PathMatchingResourcePatternResolver()
            .getResources("classpath*:workbench-ui/assets/*.css");

    assertTrue(scripts.length > 0, "packaged frontend contains no JavaScript asset");
    assertTrue(styles.length > 0, "packaged frontend contains no stylesheet asset");
    assertTrue(Arrays.stream(scripts).allMatch(ProductionFrontendPackagingTest::hasHashedName));
    assertTrue(Arrays.stream(styles).allMatch(ProductionFrontendPackagingTest::hasHashedName));
  }

  private static boolean hasHashedName(Resource resource) {
    return resource.getFilename() != null
        && resource.getFilename().matches(HASHED_ASSET_PATTERN);
  }
}
