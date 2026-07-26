package org.hammer.audio.plugin.document;

import java.util.List;
import java.util.Set;

/**
 * Stable plugin contract for one namespaced section of a portable experiment document.
 *
 * <p>The host performs bounded JSON parsing, schema parsing, migration orchestration, canonical
 * serialization and hashing. Implementations validate only the already bounded section value and
 * must not open devices, read files, fetch URLs or execute an experiment.
 */
public interface ExperimentDocumentContribution {

  /** Stable section id unique within the declaring plugin. */
  String sectionId();

  /** Current positive schema version. */
  int schemaVersion();

  /** Algorithm-behaviour compatibility identifier, independent of plugin package version. */
  String algorithmVersion();

  /** Human-readable section name. */
  String name();

  /** Human-readable section description. */
  String description();

  /** Whether missing or incompatible data blocks experiment execution. */
  boolean requiredForExecution();

  /** Stable local schema identifier; it is never dereferenced as an arbitrary network URL. */
  String schemaId();

  /** Bundled JSON Schema text for the section's {@code data} value. */
  String schemaJson();

  /** Lower-case SHA-256 of the canonical bundled schema. */
  String schemaSha256();

  /** Explicit migrations. Each migration must advance exactly one supported version step. */
  default List<ExperimentSectionMigration> migrations() {
    return List.of();
  }

  /** Source modes for which this section is meaningful; empty means unrestricted. */
  default Set<String> supportedSourceModes() {
    return Set.of();
  }

  /** Perform side-effect-free semantic validation and deterministic normalization. */
  DocumentValidationResult validateAndNormalize(DocumentValue value);
}
