package org.hammer.audio.infrastructure.workflow.store;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.SearchEntities;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import org.hammer.audio.infrastructure.workflow.search.WorkflowSemanticPersistenceEntities;

/** Builds the same searchable Hibernate mapping shape used by production workflow persistence. */
final class SearchableWorkflowTestSessionFactory {

  private SearchableWorkflowTestSessionFactory() {
    throw new AssertionError("No instances");
  }

  static HibernateSessionFactoryProvider provider(
      Properties properties, Class<?>... additionalAnnotatedClasses) {
    Properties searchableProperties = new Properties();
    searchableProperties.putAll(Objects.requireNonNull(properties, "properties"));
    searchableProperties.putIfAbsent("hibernate.search.backend.type", "lucene");
    searchableProperties.putIfAbsent("hibernate.search.backend.directory.type", "local-heap");

    Set<Class<?>> entities = new LinkedHashSet<>();
    SearchEntities.annotatedClasses().forEach(entity -> entities.add(requireAnnotatedClass(entity)));
    WorkflowSemanticPersistenceEntities.annotatedClasses()
        .forEach(entity -> entities.add(requireAnnotatedClass(entity)));
    for (Class<?> additionalAnnotatedClass :
        Objects.requireNonNull(additionalAnnotatedClasses, "additionalAnnotatedClasses")) {
      entities.add(requireAnnotatedClass(additionalAnnotatedClass));
    }
    return new HibernateSessionFactoryProvider(searchableProperties, entities);
  }

  private static Class<?> requireAnnotatedClass(Class<?> annotatedClass) {
    return Objects.requireNonNull(annotatedClass, "annotatedClass");
  }
}
