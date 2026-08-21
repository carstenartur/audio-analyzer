#!/usr/bin/env python3
"""Keep concurrent append recovery private while preserving deterministic coverage."""

from pathlib import Path


def replace_once(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {description} match, found {count}")
    return text.replace(old, new)


store_path = Path(
    "audio-app/src/main/java/org/hammer/audio/infrastructure/workflow/"
    "collaboration/store/HibernateWorkflowSessionStateStore.java"
)
store = store_path.read_text(encoding="utf-8")
store = replace_once(
    store,
    "  WorkflowSessionAppendResult recoverConcurrentAppend(\n",
    "  private WorkflowSessionAppendResult recoverConcurrentAppend(\n",
    "recovery method visibility",
)
store_path.write_text(store, encoding="utf-8")


test_path = Path(
    "audio-app/src/test/java/org/hammer/audio/infrastructure/workflow/collaboration/store/"
    "HibernateWorkflowSessionStateStorePostgreSqlConcurrencyTest.java"
)
test = test_path.read_text(encoding="utf-8")
test = replace_once(
    test,
    "import java.time.Instant;\n",
    "import java.lang.invoke.MethodHandle;\n"
    "import java.lang.invoke.MethodHandles;\n"
    "import java.lang.invoke.MethodType;\n"
    "import java.time.Instant;\n",
    "method-handle imports",
)
test = replace_once(
    test,
    '''                  return store.recoverConcurrentAppend(
                      command, new OptimisticLockException("simulated losing append"));
''',
    '''                  return invokeConcurrentAppendRecovery(
                      store,
                      command,
                      new OptimisticLockException("simulated losing append"));
''',
    "private recovery invocation",
)
test = replace_once(
    test,
    '''  private static void persistUncommittedWinner(
      Session session, WorkflowSessionAppendCommand command) {
''',
    '''  private static WorkflowSessionAppendResult invokeConcurrentAppendRecovery(
      HibernateWorkflowSessionStateStore store,
      WorkflowSessionAppendCommand command,
      RuntimeException failure) {
    try {
      return (WorkflowSessionAppendResult)
          ConcurrentAppendRecoveryHandle.HANDLE.invokeExact(store, command, failure);
    } catch (RuntimeException | Error exception) {
      throw exception;
    } catch (Throwable failureToInvoke) {
      throw new AssertionError("Could not invoke private concurrent append recovery", failureToInvoke);
    }
  }

  private static void persistUncommittedWinner(
      Session session, WorkflowSessionAppendCommand command) {
''',
    "private recovery test bridge",
)
test = replace_once(
    test,
    '''  @FunctionalInterface
  private interface StoreScenario {
    void run(HibernateWorkflowSessionStateStore store, SessionFactory sessionFactory);
  }
}
''',
    '''  private static final class ConcurrentAppendRecoveryHandle {
    private static final MethodHandle HANDLE = create();

    private static MethodHandle create() {
      try {
        return MethodHandles.privateLookupIn(
                HibernateWorkflowSessionStateStore.class, MethodHandles.lookup())
            .findVirtual(
                HibernateWorkflowSessionStateStore.class,
                "recoverConcurrentAppend",
                MethodType.methodType(
                    WorkflowSessionAppendResult.class,
                    WorkflowSessionAppendCommand.class,
                    RuntimeException.class));
      } catch (IllegalAccessException | NoSuchMethodException exception) {
        throw new ExceptionInInitializerError(exception);
      }
    }

    private ConcurrentAppendRecoveryHandle() {}
  }

  @FunctionalInterface
  private interface StoreScenario {
    void run(HibernateWorkflowSessionStateStore store, SessionFactory sessionFactory);
  }
}
''',
    "method handle holder",
)
test_path.write_text(test, encoding="utf-8")
