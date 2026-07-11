package org.hammer.audio.workflow.store;

class InMemoryVersionedWorkflowStoreContractTest extends VersionedWorkflowStoreContractTest {

  @Override
  protected VersionedWorkflowStore createStore() {
    return new InMemoryVersionedWorkflowStore();
  }
}
