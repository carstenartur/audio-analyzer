package org.hammer.audio.infrastructure.workflow.collaboration.store;

import java.util.Objects;
import java.util.Optional;
import org.hammer.audio.workflow.collaboration.identity.CollaborationIdentity;
import org.hammer.audio.workflow.collaboration.identity.CollaborationIdentityStore;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

/** Stores identities in the same application-owned Hibernate context as durable collaboration. */
public final class HibernateCollaborationIdentityStore implements CollaborationIdentityStore {
  private final SessionFactory sessionFactory;

  public HibernateCollaborationIdentityStore(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
  }

  @Override
  public Optional<CollaborationIdentity> find(String actorId) {
    try (Session session = sessionFactory.openSession()) {
      CollaborationIdentityEntity entity = session.find(CollaborationIdentityEntity.class, actorId);
      return entity == null ? Optional.empty() : Optional.of(entity.toIdentity());
    }
  }

  @Override
  public void insert(CollaborationIdentity identity) {
    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      try {
        session.persist(CollaborationIdentityEntity.from(identity));
        transaction.commit();
      } finally {
        if (transaction.isActive()) {
          transaction.rollback();
        }
      }
    }
  }
}
