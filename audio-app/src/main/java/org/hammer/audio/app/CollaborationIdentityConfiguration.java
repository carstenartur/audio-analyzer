package org.hammer.audio.app;

import java.time.Clock;
import org.hammer.audio.infrastructure.workflow.collaboration.store.HibernateCollaborationIdentityStore;
import org.hammer.audio.workflow.collaboration.identity.CollaborationIdentityStore;
import org.hammer.audio.workflow.collaboration.identity.InMemoryCollaborationIdentityStore;
import org.hammer.audio.workflow.editor.http.CollaborationIdentityService;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses durable identity storage whenever durable collaboration is selected. */
@Configuration
public class CollaborationIdentityConfiguration {
  /** Uses the shared SessionFactory; non-durable collaboration keeps identities in memory. */
  @Bean
  public CollaborationIdentityStore collaborationIdentityStore(
      @Qualifier("workflowPersistenceSessionFactory") ObjectProvider<SessionFactory> factories) {
    SessionFactory factory = factories.getIfAvailable();
    return factory == null
        ? new InMemoryCollaborationIdentityStore()
        : new HibernateCollaborationIdentityStore(factory);
  }

  /** Account-handle disclosure is opt-in and cannot be changed by a browser request. */
  @Bean
  public CollaborationIdentityService collaborationIdentityService(
      CollaborationIdentityStore store,
      @Value("${workbench.collaboration.identity.show-account-handles:false}") boolean handles) {
    return new CollaborationIdentityService(store, handles, Clock.systemUTC());
  }
}
