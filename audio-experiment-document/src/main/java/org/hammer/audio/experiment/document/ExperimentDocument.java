package org.hammer.audio.experiment.document;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.hammer.audio.plugin.document.DocumentValue;

/**
 * Immutable portable experiment setup and reproducibility contract.
 *
 * @param schema schema identifier
 * @param format stable document format identifier
 * @param formatVersion outer envelope version
 * @param experiment core experiment metadata
 * @param workflow canonical workflow payload
 * @param profiles portable core profile data
 * @param requiredPlugins required plugin declarations
 * @param pluginData namespaced plugin sections
 * @param assets bounded external asset references
 * @param outputs requested logical outputs
 * @param provenance creation and migration provenance
 */
public record ExperimentDocument(
    String schema,
    String format,
    int formatVersion,
    ExperimentInfo experiment,
    WorkflowPayload workflow,
    DocumentValue.ObjectValue profiles,
    List<PluginRequirement> requiredPlugins,
    Map<String, Map<String, PluginSection>> pluginData,
    List<AssetReference> assets,
    List<OutputRequest> outputs,
    Provenance provenance) {

  /** Validate and defensively copy all document sections. */
  public ExperimentDocument {
    schema = requireNonBlank(schema, "schema");
    format = requireNonBlank(format, "format");
    if (formatVersion < 1) {
      throw new IllegalArgumentException("formatVersion must be positive");
    }
    Objects.requireNonNull(experiment, "experiment");
    Objects.requireNonNull(workflow, "workflow");
    Objects.requireNonNull(profiles, "profiles");
    requiredPlugins = List.copyOf(Objects.requireNonNull(requiredPlugins, "requiredPlugins"));
    pluginData = immutablePluginData(pluginData);
    assets = List.copyOf(Objects.requireNonNull(assets, "assets"));
    outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
    Objects.requireNonNull(provenance, "provenance");
  }

  /** Return a fresh deeply immutable copy of plugin-owned sections. */
  @Override
  public Map<String, Map<String, PluginSection>> pluginData() {
    return immutablePluginData(pluginData);
  }

  /**
   * Core human-facing experiment metadata.
   *
   * @param id stable experiment id
   * @param name human-readable name
   * @param description experiment description
   * @param tags immutable tags
   * @param intent experiment lifecycle intent/type
   * @param sourceMode portable source mode
   * @param intendedDuration optional intended duration
   * @param applicationVersion creating application version
   */
  public record ExperimentInfo(
      String id,
      String name,
      String description,
      List<String> tags,
      String intent,
      String sourceMode,
      Duration intendedDuration,
      String applicationVersion) {

    /** Validate experiment metadata. */
    public ExperimentInfo {
      id = requireIdentifier(id, "experiment id");
      name = requireNonBlank(name, "experiment name");
      description = description == null ? "" : description;
      tags = normalizedStrings(tags, "tag");
      intent = requireIdentifier(intent, "experiment intent");
      sourceMode = requireIdentifier(sourceMode, "source mode");
      if (intendedDuration != null
          && (intendedDuration.isNegative() || intendedDuration.isZero())) {
        throw new IllegalArgumentException("intendedDuration must be positive");
      }
      applicationVersion = requireNonBlank(applicationVersion, "applicationVersion");
    }

    /** Return a fresh immutable copy of the experiment tags. */
    @Override
    public List<String> tags() {
      return List.copyOf(tags);
    }
  }

  /**
   * Canonical embedded workflow representation.
   *
   * @param format workflow representation identifier
   * @param formatVersion workflow representation version
   * @param content canonical workflow DSL
   * @param sha256 lower-case canonical SHA-256
   */
  public record WorkflowPayload(String format, int formatVersion, String content, String sha256) {

    /** Validate workflow payload identity and hash fields. */
    public WorkflowPayload {
      format = requireNonBlank(format, "workflow format");
      if (formatVersion < 1) {
        throw new IllegalArgumentException("workflow formatVersion must be positive");
      }
      content = requireNonBlank(content, "workflow content");
      sha256 = requireSha256(sha256, "workflow sha256");
    }
  }

  /**
   * Required plugin declaration.
   *
   * @param id stable plugin id
   * @param versionRange declared package compatibility range
   * @param sections required section ids
   */
  public record PluginRequirement(String id, String versionRange, List<String> sections) {

    /** Validate plugin requirement. */
    public PluginRequirement {
      id = requireIdentifier(id, "plugin id");
      versionRange = requireNonBlank(versionRange, "versionRange");
      sections = normalizedIdentifiers(sections, "plugin section id");
      if (sections.isEmpty()) {
        throw new IllegalArgumentException("required plugin must declare at least one section");
      }
    }

    /** Return a fresh immutable copy of required section identifiers. */
    @Override
    public List<String> sections() {
      return List.copyOf(sections);
    }
  }

  /**
   * One versioned plugin-owned section.
   *
   * @param schemaVersion plugin parameter schema version
   * @param algorithmVersion algorithm compatibility identifier
   * @param data plugin-owned bounded data
   */
  public record PluginSection(int schemaVersion, String algorithmVersion, DocumentValue data) {

    /** Validate plugin section metadata. */
    public PluginSection {
      if (schemaVersion < 1) {
        throw new IllegalArgumentException("plugin schemaVersion must be positive");
      }
      algorithmVersion = requireNonBlank(algorithmVersion, "algorithmVersion");
      Objects.requireNonNull(data, "data");
    }
  }

  /**
   * Portable reference to an external recording or dataset.
   *
   * @param id stable asset id
   * @param relativePath bounded relative path
   * @param mediaType expected media type
   * @param sizeBytes expected size
   * @param sha256 expected SHA-256
   */
  public record AssetReference(
      String id, String relativePath, String mediaType, long sizeBytes, String sha256) {

    /** Validate asset identity, path and digest. */
    public AssetReference {
      id = requireIdentifier(id, "asset id");
      relativePath = PortableNames.requireRelativePath(relativePath);
      mediaType = requireNonBlank(mediaType, "mediaType");
      if (sizeBytes < 0L) {
        throw new IllegalArgumentException("asset sizeBytes must not be negative");
      }
      sha256 = requireSha256(sha256, "asset sha256");
    }
  }

  /**
   * Requested logical output.
   *
   * @param id stable output id
   * @param mediaType requested media type
   * @param baseName portable basename without path separators
   */
  public record OutputRequest(String id, String mediaType, String baseName) {

    /** Validate output request. */
    public OutputRequest {
      id = requireIdentifier(id, "output id");
      mediaType = requireNonBlank(mediaType, "output mediaType");
      baseName = PortableNames.requireBaseName(baseName);
    }
  }

  /**
   * Portable document provenance.
   *
   * @param creatorDisplayName creator display identity
   * @param verifiedAccount optional verified account reference
   * @param createdAt creation instant
   * @param modifiedAt modification instant
   * @param softwareVersion software version
   * @param canonicalSha256 canonical document hash
   * @param migrationNotes immutable migration notes
   */
  public record Provenance(
      String creatorDisplayName,
      String verifiedAccount,
      Instant createdAt,
      Instant modifiedAt,
      String softwareVersion,
      String canonicalSha256,
      List<String> migrationNotes) {

    /** Validate provenance fields. */
    public Provenance {
      creatorDisplayName = requireNonBlank(creatorDisplayName, "creatorDisplayName");
      verifiedAccount = verifiedAccount == null ? "" : verifiedAccount;
      Objects.requireNonNull(createdAt, "createdAt");
      Objects.requireNonNull(modifiedAt, "modifiedAt");
      if (modifiedAt.isBefore(createdAt)) {
        throw new IllegalArgumentException("modifiedAt must not be before createdAt");
      }
      softwareVersion = requireNonBlank(softwareVersion, "softwareVersion");
      canonicalSha256 =
          canonicalSha256 == null || canonicalSha256.isBlank()
              ? ""
              : requireSha256(canonicalSha256, "canonicalSha256");
      migrationNotes = normalizedStrings(migrationNotes, "migration note");
    }

    /** Return a fresh immutable copy of migration notes. */
    @Override
    public List<String> migrationNotes() {
      return List.copyOf(migrationNotes);
    }
  }

  private static Map<String, Map<String, PluginSection>> immutablePluginData(
      Map<String, Map<String, PluginSection>> source) {
    Objects.requireNonNull(source, "pluginData");
    TreeMap<String, Map<String, PluginSection>> plugins = new TreeMap<>();
    source.forEach(
        (pluginId, sections) -> {
          String validPluginId = requireIdentifier(pluginId, "plugin data id");
          Objects.requireNonNull(sections, "plugin sections");
          TreeMap<String, PluginSection> copiedSections = new TreeMap<>();
          sections.forEach(
              (sectionId, section) ->
                  copiedSections.put(
                      requireIdentifier(sectionId, "plugin section id"),
                      Objects.requireNonNull(section, "plugin section")));
          plugins.put(validPluginId, Collections.unmodifiableMap(copiedSections));
        });
    return Collections.unmodifiableMap(plugins);
  }

  private static List<String> normalizedIdentifiers(List<String> values, String label) {
    ArrayList<String> normalized = new ArrayList<>();
    for (String value : Objects.requireNonNull(values, label + "s")) {
      normalized.add(requireIdentifier(value, label));
    }
    normalized.sort(String::compareTo);
    return List.copyOf(normalized);
  }

  private static List<String> normalizedStrings(List<String> values, String label) {
    ArrayList<String> normalized = new ArrayList<>();
    for (String value : Objects.requireNonNull(values, label + "s")) {
      normalized.add(requireNonBlank(value, label));
    }
    normalized.sort(String::compareTo);
    return List.copyOf(normalized);
  }

  static String requireIdentifier(String value, String label) {
    String checked = requireNonBlank(value, label);
    if (!checked.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
      throw new IllegalArgumentException(label + " is not a portable identifier: " + checked);
    }
    return checked;
  }

  static String requireSha256(String value, String label) {
    String checked = requireNonBlank(value, label).toLowerCase(java.util.Locale.ROOT);
    if (!checked.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(label + " must be a lower-case SHA-256");
    }
    return checked;
  }

  static String requireNonBlank(String value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
