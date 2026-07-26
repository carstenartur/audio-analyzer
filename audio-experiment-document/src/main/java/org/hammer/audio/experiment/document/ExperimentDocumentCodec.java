package org.hammer.audio.experiment.document;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.hammer.audio.plugin.document.DocumentValue;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;

/** Strict parser, validator, normalizer and canonical serializer for {@code .audioexp} files. */
public final class ExperimentDocumentCodec {

  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "$schema",
          "format",
          "formatVersion",
          "experiment",
          "workflow",
          "profiles",
          "requiredPlugins",
          "pluginData",
          "assets",
          "outputs",
          "provenance");
  private static final Set<String> EXPERIMENT_FIELDS =
      Set.of(
          "id",
          "name",
          "description",
          "tags",
          "intent",
          "sourceMode",
          "intendedDuration",
          "applicationVersion");
  private static final Set<String> WORKFLOW_FIELDS =
      Set.of("format", "formatVersion", "content", "sha256");
  private static final Set<String> REQUIREMENT_FIELDS = Set.of("id", "versionRange", "sections");
  private static final Set<String> PLUGIN_SECTION_FIELDS =
      Set.of("schemaVersion", "algorithmVersion", "data");
  private static final Set<String> ASSET_FIELDS =
      Set.of("id", "relativePath", "mediaType", "sizeBytes", "sha256");
  private static final Set<String> OUTPUT_FIELDS = Set.of("id", "mediaType", "baseName");
  private static final Set<String> PROVENANCE_FIELDS =
      Set.of(
          "creatorDisplayName",
          "verifiedAccount",
          "createdAt",
          "modifiedAt",
          "softwareVersion",
          "canonicalSha256",
          "migrationNotes");

  private final ObjectMapper mapper;
  private final WorkflowDslParser workflowParser = new WorkflowDslParser();
  private final WorkflowDslSerializer workflowSerializer = new WorkflowDslSerializer();

  /** Create a codec with duplicate-key detection and no polymorphic deserialization. */
  public ExperimentDocumentCodec() {
    JsonFactory factory =
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    mapper = new ObjectMapper(factory);
  }

  /** Parse, validate and normalize bounded UTF-8 JSON bytes. */
  public ExperimentDocument decode(byte[] bytes) throws ExperimentDocumentException {
    if (bytes == null || bytes.length == 0) {
      throw failure("/", "empty-document", "Experiment document is empty");
    }
    if (bytes.length > ExperimentDocumentFormat.MAX_DOCUMENT_BYTES) {
      throw failure("/", "max-bytes", "Experiment document exceeds the byte limit");
    }
    JsonNode parsed;
    try {
      parsed = mapper.readTree(bytes);
    } catch (IOException exception) {
      throw failure("/", "invalid-json", "Experiment document is not strict JSON", exception);
    }
    ObjectNode root = requireObject(parsed, "/");
    rejectUnknown(root, ROOT_FIELDS, "/");
    ExperimentDocument document = parseDocument(root);
    ExperimentDocument normalized = normalizeWorkflow(document, true);
    String expectedHash = canonicalHash(normalized);
    if (!expectedHash.equals(normalized.provenance().canonicalSha256())) {
      throw failure(
          "/provenance/canonicalSha256",
          "document-hash-mismatch",
          "Canonical document SHA-256 does not match its contents");
    }
    return normalized;
  }

  /** Normalize and serialize a document to byte-stable canonical UTF-8 JSON. */
  public byte[] encode(ExperimentDocument document) throws ExperimentDocumentException {
    ExperimentDocument normalized = normalizeWorkflow(document, false);
    String hash = canonicalHash(normalized);
    ExperimentDocument withHash = withCanonicalHash(normalized, hash);
    return writeTree(toTree(withHash, true));
  }

  /** Return the canonical hash without modifying the supplied document. */
  public String canonicalHash(ExperimentDocument document) throws ExperimentDocumentException {
    ExperimentDocument withoutHash = withCanonicalHash(document, "");
    return DocumentHashes.sha256(writeTree(toTree(withoutHash, false)));
  }

  /** Atomically normalize and save to the requested path through a sibling temporary file. */
  public void save(java.nio.file.Path target, ExperimentDocument document) throws IOException {
    byte[] bytes = encode(document);
    java.nio.file.Path normalized = target.toAbsolutePath().normalize();
    java.nio.file.Path parent = normalized.getParent();
    java.nio.file.Path fileName = normalized.getFileName();
    if (parent == null || fileName == null) {
      throw new IOException("Experiment document target has no parent or filename: " + normalized);
    }
    java.nio.file.Files.createDirectories(parent);
    java.nio.file.Path temporary = normalized.resolveSibling(fileName.toString() + ".partial");
    java.nio.file.Files.write(
        temporary,
        bytes,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
        java.nio.file.StandardOpenOption.WRITE);
    try {
      java.nio.file.Files.move(
          temporary,
          normalized,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
      java.nio.file.Files.move(
          temporary, normalized, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** Read and decode one bounded document from disk. */
  public ExperimentDocument load(java.nio.file.Path source) throws IOException {
    long size = java.nio.file.Files.size(source);
    if (size > ExperimentDocumentFormat.MAX_DOCUMENT_BYTES) {
      throw failure("/", "max-bytes", "Experiment document exceeds the byte limit");
    }
    return decode(java.nio.file.Files.readAllBytes(source));
  }

  private ExperimentDocument parseDocument(ObjectNode root) throws ExperimentDocumentException {
    String schema = requiredText(root, "$schema", "/$schema");
    String format = requiredText(root, "format", "/format");
    int version = requiredPositiveInt(root, "formatVersion", "/formatVersion");
    if (!ExperimentDocumentFormat.FORMAT_ID.equals(format)) {
      throw failure("/format", "unsupported-format", "Unsupported experiment document format");
    }
    if (version != ExperimentDocumentFormat.VERSION) {
      throw failure(
          "/formatVersion",
          "unsupported-version",
          "Unsupported experiment document version: " + version);
    }
    if (!ExperimentDocumentFormat.SCHEMA_RESOURCE.equals(schema)) {
      throw failure("/$schema", "unsupported-schema", "Unsupported experiment document schema");
    }
    return new ExperimentDocument(
        schema,
        format,
        version,
        parseExperiment(requiredObject(root, "experiment", "/experiment")),
        parseWorkflow(requiredObject(root, "workflow", "/workflow")),
        parseProfiles(requiredObject(root, "profiles", "/profiles")),
        parseRequirements(requiredArray(root, "requiredPlugins", "/requiredPlugins")),
        parsePluginData(requiredObject(root, "pluginData", "/pluginData")),
        parseAssets(requiredArray(root, "assets", "/assets")),
        parseOutputs(requiredArray(root, "outputs", "/outputs")),
        parseProvenance(requiredObject(root, "provenance", "/provenance")));
  }

  private ExperimentDocument.ExperimentInfo parseExperiment(ObjectNode node)
      throws ExperimentDocumentException {
    rejectUnknown(node, EXPERIMENT_FIELDS, "/experiment");
    String duration = optionalText(node, "intendedDuration", "/experiment/intendedDuration");
    return new ExperimentDocument.ExperimentInfo(
        requiredText(node, "id", "/experiment/id"),
        requiredText(node, "name", "/experiment/name"),
        optionalText(node, "description", "/experiment/description"),
        textArray(requiredArray(node, "tags", "/experiment/tags"), "/experiment/tags"),
        requiredText(node, "intent", "/experiment/intent"),
        requiredText(node, "sourceMode", "/experiment/sourceMode"),
        duration.isBlank() ? null : parseDuration(duration, "/experiment/intendedDuration"),
        requiredText(node, "applicationVersion", "/experiment/applicationVersion"));
  }

  private ExperimentDocument.WorkflowPayload parseWorkflow(ObjectNode node)
      throws ExperimentDocumentException {
    rejectUnknown(node, WORKFLOW_FIELDS, "/workflow");
    return new ExperimentDocument.WorkflowPayload(
        requiredText(node, "format", "/workflow/format"),
        requiredPositiveInt(node, "formatVersion", "/workflow/formatVersion"),
        requiredText(node, "content", "/workflow/content"),
        requiredText(node, "sha256", "/workflow/sha256"));
  }

  private DocumentValue.ObjectValue parseProfiles(ObjectNode node)
      throws ExperimentDocumentException {
    DocumentValue value = DocumentValueJson.fromJson(node);
    if (value instanceof DocumentValue.ObjectValue objectValue) {
      return objectValue;
    }
    throw failure("/profiles", "expected-object", "profiles must be an object");
  }

  private List<ExperimentDocument.PluginRequirement> parseRequirements(ArrayNode array)
      throws ExperimentDocumentException {
    requireCollectionLimit(array.size(), "/requiredPlugins");
    ArrayList<ExperimentDocument.PluginRequirement> result = new ArrayList<>();
    for (int index = 0; index < array.size(); index++) {
      String pointer = "/requiredPlugins/" + index;
      ObjectNode item = requireObject(array.get(index), pointer);
      rejectUnknown(item, REQUIREMENT_FIELDS, pointer);
      result.add(
          new ExperimentDocument.PluginRequirement(
              requiredText(item, "id", pointer + "/id"),
              requiredText(item, "versionRange", pointer + "/versionRange"),
              textArray(
                  requiredArray(item, "sections", pointer + "/sections"), pointer + "/sections")));
    }
    return result;
  }

  private Map<String, Map<String, ExperimentDocument.PluginSection>> parsePluginData(
      ObjectNode plugins) throws ExperimentDocumentException {
    requireCollectionLimit(plugins.size(), "/pluginData");
    TreeMap<String, Map<String, ExperimentDocument.PluginSection>> result = new TreeMap<>();
    Iterator<Map.Entry<String, JsonNode>> pluginFields = plugins.fields();
    while (pluginFields.hasNext()) {
      Map.Entry<String, JsonNode> pluginEntry = pluginFields.next();
      String pluginId = ExperimentDocument.requireIdentifier(pluginEntry.getKey(), "plugin id");
      String pluginPointer = DocumentValueJson.pointer("/pluginData", pluginId);
      ObjectNode sections = requireObject(pluginEntry.getValue(), pluginPointer);
      requireCollectionLimit(sections.size(), pluginPointer);
      TreeMap<String, ExperimentDocument.PluginSection> parsedSections = new TreeMap<>();
      Iterator<Map.Entry<String, JsonNode>> sectionFields = sections.fields();
      while (sectionFields.hasNext()) {
        Map.Entry<String, JsonNode> sectionEntry = sectionFields.next();
        String sectionId =
            ExperimentDocument.requireIdentifier(sectionEntry.getKey(), "plugin section id");
        String sectionPointer = DocumentValueJson.pointer(pluginPointer, sectionId);
        ObjectNode section = requireObject(sectionEntry.getValue(), sectionPointer);
        rejectUnknown(section, PLUGIN_SECTION_FIELDS, sectionPointer);
        parsedSections.put(
            sectionId,
            new ExperimentDocument.PluginSection(
                requiredPositiveInt(section, "schemaVersion", sectionPointer + "/schemaVersion"),
                requiredText(section, "algorithmVersion", sectionPointer + "/algorithmVersion"),
                DocumentValueJson.fromJson(
                    requiredNode(section, "data", sectionPointer + "/data"))));
      }
      result.put(pluginId, parsedSections);
    }
    return result;
  }

  private List<ExperimentDocument.AssetReference> parseAssets(ArrayNode array)
      throws ExperimentDocumentException {
    requireCollectionLimit(array.size(), "/assets");
    ArrayList<ExperimentDocument.AssetReference> result = new ArrayList<>();
    for (int index = 0; index < array.size(); index++) {
      String pointer = "/assets/" + index;
      ObjectNode item = requireObject(array.get(index), pointer);
      rejectUnknown(item, ASSET_FIELDS, pointer);
      result.add(
          new ExperimentDocument.AssetReference(
              requiredText(item, "id", pointer + "/id"),
              requiredText(item, "relativePath", pointer + "/relativePath"),
              requiredText(item, "mediaType", pointer + "/mediaType"),
              requiredNonNegativeLong(item, "sizeBytes", pointer + "/sizeBytes"),
              requiredText(item, "sha256", pointer + "/sha256")));
    }
    return result;
  }

  private List<ExperimentDocument.OutputRequest> parseOutputs(ArrayNode array)
      throws ExperimentDocumentException {
    requireCollectionLimit(array.size(), "/outputs");
    ArrayList<ExperimentDocument.OutputRequest> result = new ArrayList<>();
    for (int index = 0; index < array.size(); index++) {
      String pointer = "/outputs/" + index;
      ObjectNode item = requireObject(array.get(index), pointer);
      rejectUnknown(item, OUTPUT_FIELDS, pointer);
      result.add(
          new ExperimentDocument.OutputRequest(
              requiredText(item, "id", pointer + "/id"),
              requiredText(item, "mediaType", pointer + "/mediaType"),
              requiredText(item, "baseName", pointer + "/baseName")));
    }
    return result;
  }

  private ExperimentDocument.Provenance parseProvenance(ObjectNode node)
      throws ExperimentDocumentException {
    rejectUnknown(node, PROVENANCE_FIELDS, "/provenance");
    return new ExperimentDocument.Provenance(
        requiredText(node, "creatorDisplayName", "/provenance/creatorDisplayName"),
        optionalText(node, "verifiedAccount", "/provenance/verifiedAccount"),
        parseInstant(
            requiredText(node, "createdAt", "/provenance/createdAt"), "/provenance/createdAt"),
        parseInstant(
            requiredText(node, "modifiedAt", "/provenance/modifiedAt"), "/provenance/modifiedAt"),
        requiredText(node, "softwareVersion", "/provenance/softwareVersion"),
        requiredText(node, "canonicalSha256", "/provenance/canonicalSha256"),
        textArray(
            requiredArray(node, "migrationNotes", "/provenance/migrationNotes"),
            "/provenance/migrationNotes"));
  }

  private ExperimentDocument normalizeWorkflow(ExperimentDocument document, boolean verifyHash)
      throws ExperimentDocumentException {
    ExperimentDocument.WorkflowPayload payload = document.workflow();
    if (!ExperimentDocumentFormat.WORKFLOW_FORMAT_ID.equals(payload.format())
        || payload.formatVersion() != ExperimentDocumentFormat.WORKFLOW_VERSION) {
      throw failure("/workflow", "unsupported-workflow", "Unsupported embedded workflow format");
    }
    Workflow workflow;
    try {
      workflow = workflowParser.parse(payload.content());
    } catch (RuntimeException exception) {
      throw failure(
          "/workflow/content", "invalid-workflow", "Embedded workflow DSL is invalid", exception);
    }
    String canonical = workflowSerializer.serialize(workflow);
    String hash = DocumentHashes.sha256(canonical);
    if (verifyHash && !hash.equals(payload.sha256())) {
      throw failure(
          "/workflow/sha256",
          "workflow-hash-mismatch",
          "Workflow SHA-256 does not match canonical DSL");
    }
    ExperimentDocument.WorkflowPayload normalizedPayload =
        new ExperimentDocument.WorkflowPayload(
            ExperimentDocumentFormat.WORKFLOW_FORMAT_ID,
            ExperimentDocumentFormat.WORKFLOW_VERSION,
            canonical,
            hash);
    return new ExperimentDocument(
        document.schema(),
        document.format(),
        document.formatVersion(),
        document.experiment(),
        normalizedPayload,
        document.profiles(),
        document.requiredPlugins(),
        document.pluginData(),
        document.assets(),
        document.outputs(),
        document.provenance());
  }

  private ExperimentDocument withCanonicalHash(ExperimentDocument document, String hash) {
    ExperimentDocument.Provenance current = document.provenance();
    ExperimentDocument.Provenance provenance =
        new ExperimentDocument.Provenance(
            current.creatorDisplayName(),
            current.verifiedAccount(),
            current.createdAt(),
            current.modifiedAt(),
            current.softwareVersion(),
            hash,
            current.migrationNotes());
    return new ExperimentDocument(
        document.schema(),
        document.format(),
        document.formatVersion(),
        document.experiment(),
        document.workflow(),
        document.profiles(),
        document.requiredPlugins(),
        document.pluginData(),
        document.assets(),
        document.outputs(),
        provenance);
  }

  private ObjectNode toTree(ExperimentDocument document, boolean includeHash) {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    root.put("$schema", document.schema());
    root.put("format", document.format());
    root.put("formatVersion", document.formatVersion());
    root.set("experiment", experimentNode(document.experiment()));
    root.set("workflow", workflowNode(document.workflow()));
    root.set("profiles", DocumentValueJson.toJson(document.profiles()));
    root.set("requiredPlugins", requirementsNode(document.requiredPlugins()));
    root.set("pluginData", pluginDataNode(document.pluginData()));
    root.set("assets", assetsNode(document.assets()));
    root.set("outputs", outputsNode(document.outputs()));
    root.set("provenance", provenanceNode(document.provenance(), includeHash));
    return root;
  }

  private ObjectNode experimentNode(ExperimentDocument.ExperimentInfo value) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("id", value.id());
    node.put("name", value.name());
    node.put("description", value.description());
    node.set("tags", stringArray(value.tags()));
    node.put("intent", value.intent());
    node.put("sourceMode", value.sourceMode());
    if (value.intendedDuration() == null) {
      node.putNull("intendedDuration");
    } else {
      node.put("intendedDuration", value.intendedDuration().toString());
    }
    node.put("applicationVersion", value.applicationVersion());
    return node;
  }

  private ObjectNode workflowNode(ExperimentDocument.WorkflowPayload value) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("format", value.format());
    node.put("formatVersion", value.formatVersion());
    node.put("content", value.content());
    node.put("sha256", value.sha256());
    return node;
  }

  private ArrayNode requirementsNode(List<ExperimentDocument.PluginRequirement> values) {
    ArrayNode result = JsonNodeFactory.instance.arrayNode();
    values.stream()
        .sorted(java.util.Comparator.comparing(ExperimentDocument.PluginRequirement::id))
        .forEach(
            value -> {
              ObjectNode node = JsonNodeFactory.instance.objectNode();
              node.put("id", value.id());
              node.put("versionRange", value.versionRange());
              node.set("sections", stringArray(value.sections()));
              result.add(node);
            });
    return result;
  }

  private ObjectNode pluginDataNode(
      Map<String, Map<String, ExperimentDocument.PluginSection>> values) {
    ObjectNode plugins = JsonNodeFactory.instance.objectNode();
    new TreeMap<>(values)
        .forEach(
            (pluginId, sections) -> {
              ObjectNode sectionNode = JsonNodeFactory.instance.objectNode();
              new TreeMap<>(sections)
                  .forEach(
                      (sectionId, value) -> {
                        ObjectNode node = JsonNodeFactory.instance.objectNode();
                        node.put("schemaVersion", value.schemaVersion());
                        node.put("algorithmVersion", value.algorithmVersion());
                        node.set("data", DocumentValueJson.toJson(value.data()));
                        sectionNode.set(sectionId, node);
                      });
              plugins.set(pluginId, sectionNode);
            });
    return plugins;
  }

  private ArrayNode assetsNode(List<ExperimentDocument.AssetReference> values) {
    ArrayNode result = JsonNodeFactory.instance.arrayNode();
    values.stream()
        .sorted(java.util.Comparator.comparing(ExperimentDocument.AssetReference::id))
        .forEach(
            value -> {
              ObjectNode node = JsonNodeFactory.instance.objectNode();
              node.put("id", value.id());
              node.put("relativePath", value.relativePath());
              node.put("mediaType", value.mediaType());
              node.put("sizeBytes", value.sizeBytes());
              node.put("sha256", value.sha256());
              result.add(node);
            });
    return result;
  }

  private ArrayNode outputsNode(List<ExperimentDocument.OutputRequest> values) {
    ArrayNode result = JsonNodeFactory.instance.arrayNode();
    values.stream()
        .sorted(java.util.Comparator.comparing(ExperimentDocument.OutputRequest::id))
        .forEach(
            value -> {
              ObjectNode node = JsonNodeFactory.instance.objectNode();
              node.put("id", value.id());
              node.put("mediaType", value.mediaType());
              node.put("baseName", value.baseName());
              result.add(node);
            });
    return result;
  }

  private ObjectNode provenanceNode(ExperimentDocument.Provenance value, boolean includeHash) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("creatorDisplayName", value.creatorDisplayName());
    node.put("verifiedAccount", value.verifiedAccount());
    node.put("createdAt", value.createdAt().toString());
    node.put("modifiedAt", value.modifiedAt().toString());
    node.put("softwareVersion", value.softwareVersion());
    if (includeHash) {
      node.put("canonicalSha256", value.canonicalSha256());
    }
    node.set("migrationNotes", stringArray(value.migrationNotes()));
    return node;
  }

  private ArrayNode stringArray(List<String> values) {
    ArrayNode array = JsonNodeFactory.instance.arrayNode();
    values.forEach(array::add);
    return array;
  }

  private byte[] writeTree(JsonNode tree) throws ExperimentDocumentException {
    try {
      return mapper.writeValueAsBytes(tree);
    } catch (IOException exception) {
      throw failure(
          "/",
          "serialization-failed",
          "Could not serialize canonical experiment document",
          exception);
    }
  }

  private static ObjectNode requiredObject(ObjectNode parent, String field, String pointer)
      throws ExperimentDocumentException {
    return requireObject(requiredNode(parent, field, pointer), pointer);
  }

  private static ObjectNode requireObject(JsonNode value, String pointer)
      throws ExperimentDocumentException {
    if (value instanceof ObjectNode objectNode) {
      return objectNode;
    }
    throw failure(pointer, "expected-object", "Expected a JSON object");
  }

  private static ArrayNode requiredArray(ObjectNode parent, String field, String pointer)
      throws ExperimentDocumentException {
    JsonNode value = requiredNode(parent, field, pointer);
    if (value instanceof ArrayNode arrayNode) {
      return arrayNode;
    }
    throw failure(pointer, "expected-array", "Expected a JSON array");
  }

  private static JsonNode requiredNode(ObjectNode parent, String field, String pointer)
      throws ExperimentDocumentException {
    JsonNode value = parent.get(field);
    if (value == null) {
      throw failure(pointer, "missing-field", "Missing required field: " + field);
    }
    return value;
  }

  private static String requiredText(ObjectNode parent, String field, String pointer)
      throws ExperimentDocumentException {
    JsonNode value = requiredNode(parent, field, pointer);
    if (!value.isTextual()) {
      throw failure(pointer, "expected-string", "Expected a JSON string");
    }
    String text = value.textValue();
    if (text.length() > ExperimentDocumentFormat.MAX_STRING_LENGTH) {
      throw failure(pointer, "max-string", "String exceeds the configured limit");
    }
    return text;
  }

  private static String optionalText(ObjectNode parent, String field, String pointer)
      throws ExperimentDocumentException {
    JsonNode value = parent.get(field);
    if (value == null || value.isNull()) {
      return "";
    }
    if (!value.isTextual()) {
      throw failure(pointer, "expected-string", "Expected a JSON string or null");
    }
    String text = value.textValue();
    if (text.length() > ExperimentDocumentFormat.MAX_STRING_LENGTH) {
      throw failure(pointer, "max-string", "String exceeds the configured limit");
    }
    return text;
  }

  private static int requiredPositiveInt(ObjectNode parent, String field, String pointer)
      throws ExperimentDocumentException {
    JsonNode value = requiredNode(parent, field, pointer);
    if (!value.canConvertToInt() || value.intValue() < 1) {
      throw failure(pointer, "expected-positive-integer", "Expected a positive integer");
    }
    return value.intValue();
  }

  private static long requiredNonNegativeLong(ObjectNode parent, String field, String pointer)
      throws ExperimentDocumentException {
    JsonNode value = requiredNode(parent, field, pointer);
    if (!value.canConvertToLong() || value.longValue() < 0L) {
      throw failure(pointer, "expected-nonnegative-integer", "Expected a non-negative integer");
    }
    return value.longValue();
  }

  private static List<String> textArray(ArrayNode array, String pointer)
      throws ExperimentDocumentException {
    requireCollectionLimit(array.size(), pointer);
    ArrayList<String> result = new ArrayList<>();
    for (int index = 0; index < array.size(); index++) {
      JsonNode value = array.get(index);
      if (!value.isTextual()) {
        throw failure(pointer + "/" + index, "expected-string", "Expected a JSON string");
      }
      String text = value.textValue();
      if (text.length() > ExperimentDocumentFormat.MAX_STRING_LENGTH) {
        throw failure(pointer + "/" + index, "max-string", "String exceeds the configured limit");
      }
      result.add(text);
    }
    return result;
  }

  private static void rejectUnknown(ObjectNode node, Set<String> allowed, String pointer)
      throws ExperimentDocumentException {
    HashSet<String> unknown = new HashSet<>();
    node.fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name)) {
                unknown.add(name);
              }
            });
    if (!unknown.isEmpty()) {
      String field = unknown.stream().sorted().findFirst().orElseThrow();
      throw failure(
          DocumentValueJson.pointer(pointer, field),
          "unknown-field",
          "Unknown core field: " + field);
    }
  }

  private static void requireCollectionLimit(int size, String pointer)
      throws ExperimentDocumentException {
    if (size > ExperimentDocumentFormat.MAX_COLLECTION_SIZE) {
      throw failure(pointer, "max-collection", "Collection exceeds the configured limit");
    }
  }

  private static Duration parseDuration(String value, String pointer)
      throws ExperimentDocumentException {
    try {
      return Duration.parse(value);
    } catch (DateTimeParseException exception) {
      throw failure(pointer, "invalid-duration", "Expected an ISO-8601 duration", exception);
    }
  }

  private static Instant parseInstant(String value, String pointer)
      throws ExperimentDocumentException {
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException exception) {
      throw failure(pointer, "invalid-instant", "Expected an ISO-8601 instant", exception);
    }
  }

  private static ExperimentDocumentException failure(String pointer, String code, String message) {
    return new ExperimentDocumentException(pointer, code, message);
  }

  private static ExperimentDocumentException failure(
      String pointer, String code, String message, Throwable cause) {
    return new ExperimentDocumentException(pointer, code, message, cause);
  }
}
