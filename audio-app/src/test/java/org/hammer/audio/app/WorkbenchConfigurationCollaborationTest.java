package org.hammer.audio.app;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.hammer.audio.workflow.collaboration.WorkflowSessionEventHub;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class WorkbenchConfigurationCollaborationTest {

  @Test
  void durableStateStoreIsUsedWhenHibernateModeContributesIt() {
    WorkbenchConfiguration configuration = new WorkbenchConfiguration();
    WorkflowSessionEventHub eventHub = new WorkflowSessionEventHub();
    WorkflowSessionStateStore stateStore = mock(WorkflowSessionStateStore.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<WorkflowSessionStateStore> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(stateStore);
    when(stateStore.openSessions()).thenReturn(List.of());

    WorkflowSessionRegistry registry = configuration.workflowSessionRegistry(eventHub, provider);

    assertSame(eventHub, registry.eventHub());
    verify(stateStore).openSessions();
  }

  @Test
  void registryRemainsInMemoryWhenNoDurableStoreIsConfigured() {
    WorkbenchConfiguration configuration = new WorkbenchConfiguration();
    WorkflowSessionEventHub eventHub = new WorkflowSessionEventHub();
    @SuppressWarnings("unchecked")
    ObjectProvider<WorkflowSessionStateStore> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);

    WorkflowSessionRegistry registry = configuration.workflowSessionRegistry(eventHub, provider);

    assertSame(eventHub, registry.eventHub());
  }
}
