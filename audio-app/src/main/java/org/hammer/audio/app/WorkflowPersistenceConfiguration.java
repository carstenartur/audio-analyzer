package org.hammer.audio.app;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import javax.sql.DataSource;
import org.hammer.audio.infrastructure.workflow.store.FileSystemJGitVersionedWorkflowStore;
import org.hammer.audio.infrastructure.workflow.store.HibernateJGitVersionedWorkflowStore;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Selects an explicit workflow persistence implementation for the workbench. */
@Configuration
public class WorkflowPersistenceConfiguration {

  private static final String PERSISTENCE_MODE_PROPERTY = "workbench.persistence.mode";

  /** Builds the shared Hibernate context over the Spring-managed DataSource. */
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = PERSISTENCE_MODE_PROPERTY, havingValue = "hibernate")
  public HibernateSessionFactoryProvider workflowHibernateSessionFactoryProvider(
      DataSource dataSource,
      Environment environment,
      List<WorkflowPersistenceEntityContributor> entityContributors,
      @Value("${workbench.persistence.schema-action:validate}") String schemaAction) {
    requireExplicitDataSource(environment);
    Properties properties = new Properties();
    properties.put("hibernate.connection.datasource", dataSource);
    properties.setProperty("hibernate.hbm2ddl.auto", requireNotBlank(schemaAction, "schemaAction"));
    properties.setProperty("hibernate.show_sql", "false");
    properties.setProperty("hibernate.format_sql", "false");
    return new HibernateSessionFactoryProvider(
        properties, additionalAnnotatedClasses(entityContributors));
  }

  /**
   * Exposes the application-managed SessionFactory for the JGit store and future domain entities.
   */
  @Bean(destroyMethod = "")
  @ConditionalOnProperty(name = PERSISTENCE_MODE_PROPERTY, havingValue = "hibernate")
  public SessionFactory workflowPersistenceSessionFactory(
      HibernateSessionFactoryProvider provider) {
    return provider.getSessionFactory();
  }

  /** Opens the production database-backed logical JGit repository. */
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = PERSISTENCE_MODE_PROPERTY, havingValue = "hibernate")
  public VersionedWorkflowStore hibernateVersionedWorkflowStore(
      @Qualifier("workflowPersistenceSessionFactory") SessionFactory sessionFactory,
      @Value("${workbench.persistence.repository-name:audio-analyzer-workflows}")
          String repositoryName) {
    return new HibernateJGitVersionedWorkflowStore(
        sessionFactory, requireNotBlank(repositoryName, "repositoryName"));
  }

  /** Opens the explicitly selected filesystem store for tests and local demonstrations only. */
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = PERSISTENCE_MODE_PROPERTY, havingValue = "filesystem")
  public VersionedWorkflowStore fileSystemVersionedWorkflowStore(
      @Value("${workbench.persistence.filesystem.path}") String repositoryPath) {
    return new FileSystemJGitVersionedWorkflowStore(
        Path.of(requireNotBlank(repositoryPath, "repositoryPath")));
  }

  private static List<Class<?>> additionalAnnotatedClasses(
      List<WorkflowPersistenceEntityContributor> entityContributors) {
    Objects.requireNonNull(entityContributors, "entityContributors");
    Set<Class<?>> annotatedClasses = new LinkedHashSet<>();
    for (WorkflowPersistenceEntityContributor contributor : entityContributors) {
      Collection<Class<?>> contributedClasses =
          Objects.requireNonNull(contributor, "entityContributor").annotatedClasses();
      for (Class<?> contributedClass :
          Objects.requireNonNull(contributedClasses, "annotatedClasses")) {
        annotatedClasses.add(Objects.requireNonNull(contributedClass, "annotatedClass"));
      }
    }
    return List.copyOf(annotatedClasses);
  }

  private static void requireExplicitDataSource(Environment environment) {
    String jdbcUrl = environment.getProperty("spring.datasource.url");
    String jndiName = environment.getProperty("spring.datasource.jndi-name");
    if (isBlank(jdbcUrl) && isBlank(jndiName)) {
      throw new IllegalStateException(
          PERSISTENCE_MODE_PROPERTY
              + "=hibernate requires spring.datasource.url "
              + "or spring.datasource.jndi-name");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String requireNotBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
