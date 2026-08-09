package org.hammer.audio.infrastructure.workflow.store;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.SearchEntities;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.hammer.audio.infrastructure.workflow.search.WorkflowSemanticPersistenceEntities;

/** Builds the same searchable Hibernate mapping shape used by production workflow persistence. */
final class SearchableWorkflowTestSessionFactory {

  private SearchableWorkflowTestSessionFactory() {
    throw new AssertionError("No instances");
  }

  static HibernateSessionFactoryProvider provider(
      Properties properties, Class<?>... additionalAnnotatedClasses) {
    Properties searchableProperties = new Properties();
    searchableProperties.putAll(properties);
    searchableProperties.putIfAbsent("hibernate.search.backend.type", "lucene");
    searchableProperties.putIfAbsent("hibernate.search.backend.directory.type", "local-heap");

    List<Class<?>> entities = new ArrayList<>(SearchEntities.annotatedClasses());
    entities.addAll(WorkflowSemanticPersistenceEntities.annotatedClasses());
    entities.addAll(Arrays.asList(additionalAnnotatedClasses));
    return new HibernateSessionFactoryProvider(searchableProperties, entities);
  }
}
