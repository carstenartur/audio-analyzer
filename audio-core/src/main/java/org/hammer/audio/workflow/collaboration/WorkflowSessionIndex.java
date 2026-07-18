package org.hammer.audio.workflow.collaboration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Small mutable index whose lifecycle and synchronization are owned by one session lock. */
final class WorkflowSessionIndex<K, V> {

  private final Map<K, V> entries = new HashMap<>();

  V get(K key) {
    return entries.get(Objects.requireNonNull(key, "key"));
  }

  V put(K key, V value) {
    return entries.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
  }

  V putIfAbsent(K key, V value) {
    return entries.putIfAbsent(
        Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
  }

  V remove(K key) {
    return entries.remove(Objects.requireNonNull(key, "key"));
  }

  List<V> values() {
    return List.copyOf(entries.values());
  }
}
