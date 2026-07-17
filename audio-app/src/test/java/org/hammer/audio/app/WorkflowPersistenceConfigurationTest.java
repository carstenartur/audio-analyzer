package org.hammer.audio.app;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.hammer.audio.infrastructure.workflow.store.WorkflowPersistenceProbeEntity;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class WorkflowPersistenceConfigurationTest {

  @Test
  void contributorRegistersApplicationEntityInSharedSessionFactory() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:workflow-persistence-configuration;DB_CLOSE_DELAY=-1");

    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    WorkflowPersistenceEntityContributor contributor =
        () -> List.of(WorkflowPersistenceProbeEntity.class);
    beanFactory.registerSingleton("probePersistenceEntities", contributor);

    MockEnvironment environment =
        new MockEnvironment().withProperty("spring.datasource.url", dataSource.getURL());
    WorkflowPersistenceConfiguration configuration = new WorkflowPersistenceConfiguration();

    try (HibernateSessionFactoryProvider provider =
        configuration.workflowHibernateSessionFactoryProvider(
            dataSource,
            environment,
            beanFactory.getBeanProvider(WorkflowPersistenceEntityContributor.class),
            "create-drop")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      assertNotNull(sessionFactory.getMetamodel().entity(WorkflowPersistenceProbeEntity.class));
    }
  }
}
