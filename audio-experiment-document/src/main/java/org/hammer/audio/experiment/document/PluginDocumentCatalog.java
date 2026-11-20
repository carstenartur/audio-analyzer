package org.hammer.audio.experiment.document;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.hammer.audio.plugin.AudioAnalyzerPlugin;
import org.hammer.audio.plugin.PluginDescriptor;
import org.hammer.audio.plugin.document.DocumentDiagnostic;
import org.hammer.audio.plugin.document.DocumentValidationResult;
import org.hammer.audio.plugin.document.DocumentValue;
import org.hammer.audio.plugin.document.ExperimentDocumentContribution;
import org.hammer.audio.plugin.document.ExperimentSectionMigration;

/** Installed, trusted plugin sections with bounded, transactional migration and normalization. */
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.LooseCoupling", "PMD.UseExplicitTypes"})
public final class PluginDocumentCatalog {

  private static final int MAX_SECTIONS = 64;
  private final Map<String, RegisteredPlugin> plugins;
  private final LocalJsonSchemaValidator schemaValidator = new LocalJsonSchemaValidator();

  /** Snapshot and validate registrations; duplicate identities are rejected deterministically. */
  public PluginDocumentCatalog(Collection<? extends AudioAnalyzerPlugin> installedPlugins) {
    Objects.requireNonNull(installedPlugins, "installedPlugins");
    TreeMap<String, RegisteredPlugin> collected = new TreeMap<>();
    for (AudioAnalyzerPlugin plugin : installedPlugins) {
      RegisteredPlugin registered =
          PluginCallbackRunner.invoke(() -> register(plugin, plugin.descriptor()));
      String id = registered.descriptor().id();
      if (collected.putIfAbsent(id, registered) != null) {
        throw new IllegalArgumentException("Duplicate plugin id: " + id);
      }
    }
    plugins = Map.copyOf(collected);
  }

  /** Validate document contributions at plugin loading without reevaluating the descriptor. */
  public static void validateRegistration(AudioAnalyzerPlugin plugin, PluginDescriptor descriptor) {
    PluginCallbackRunner.invoke(() -> register(plugin, descriptor));
  }

  /** Return an empty catalog for inspection without installed plugins. */
  public static PluginDocumentCatalog empty() {
    return new PluginDocumentCatalog(List.of());
  }

  /** Preview a bounded document, preserving any section whose interpretation fails. */
  public ExperimentDocumentPreview preview(ExperimentDocument input, ExperimentDocumentCodec codec)
      throws ExperimentDocumentException {
    Objects.requireNonNull(input, "input");
    ArrayList<DocumentDiagnostic> diagnostics = new ArrayList<>();
    ArrayList<String> migrations = new ArrayList<>();
    Set<SectionKey> required = requiredSections(input, diagnostics);
    TreeMap<String, Map<String, ExperimentDocument.PluginSection>> data = new TreeMap<>();
    int sectionCount = input.pluginData().values().stream().mapToInt(Map::size).sum();
    if (sectionCount > MAX_SECTIONS) {
      diagnostics.add(
          error(
              "/pluginData",
              "plugin-section-limit",
              "At most 64 plugin sections may be interpreted"));
    }
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    for (var pluginEntry : input.pluginData().entrySet()) {
      String pluginId = pluginEntry.getKey();
      RegisteredPlugin installed = plugins.get(pluginId);
      TreeMap<String, ExperimentDocument.PluginSection> sections = new TreeMap<>();
      for (var sectionEntry : pluginEntry.getValue().entrySet()) {
        String sectionId = sectionEntry.getKey();
        ExperimentDocument.PluginSection original = sectionEntry.getValue();
        String pointer = sectionPointer(pluginId, sectionId);
        boolean requiredSection = required.contains(new SectionKey(pluginId, sectionId));
        sections.put(sectionId, original);
        RegisteredSection contribution =
            installed == null ? null : installed.sections().get(sectionId);
        if (contribution == null) {
          diagnostics.add(
              new DocumentDiagnostic(
                  requiredSection
                      ? DocumentDiagnostic.Severity.ERROR
                      : DocumentDiagnostic.Severity.WARNING,
                  pointer,
                  installed == null ? "missing-plugin" : "missing-section",
                  "Section is unavailable and preserved without interpretation"));
        } else if (sectionCount <= MAX_SECTIONS) {
          if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
            diagnostics.add(
                error(
                    pointer,
                    "plugin-timeout",
                    "Document validation budget exhausted; section preserved"));
            continue;
          }
          try {
            SectionResult result =
                PluginCallbackRunner.invoke(
                    () ->
                        normalizeSection(
                            contribution,
                            original,
                            pointer,
                            requiredSection || contribution.required(),
                            input.experiment().sourceMode()));
            diagnostics.addAll(result.diagnostics());
            migrations.addAll(result.migrations());
            sections.put(sectionId, result.section());
          } catch (PluginCallbackRunner.Failure failure) {
            diagnostics.add(error(pointer, failure.code(), failure.getMessage()));
          }
        }
      }
      data.put(pluginId, Map.copyOf(sections));
    }
    inspectAbsentSections(input, required, diagnostics);
    ExperimentDocument normalized = withPluginData(input, data, migrations);
    ExperimentDocument canonical;
    try {
      canonical = codec.decode(codec.encode(normalized));
    } catch (ExperimentDocumentException exception) {
      diagnostics.add(
          error(
              "/pluginData",
              "plugin-output-limit",
              "Normalized plugin data exceeds document limits; original preserved"));
      migrations.clear();
      canonical = codec.decode(codec.encode(input));
    }
    boolean errors = hasErrors(diagnostics);
    boolean incompatible =
        diagnostics.stream()
            .anyMatch(
                item ->
                    item.code().startsWith("missing-")
                        || item.code().startsWith("future-")
                        || item.code().endsWith("-incompatible"));
    return new ExperimentDocumentPreview(
        canonical,
        canonical.provenance().canonicalSha256(),
        diagnostics,
        migrations,
        !errors,
        errors || incompatible);
  }

  private static void inspectAbsentSections(
      ExperimentDocument input, Set<SectionKey> required, List<DocumentDiagnostic> diagnostics) {
    for (SectionKey key : required) {
      if (!input.pluginData().getOrDefault(key.pluginId(), Map.of()).containsKey(key.sectionId())) {
        diagnostics.add(
            error(
                sectionPointer(key.pluginId(), key.sectionId()),
                "required-section-absent",
                "Required section is absent"));
      }
    }
  }

  /** Installed section metadata and schema-derived parameter tables for import previews. */
  public List<SectionDescription> descriptions() {
    ArrayList<SectionDescription> result = new ArrayList<>();
    new TreeMap<>(plugins)
        .forEach(
            (pluginId, plugin) ->
                new TreeMap<>(plugin.sections())
                    .forEach(
                        (sectionId, section) ->
                            result.add(
                                new SectionDescription(
                                    pluginId,
                                    plugin.descriptor().version(),
                                    sectionId,
                                    section.version(),
                                    section.algorithm(),
                                    section.name(),
                                    section.description(),
                                    section.required(),
                                    section.schemaId(),
                                    section.schemaDigest(),
                                    section.sourceModes(),
                                    PluginDocumentTestKit.parameters(section.schema())))));
    return List.copyOf(result);
  }

  private SectionResult normalizeSection(
      RegisteredSection contribution,
      ExperimentDocument.PluginSection original,
      String pointer,
      boolean required,
      String sourceMode) {
    ArrayList<DocumentDiagnostic> diagnostics = new ArrayList<>();
    ArrayList<String> migrations = new ArrayList<>();
    DocumentDiagnostic.Severity severity =
        required ? DocumentDiagnostic.Severity.ERROR : DocumentDiagnostic.Severity.WARNING;
    if (!contribution.sourceModes().isEmpty() && !contribution.sourceModes().contains(sourceMode)) {
      diagnostics.add(
          new DocumentDiagnostic(
              severity,
              pointer,
              "source-mode-incompatible",
              "Section does not support this source mode"));
      return preserved(original, diagnostics);
    }
    if (original.schemaVersion() > contribution.version()) {
      diagnostics.add(
          new DocumentDiagnostic(
              severity,
              pointer + "/schemaVersion",
              "future-schema-version",
              "Newer section version is preserved"));
      return preserved(original, diagnostics);
    }
    if (!original.algorithmVersion().equals(contribution.algorithm())) {
      diagnostics.add(
          new DocumentDiagnostic(
              severity,
              pointer + "/algorithmVersion",
              "algorithm-incompatible",
              "Algorithm compatibility identifier differs"));
      return preserved(original, diagnostics);
    }
    DocumentValue value = original.data();
    String dataPointer = pointer + "/data";
    try {
      DocumentValueBounds.check(value, dataPointer);
      int version = original.schemaVersion();
      while (version < contribution.version()) {
        RegisteredMigration migration = contribution.migrations().get(version);
        if (migration == null) {
          diagnostics.add(
              error(
                  pointer + "/schemaVersion",
                  "migration-gap",
                  "No explicit adjacent migration from " + version));
          return preserved(original, diagnostics);
        }
        try {
          value = Objects.requireNonNull(migration.handler().apply(value), "migration result");
          DocumentValueBounds.check(value, dataPointer);
        } catch (RuntimeException exception) {
          diagnostics.add(
              error(
                  pointer,
                  "migration-failed",
                  "Plugin migration failed: " + exception.getClass().getSimpleName()));
          return preserved(original, diagnostics);
        }
        migrations.add(contribution.id() + ":" + version + "->" + (version + 1));
        version++;
      }
      diagnostics.addAll(
          schemaValidator.validate(
              contribution.schema(), contribution.schemaDigest(), value, dataPointer));
      if (hasErrors(diagnostics)) {
        return preserved(original, diagnostics);
      }
      DocumentValidationResult semantic =
          Objects.requireNonNull(contribution.handler().apply(value), "plugin validation result");
      if (semantic.diagnostics().size() > 128) {
        throw new IllegalArgumentException("Plugin returned more than 128 diagnostics");
      }
      for (DocumentDiagnostic diagnostic : semantic.diagnostics()) {
        if (diagnostic.pointer().length() > 4096
            || diagnostic.code().length() > 128
            || diagnostic.message().length() > 8192) {
          throw new IllegalArgumentException("Plugin diagnostic exceeds the bounded text limits");
        }
        String relative = diagnostic.pointer();
        if (!relative.startsWith("/")) {
          throw new IllegalArgumentException(
              "Plugin diagnostic must be a data-relative JSON Pointer");
        }
        String scoped = dataPointer + ("/".equals(relative) ? "" : relative);
        diagnostics.add(
            new DocumentDiagnostic(
                diagnostic.severity(), scoped, diagnostic.code(), diagnostic.message()));
      }
      DocumentValueBounds.check(semantic.normalizedValue(), dataPointer);
      diagnostics.addAll(
          schemaValidator.validate(
              contribution.schema(),
              contribution.schemaDigest(),
              semantic.normalizedValue(),
              dataPointer));
      if (hasErrors(diagnostics)) {
        return preserved(original, diagnostics);
      }
      return new SectionResult(
          new ExperimentDocument.PluginSection(
              contribution.version(), contribution.algorithm(), semantic.normalizedValue()),
          List.copyOf(diagnostics),
          List.copyOf(migrations));
    } catch (ExperimentDocumentException exception) {
      diagnostics.add(error(exception.pointer(), exception.code(), exception.getMessage()));
      return preserved(original, diagnostics);
    }
  }

  private Set<SectionKey> requiredSections(
      ExperimentDocument document, List<DocumentDiagnostic> diagnostics) {
    HashSet<SectionKey> required = new HashSet<>();
    for (int index = 0; index < document.requiredPlugins().size(); index++) {
      ExperimentDocument.PluginRequirement requirement = document.requiredPlugins().get(index);
      RegisteredPlugin installed = plugins.get(requirement.id());
      if (installed == null && requirement.sections().isEmpty()) {
        diagnostics.add(
            error(
                "/requiredPlugins/" + index, "missing-plugin", "Required plugin is not installed"));
      } else if (installed != null
          && !PluginVersionRange.matches(
              requirement.versionRange(), installed.descriptor().version())) {
        diagnostics.add(
            error(
                "/requiredPlugins/" + index + "/versionRange",
                "package-incompatible",
                "Required plugin package version mismatch for "
                    + requirement.id()
                    + ": required "
                    + requirement.versionRange()
                    + ", installed "
                    + installed.descriptor().version()));
      }
      for (String section : requirement.sections()) {
        required.add(new SectionKey(requirement.id(), section));
      }
    }
    return required;
  }

  private static RegisteredPlugin register(
      AudioAnalyzerPlugin plugin, PluginDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "plugin descriptor");
    ExperimentDocument.requireIdentifier(descriptor.id(), "plugin id");
    TreeMap<String, RegisteredSection> sections = new TreeMap<>();
    List<ExperimentDocumentContribution> contributions =
        List.copyOf(plugin.experimentDocumentContributions());
    if (contributions.size() > MAX_SECTIONS) {
      throw new IllegalArgumentException("At most 64 sections may be registered per plugin");
    }
    for (ExperimentDocumentContribution contribution : contributions) {
      RegisteredSection section = snapshot(contribution);
      if (sections.putIfAbsent(section.id(), section) != null) {
        throw new IllegalArgumentException(
            "Duplicate experiment document section " + descriptor.id() + "/" + section.id());
      }
    }
    return new RegisteredPlugin(descriptor, Map.copyOf(sections));
  }

  private static RegisteredSection snapshot(ExperimentDocumentContribution contribution) {
    String id = ExperimentDocument.requireIdentifier(contribution.sectionId(), "section id");
    int version = contribution.schemaVersion();
    if (version < 1) {
      throw new IllegalArgumentException("Section version must be positive");
    }
    String algorithm =
        ExperimentDocument.requireNonBlank(contribution.algorithmVersion(), "algorithmVersion");
    String name = ExperimentDocument.requireNonBlank(contribution.name(), "name");
    String description =
        ExperimentDocument.requireNonBlank(contribution.description(), "description");
    String schemaId = ExperimentDocument.requireNonBlank(contribution.schemaId(), "schemaId");
    String schema = ExperimentDocument.requireNonBlank(contribution.schemaJson(), "schemaJson");
    String digest = ExperimentDocument.requireSha256(contribution.schemaSha256(), "schemaSha256");
    TreeMap<Integer, RegisteredMigration> migrations = new TreeMap<>();
    for (ExperimentSectionMigration migration : List.copyOf(contribution.migrations())) {
      int from = migration.fromVersion();
      if (from < 1
          || from >= version
          || migration.toVersion() != from + 1
          || migrations.size() >= MAX_SECTIONS
          || migrations.putIfAbsent(from, new RegisteredMigration(migration::migrate)) != null) {
        throw new IllegalArgumentException(
            "Migrations must have unique adjacent positive versions below current version");
      }
    }
    List<DocumentDiagnostic> schemaDiagnostics =
        new LocalJsonSchemaValidator().validate(schema, digest, DocumentValue.nullValue(), "/");
    Set<String> schemaErrors =
        Set.of(
            "invalid-schema", "schema-ref", "schema-keyword", "schema-digest", "schema-too-large");
    if (schemaDiagnostics.stream().anyMatch(item -> schemaErrors.contains(item.code()))) {
      throw new IllegalArgumentException("Invalid bundled plugin schema: " + schemaDiagnostics);
    }
    return new RegisteredSection(
        id,
        version,
        algorithm,
        name,
        description,
        contribution.requiredForExecution(),
        schemaId,
        schema,
        digest,
        Set.copyOf(contribution.supportedSourceModes()),
        Map.copyOf(migrations),
        contribution::validateAndNormalize);
  }

  private static ExperimentDocument withPluginData(
      ExperimentDocument input,
      Map<String, Map<String, ExperimentDocument.PluginSection>> data,
      List<String> migrations) {
    ArrayList<String> notes = new ArrayList<>(input.provenance().migrationNotes());
    notes.addAll(migrations);
    ExperimentDocument.Provenance old = input.provenance();
    ExperimentDocument.Provenance provenance =
        new ExperimentDocument.Provenance(
            old.creatorDisplayName(),
            old.verifiedAccount(),
            old.createdAt(),
            old.modifiedAt(),
            old.softwareVersion(),
            "",
            notes,
            old.algorithmVersions(),
            old.sourceCommit(),
            old.sourceRun());
    return new ExperimentDocument(
        input.schema(),
        input.format(),
        input.formatVersion(),
        input.experiment(),
        input.workflow(),
        input.profiles(),
        input.requiredPlugins(),
        data,
        input.assets(),
        input.outputs(),
        provenance);
  }

  private static boolean hasErrors(List<DocumentDiagnostic> diagnostics) {
    return diagnostics.stream()
        .anyMatch(item -> item.severity() == DocumentDiagnostic.Severity.ERROR);
  }

  private static SectionResult preserved(
      ExperimentDocument.PluginSection original, List<DocumentDiagnostic> diagnostics) {
    return new SectionResult(original, List.copyOf(diagnostics), List.of());
  }

  private static String sectionPointer(String plugin, String section) {
    return DocumentValueJson.pointer(DocumentValueJson.pointer("/pluginData", plugin), section);
  }

  private static DocumentDiagnostic error(String pointer, String code, String message) {
    return new DocumentDiagnostic(DocumentDiagnostic.Severity.ERROR, pointer, code, message);
  }

  private record RegisteredPlugin(
      PluginDescriptor descriptor, Map<String, RegisteredSection> sections) {
    // Immutable value; record components carry the complete state.
  }

  private record RegisteredSection(
      String id,
      int version,
      String algorithm,
      String name,
      String description,
      boolean required,
      String schemaId,
      String schema,
      String schemaDigest,
      Set<String> sourceModes,
      Map<Integer, RegisteredMigration> migrations,
      Function<DocumentValue, DocumentValidationResult> handler) {
    // Immutable value; record components carry the complete state.
  }

  private record RegisteredMigration(Function<DocumentValue, DocumentValue> handler) {
    // Immutable value; record components carry the complete state.
  }

  private record SectionKey(String pluginId, String sectionId) {
    // Immutable value; record components carry the complete state.
  }

  private record SectionResult(
      ExperimentDocument.PluginSection section,
      List<DocumentDiagnostic> diagnostics,
      List<String> migrations) {
    // Immutable value; record components carry the complete state.
  }

  /**
   * Immutable registration details for schema-derived parameter/import previews.
   *
   * @param pluginId stable plugin namespace
   * @param packageVersion installed plugin package version
   * @param sectionId stable contribution-local identity
   * @param schemaVersion current parameter schema version
   * @param algorithmVersion algorithm compatibility identifier
   * @param name human-readable section name
   * @param description section purpose
   * @param required whether this contribution is required for execution
   * @param schemaId bundled schema identifier
   * @param schemaSha256 canonical bundled schema digest
   * @param sourceModes supported portable source modes
   * @param parameters schema-derived parameter documentation
   */
  public record SectionDescription(
      String pluginId,
      String packageVersion,
      String sectionId,
      int schemaVersion,
      String algorithmVersion,
      String name,
      String description,
      boolean required,
      String schemaId,
      String schemaSha256,
      Set<String> sourceModes,
      List<PluginDocumentTestKit.Parameter> parameters) {
    /* Copy collections from the trusted registration. */
    public SectionDescription {
      sourceModes = Set.copyOf(sourceModes);
      parameters = List.copyOf(parameters);
    }
  }
}
