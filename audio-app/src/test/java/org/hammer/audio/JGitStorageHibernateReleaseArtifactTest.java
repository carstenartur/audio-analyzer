package org.hammer.audio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class JGitStorageHibernateReleaseArtifactTest {

  @Test
  void releasedJGitStorageHibernateCoreArtifactIsOnTestClasspath() {
    assertDoesNotThrow(
        () ->
            Class.forName(
                "io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory"));
    assertDoesNotThrow(
        () ->
            Class.forName(
                "io.github.carstenartur.jgit.storage.hibernate.RepositoryName"));
    assertDoesNotThrow(
        () ->
            Class.forName(
                "io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider"));
  }
}
