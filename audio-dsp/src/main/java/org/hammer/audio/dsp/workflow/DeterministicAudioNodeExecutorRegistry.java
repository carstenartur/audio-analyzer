package org.hammer.audio.dsp.workflow;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable registry mapping stable workflow node types to deterministic audio executors. */
final class DeterministicAudioNodeExecutorRegistry {

  private final Map<String, DeterministicAudioNodeExecutor> executorsByType;

  DeterministicAudioNodeExecutorRegistry(List<DeterministicAudioNodeExecutor> executors) {
    Objects.requireNonNull(executors, "executors");
    Map<String, DeterministicAudioNodeExecutor> registered = new ConcurrentHashMap<>();
    for (DeterministicAudioNodeExecutor executor : executors) {
      DeterministicAudioNodeExecutor required = Objects.requireNonNull(executor, "executor");
      String nodeType = required.nodeType();
      if (nodeType == null || nodeType.isBlank()) {
        throw new IllegalArgumentException("executor nodeType must not be blank");
      }
      if (registered.putIfAbsent(nodeType, required) != null) {
        throw new IllegalArgumentException("Duplicate deterministic executor for " + nodeType);
      }
    }
    executorsByType = Map.copyOf(registered);
  }

  static DeterministicAudioNodeExecutorRegistry standard() {
    return new DeterministicAudioNodeExecutorRegistry(
        List.of(new SyntheticSignalNodeExecutor(), new GainNodeExecutor()));
  }

  Optional<DeterministicAudioNodeExecutor> find(String nodeType) {
    return Optional.ofNullable(executorsByType.get(nodeType));
  }
}
