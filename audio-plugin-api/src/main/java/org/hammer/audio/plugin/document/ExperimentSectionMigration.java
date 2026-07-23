package org.hammer.audio.plugin.document;

/** Explicit deterministic migration between adjacent plugin-section schema versions. */
public interface ExperimentSectionMigration {

  /** Source schema version accepted by this migration. */
  int fromVersion();

  /** Target schema version produced by this migration. */
  int toVersion();

  /** Migrate a bounded section value without side effects. */
  DocumentValue migrate(DocumentValue source);
}
