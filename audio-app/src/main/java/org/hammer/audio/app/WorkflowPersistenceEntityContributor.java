package org.hammer.audio.app;

import java.util.Collection;

/**
 * Contributes Audio Analyzer-specific entity mappings to the shared workflow persistence context.
 *
 * <p>The generic JGit entities remain owned by {@code jgit-storage-hibernate-core}. Application
 * modules use this hook only for their own workflow, collaboration and outbox entities.
 */
@FunctionalInterface
public interface WorkflowPersistenceEntityContributor {

  /**
   * Returns annotated entity classes to register in the application-managed Hibernate context.
   *
   * @return application-specific annotated classes
   */
  Collection<Class<?>> annotatedClasses();
}
