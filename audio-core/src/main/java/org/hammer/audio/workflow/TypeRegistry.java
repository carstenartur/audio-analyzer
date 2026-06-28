package org.hammer.audio.workflow;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Registry of available data types and deterministic compatibility rules. */
public final class TypeRegistry {

  private final Map<String, DataType> types;
  private final Map<String, Set<String>> compatibilityBySource;

  private TypeRegistry(
      Map<String, DataType> types, Map<String, Set<String>> compatibilityBySource) {
    this.types = Map.copyOf(types);
    Map<String, Set<String>> immutableCompatibility = new ConcurrentHashMap<>();
    compatibilityBySource.forEach(
        (sourceType, compatibleTargets) ->
            immutableCompatibility.put(sourceType, Set.copyOf(compatibleTargets)));
    this.compatibilityBySource = Map.copyOf(immutableCompatibility);
  }

  public static TypeRegistry defaultRegistry() {
    Builder builder = builder();
    for (DataType type : DataTypes.builtIns()) {
      builder.register(type);
    }
    return builder.build();
  }

  public boolean isRegistered(DataType dataType) {
    return types.containsKey(dataType.id());
  }

  public boolean areCompatible(DataType sourceType, DataType targetType) {
    if (!isRegistered(sourceType) || !isRegistered(targetType)) {
      return false;
    }
    return compatibilityBySource.getOrDefault(sourceType.id(), Set.of()).contains(targetType.id());
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder for custom type registries and compatibility mappings. */
  public static final class Builder {
    private final Map<String, DataType> types = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> compatibilityBySource = new ConcurrentHashMap<>();

    private Builder() {
      // use TypeRegistry.builder()
    }

    public Builder register(DataType dataType) {
      types.put(dataType.id(), dataType);
      compatibilityBySource
          .computeIfAbsent(dataType.id(), key -> ConcurrentHashMap.newKeySet())
          .add(dataType.id());
      return this;
    }

    public Builder registerCompatibility(DataType sourceType, DataType targetType) {
      register(sourceType);
      register(targetType);
      compatibilityBySource
          .computeIfAbsent(sourceType.id(), key -> ConcurrentHashMap.newKeySet())
          .add(targetType.id());
      return this;
    }

    public TypeRegistry build() {
      return new TypeRegistry(types, compatibilityBySource);
    }
  }
}
