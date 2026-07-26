package org.hammer.audio.experiment.document;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.hammer.audio.plugin.AudioAnalyzerPlugin;
import org.hammer.audio.plugin.PluginDescriptor;
import org.hammer.audio.plugin.document.DocumentDiagnostic;
import org.hammer.audio.plugin.document.DocumentValidationResult;
import org.hammer.audio.plugin.document.DocumentValue;
import org.hammer.audio.plugin.document.ExperimentDocumentContribution;
import org.hammer.audio.plugin.document.ExperimentSectionMigration;

/**
 * Installed-plugin catalog for safe experiment-document inspection, migration and normalization.
 */
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.LooseCoupling", "PMD.UseExplicitTypes"})
public final class PluginDocumentCatalog {

  private final Map<String, RegisteredPlugin> plugins;
  private final LocalJsonSchemaValidator schemaValidator = new LocalJsonSchemaValidator();

  /** Build a catalog and reject duplicate plugin or section identities. */
  public PluginDocumentCatalog(Collection<? extends AudioAnalyzerPlugin> installedPlugins) {
    Objects.requireNonNull(installedPlugins, "installedPlugins");
    TreeMap<String, RegisteredPlugin> collected = new TreeMap<>();
    for (AudioAnalyzerPlugin plugin : installedPlugins) {
      Objects.requireNonNull(plugin, "plugin");
      PluginDescriptor descriptor =
          Objects.requireNonNull(plugin.descriptor(), "plugin descriptor");
      TreeMap<String, ExperimentDocumentContribution> sections = new TreeMap<>();
      for (ExperimentDocumentContribution contribution : plugin.experimentDocumentContributions()) {
        validateContribution(contribution);
        ExperimentDocumentContribution previous =
            sections.put(contribution.sectionId(), contribution);
        if (previous != null) {
          throw new IllegalArgumentException(
              "Duplicate experiment document section "
                  + descriptor.id()
                  + "/"
                  + contribution.sectionId());
        }
      }
      RegisteredPlugin previous =
          collected.put(descriptor.id(), new RegisteredPlugin(descriptor, Map.copyOf(sections)));
      if (previous != null) {
        throw new IllegalArgumentException("Duplicate plugin id: " + descriptor.id());
      }
    }
    plugins = Map.copyOf(collected);
  }

  /** Return an empty catalog suitable for core-only inspection. */
  public static PluginDocumentCatalog empty() {
    return new PluginDocumentCatalog(List.of());
  }

  /**
   * Resolve installed plugins and return a non-mutating import preview.
   *
   * <p>The original document is never rewritten. The returned document is a normalized copy whose
   * migration provenance is explicit.
   */
  public ExperimentDocumentPreview preview(ExperimentDocument input, ExperimentDocumentCodec codec)
      throws ExperimentDocumentException {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(codec, "codec");
    ArrayList<DocumentDiagnostic> diagnostics = new ArrayList<>();
    ArrayList<String> migrations = new ArrayList<>();
    Set<SectionKey> required = requiredSections(input, diagnostics);
    TreeMap<String, Map<String, ExperimentDocument.PluginSection>> normalizedData = new TreeMap<>();

    for (var pluginEntry : input.pluginData().entrySet()) {
      String pluginId = pluginEntry.getKey();
      TreeMap<String, ExperimentDocument.PluginSection> normalizedSections = new TreeMap<>();
      RegisteredPlugin installed = plugins.get(pluginId);
      for (var sectionEntry : pluginEntry.getValue().entrySet()) {
        String sectionId = sectionEntry.getKey();
        SectionKey key = new SectionKey(pluginId, sectionId);
        boolean requiredSection = required.contains(key);
        String pointer = sectionPointer(pluginId, sectionId);
        ExperimentDocument.PluginSection section = sectionEntry.getValue();
        if (installed == null) {
          diagnostics.add(
              diagnostic(
                  requiredSection
                      ? DocumentDiagnostic.Severity.ERROR
                      : DocumentDiagnostic.Severity.WARNING,
                  pointer,
                  "missing-plugin",
                  "Plugin is not installed; section is preserved without interpretation"));
          normalizedSections.put(sectionId, section);
          continue;
        }
        ExperimentDocumentContribution contribution = installed.sections().get(sectionId);
        if (contribution == null) {
          diagnostics.add(
              diagnostic(
                  requiredSection
                      ? DocumentDiagnostic.Severity.ERROR
                      : DocumentDiagnostic.Severity.WARNING,
                  pointer,
                  "missing-section",
                  "Installed plugin does not provide this section; data is preserved"));
          normalizedSections.put(sectionId, section);
          continue;
        }
        ExperimentDocument.PluginSection normalized =
            normalizeSection(
                contribution,
                section,
                pointer,
                requiredSection || contribution.requiredForExecution(),
                input.experiment().sourceMode(),
                diagnostics,
                migrations);
        normalizedSections.put(sectionId, normalized);
      }
      normalizedData.put(pluginId, Map.copyOf(normalizedSections));
    }

    for (SectionKey key : required) {
      Map<String, ExperimentDocument.PluginSection> sections =
          input.pluginData().get(key.pluginId());
      if (sections == null || !sections.containsKey(key.sectionId())) {
        diagnostics.add(
            diagnostic(
                DocumentDiagnostic.Severity.ERROR,
                sectionPointer(key.pluginId(), key.sectionId()),
                "required-section-absent",
                "Required plugin section is absent from the document"));
      }
    }

    ExperimentDocument normalized = withPluginData(input, normalizedData, migrations);
    ExperimentDocument canonical = codec.decode(codec.encode(normalized));
    boolean hasErrors =
        diagnostics.stream().anyMatch(item -> item.severity() == DocumentDiagnostic.Severity.ERROR);
    boolean compatibilityWarning =
        diagnostics.stream()
            .anyMatch(
                item ->
                    item.code().startsWith("missing-")
                        || item.code().startsWith("future-")
                        || item.code().startsWith("algorithm-"));
    return new ExperimentDocumentPreview(
        canonical,
        canonical.provenance().canonicalSha256(),
        diagnostics,
        migrations,
        !hasErrors,
        hasErrors || compatibilityWarning);
  }

  private ExperimentDocument.PluginSection normalizeSection(
      ExperimentDocumentContribution contribution,
      ExperimentDocument.PluginSection original,
      String pointer,
      boolean required,
      String sourceMode,
      List<DocumentDiagnostic> diagnostics,
      List<String> migrations) {
    if (!contribution.supportedSourceModes().isEmpty()
        && !contribution.supportedSourceModes().contains(sourceMode)) {
      diagnostics.add(
          diagnostic(
              required ? DocumentDiagnostic.Severity.ERROR : DocumentDiagnostic.Severity.WARNING,
              pointer,
              "source-mode-incompatible",
              "Plugin section does not support source mode " + sourceMode));
    }
    if (original.schemaVersion() > contribution.schemaVersion()) {
      diagnostics.add(
          diagnostic(
              required ? DocumentDiagnostic.Severity.ERROR : DocumentDiagnostic.Severity.WARNING,
              pointer + "/schemaVersion",
              "future-schema-version",
              "Plugin section version is newer than the installed contribution"));
      return original;
    }
    DocumentValue migratedValue = original.data();
    int version = original.schemaVersion();
    while (version < contribution.schemaVersion()) {
      ExperimentSectionMigration migration = migrationFrom(contribution.migrations(), version);
      if (migration == null || migration.toVersion() != version + 1) {
        diagnostics.add(
            diagnostic(
                DocumentDiagnostic.Severity.ERROR,
                pointer + "/schemaVersion",
                "migration-gap",
                "No explicit adjacent migration from schema version " + version));
        return original;
      }
      try {
        migratedValue = Objects.requireNonNull(migration.migrate(migratedValue), "migrated value");
      } catch (RuntimeException exception) {
        diagnostics.add(
            diagnostic(
                DocumentDiagnostic.Severity.ERROR,
                pointer,
                "migration-failed",
                "Plugin migration failed: " + exception.getClass().getSimpleName()));
        return original;
      }
      migrations.add(
          contribution.sectionId() + ":" + migration.fromVersion() + "->" + migration.toVersion());
      version = migration.toVersion();
    }
    diagnostics.addAll(
        schemaValidator.validate(
            contribution.schemaJson(),
            contribution.schemaSha256(),
            migratedValue,
            pointer + "/data"));
    DocumentValidationResult semantic;
    try {
      semantic = contribution.validateAndNormalize(migratedValue);
    } catch (RuntimeException exception) {
      diagnostics.add(
          diagnostic(
              DocumentDiagnostic.Severity.ERROR,
              pointer,
              "plugin-validator-failed",
              "Plugin validation failed: " + exception.getClass().getSimpleName()));
      return original;
    }
    diagnostics.addAll(semantic.diagnostics());
    if (!original.algorithmVersion().equals(contribution.algorithmVersion())) {
      diagnostics.add(
          diagnostic(
              required ? DocumentDiagnostic.Severity.ERROR : DocumentDiagnostic.Severity.WARNING,
              pointer + "/algorithmVersion",
              "algorithm-incompatible",
              "Document algorithm version differs from the installed contribution"));
    }
    return new ExperimentDocument.PluginSection(
        contribution.schemaVersion(), contribution.algorithmVersion(), semantic.normalizedValue());
  }

  private static Set<SectionKey> requiredSections(
      ExperimentDocument document, List<DocumentDiagnostic> diagnostics) {
    HashSet<SectionKey> required = new HashSet<>();
    for (ExperimentDocument.PluginRequirement requirement : document.requiredPlugins()) {
      for (String section : requirement.sections()) {
        required.add(new SectionKey(requirement.id(), section));
      }
    }
    return required;
  }

  private static ExperimentSectionMigration migrationFrom(
      List<ExperimentSectionMigration> migrations, int version) {
    ExperimentSectionMigration found = null;
    for (ExperimentSectionMigration migration : migrations) {
      if (migration.fromVersion() == version) {
        if (found != null) {
          throw new IllegalArgumentException("Duplicate migration from version " + version);
        }
        found = migration;
      }
    }
    return found;
  }

  private static ExperimentDocument withPluginData(
      ExperimentDocument input,
      Map<String, Map<String, ExperimentDocument.PluginSection>> pluginData,
      List<String> migrations) {
    ArrayList<String> notes = new ArrayList<>(input.provenance().migrationNotes());
    notes.addAll(migrations);
    ExperimentDocument.Provenance provenance =
        new ExperimentDocument.Provenance(
            input.provenance().creatorDisplayName(),
            input.provenance().verifiedAccount(),
            input.provenance().createdAt(),
            input.provenance().modifiedAt(),
            input.provenance().softwareVersion(),
            "",
            notes);
    return new ExperimentDocument(
        input.schema(),
        input.format(),
        input.formatVersion(),
        input.experiment(),
        input.workflow(),
        input.profiles(),
        input.requiredPlugins(),
        pluginData,
        input.assets(),
        input.outputs(),
        provenance);
  }

  private static void validateContribution(ExperimentDocumentContribution contribution) {
    Objects.requireNonNull(contribution, "document contribution");
    ExperimentDocument.requireIdentifier(contribution.sectionId(), "section id");
    if (contribution.schemaVersion() < 1) {
      throw new IllegalArgumentException("schemaVersion must be positive");
    }
    ExperimentDocument.requireNonBlank(contribution.algorithmVersion(), "algorithmVersion");
    ExperimentDocument.requireNonBlank(contribution.name(), "contribution name");
    ExperimentDocument.requireNonBlank(contribution.description(), "contribution description");
    ExperimentDocument.requireNonBlank(contribution.schemaId(), "schemaId");
    ExperimentDocument.requireNonBlank(contribution.schemaJson(), "schemaJson");
    ExperimentDocument.requireSha256(contribution.schemaSha256(), "schemaSha256");
    HashMap<Integer, ExperimentSectionMigration> migrations = new HashMap<>();
    for (ExperimentSectionMigration migration : contribution.migrations()) {
      Objects.requireNonNull(migration, "migration");
      if (migration.fromVersion() < 1 || migration.toVersion() != migration.fromVersion() + 1) {
        throw new IllegalArgumentException("Migrations must advance exactly one positive version");
      }
      if (migrations.put(migration.fromVersion(), migration) != null) {
        throw new IllegalArgumentException(
            "Duplicate migration from version " + migration.fromVersion());
      }
    }
  }

  private static String sectionPointer(String pluginId, String sectionId) {
    return DocumentValueJson.pointer(DocumentValueJson.pointer("/pluginData", pluginId), sectionId);
  }

  private static DocumentDiagnostic diagnostic(
      DocumentDiagnostic.Severity severity, String pointer, String code, String message) {
    return new DocumentDiagnostic(severity, pointer, code, message);
  }

  private record RegisteredPlugin(
      PluginDescriptor descriptor, Map<String, ExperimentDocumentContribution> sections) {
    // Immutable installed plugin descriptor and section registry.
  }

  private record SectionKey(String pluginId, String sectionId) {
    // Immutable namespaced section identity.
  }
}
