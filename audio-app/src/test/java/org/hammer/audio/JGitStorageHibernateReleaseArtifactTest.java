package org.hammer.audio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

class JGitStorageHibernateReleaseArtifactTest {

  @Test
  void releasedJGitStorageHibernateCoreArtifactIsOnTestClasspath() {
    assumeTrue(
        Boolean.getBoolean("jgitStorageHibernateArtifactCheck"),
        "Enable with -DjgitStorageHibernateArtifactCheck=true");
    assertDoesNotThrow(
        () ->
            Class.forName(
                "io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory"));
    assertDoesNotThrow(
        () -> Class.forName("io.github.carstenartur.jgit.storage.hibernate.RepositoryName"));
    assertDoesNotThrow(
        () ->
            Class.forName(
                "io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider"));
  }
}
