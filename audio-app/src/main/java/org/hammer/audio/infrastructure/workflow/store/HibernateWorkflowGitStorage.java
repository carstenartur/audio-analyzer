package org.hammer.audio.infrastructure.workflow.store;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import java.util.Objects;
import org.hibernate.SessionFactory;

/** Opens one logical Hibernate-backed Git repository from the shared persistence context. */
final class HibernateWorkflowGitStorage {

  private HibernateWorkflowGitStorage() {}

  static HibernateGitStorage open(SessionFactory sessionFactory, String repositoryName) {
    return new DefaultHibernateRepositoryFactory(
            Objects.requireNonNull(sessionFactory, "sessionFactory"))
        .open(new RepositoryName(repositoryName));
  }
}
