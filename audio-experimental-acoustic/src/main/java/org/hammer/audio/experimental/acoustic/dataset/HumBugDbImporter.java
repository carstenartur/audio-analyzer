package org.hammer.audio.experimental.acoustic.dataset;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Imports a local HumBugDB dataset export into the normalized dataset abstraction.
 *
 * <p>The importer is intentionally tolerant: it looks for metadata CSV files under {@code
 * data/metadata} (falling back to any CSVs in the dataset root), resolves audio files from the
 * local export, preserves all available metadata, and creates whole-clip annotations when no
 * explicit span columns are present.
 */
@SuppressWarnings("PMD.UseConcurrentHashMap")
public final class HumBugDbImporter implements DatasetImporter {

  private static final URI HUMBUG_DB_SOURCE =
      URI.create("https://github.com/HumBug-Mosquito/HumBugDB");
  private static final String LICENSE_PLACEHOLDER = "CHECK_DATASET_RELEASE";
  private static final String SOUND_TYPE = "sound_type";
  private static final String SPECIES = "species";
  private static final String GENDER = "gender";
  private static final Set<String> LABEL_COLUMNS =
      Set.of(SOUND_TYPE, SPECIES, GENDER, "fed", "plurality", "age");
  private static final Set<String> AUDIO_PATH_COLUMNS =
      Set.of("name", "filename", "file", "path", "audio_path");
  private static final Set<String> DURATION_COLUMNS =
      Set.of("length", "duration", "duration_seconds", "clip_duration_seconds");
  private static final Set<String> SAMPLE_RATE_COLUMNS =
      Set.of("sample_rate", "sampling_rate", "sample_rate_hz");
  private static final Set<String> ANNOTATION_START_COLUMNS =
      Set.of("start_time", "start_seconds", "offset_seconds", "begin_seconds");
  private static final Set<String> ANNOTATION_END_COLUMNS = Set.of("end_time", "end_seconds");
  private static final Set<String> ANNOTATION_LENGTH_COLUMNS =
      Set.of(
          "annotation_length",
          "annotation_duration",
          "annotation_duration_seconds",
          "event_length_seconds",
          "event_duration_seconds");
  private static final Set<String> ANNOTATION_LABEL_COLUMNS =
      Set.of("event_label", "annotation_label", SOUND_TYPE, SPECIES);
  private static final Set<String> RESERVED_COLUMNS = buildReservedColumns();

  @Override
  public String datasetId() {
    return "humbugdb";
  }

  @Override
  public DatasetManifest importFrom(Path localRootPath) throws IOException {
    Objects.requireNonNull(localRootPath, "localRootPath");
    Path root = localRootPath.toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      throw new IOException("HumBugDB root is not a directory: " + root);
    }
    List<Path> metadataCsvFiles = metadataCsvFiles(root);
    if (metadataCsvFiles.isEmpty()) {
      throw new IOException("No metadata CSV files found under " + root);
    }
    AudioFileIndex audioFileIndex = AudioFileIndex.build(root);
    Map<String, DatasetRecording> recordings = new LinkedHashMap<>();
    Set<String> usedRecordingIds = new LinkedHashSet<>();
    for (Path csvFile : metadataCsvFiles) {
      importCsv(root, csvFile, audioFileIndex, recordings, usedRecordingIds);
    }
    if (recordings.isEmpty()) {
      throw new IOException("No HumBugDB recordings could be resolved from " + root);
    }
    DatasetDescriptor descriptor =
        new DatasetDescriptor(
            datasetId(), "HumBugDB", HUMBUG_DB_SOURCE, LICENSE_PLACEHOLDER, root, metadataSchema());
    return new DatasetManifest(descriptor, List.copyOf(recordings.values()));
  }

  private static void importCsv(
      Path root,
      Path csvFile,
      AudioFileIndex audioFileIndex,
      Map<String, DatasetRecording> recordings,
      Set<String> usedRecordingIds)
      throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(csvFile)) {
      String headerLine = reader.readLine();
      if (headerLine == null || headerLine.isBlank()) {
        return;
      }
      List<String> headers = parseCsvLine(headerLine);
      int rowNumber = 1;
      String line = reader.readLine();
      while (line != null) {
        rowNumber++;
        if (line.isBlank()) {
          line = reader.readLine();
          continue;
        }
        Map<String, String> row = row(headers, parseCsvLine(line));
        Path audioPath = resolveAudioPath(root, audioFileIndex, row);
        if (audioPath == null) {
          continue;
        }
        DatasetAudioLoader.AudioFileInfo audioInfo = DatasetAudioLoader.inspect(audioPath);
        String recordingId = uniqueRecordingId(row, audioPath, rowNumber, usedRecordingIds);
        DatasetRecording recording =
            new DatasetRecording(
                recordingId,
                root.relativize(audioPath),
                sampleRateHz(row, audioInfo),
                durationSeconds(row, audioInfo),
                labels(row),
                annotations(row, durationSeconds(row, audioInfo)),
                metadata(row, root, csvFile));
        recordings.put(recordingId, recording);
        line = reader.readLine();
      }
    }
  }

  private static String uniqueRecordingId(
      Map<String, String> row, Path audioPath, int rowNumber, Set<String> usedRecordingIds) {
    String baseId =
        firstNonBlank(
            row.get("id"),
            stripExtension(fileName(row)),
            stripExtension(lastPathSegment(audioPath.toString())),
            "recording-" + rowNumber);
    String candidate = sanitizeId(baseId);
    int suffix = 2;
    while (!usedRecordingIds.add(candidate)) {
      candidate = sanitizeId(baseId) + "-" + suffix;
      suffix++;
    }
    return candidate;
  }

  private static String sanitizeId(String value) {
    return value.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
  }

  private static double sampleRateHz(
      Map<String, String> row, DatasetAudioLoader.AudioFileInfo audioInfo) {
    Double parsed = parseDouble(firstPresent(row, SAMPLE_RATE_COLUMNS));
    return parsed != null && parsed > 0.0 ? parsed : audioInfo.sampleRateHz();
  }

  private static double durationSeconds(
      Map<String, String> row, DatasetAudioLoader.AudioFileInfo audioInfo) {
    Double parsed = parseDouble(firstPresent(row, DURATION_COLUMNS));
    if (parsed != null && parsed >= 0.0) {
      return parsed;
    }
    Double start = parseDouble(firstPresent(row, ANNOTATION_START_COLUMNS));
    Double end = parseDouble(firstPresent(row, ANNOTATION_END_COLUMNS));
    if (start != null && end != null && end >= start) {
      return Math.max(audioInfo.durationSeconds(), end);
    }
    return audioInfo.durationSeconds();
  }

  private static Map<String, String> labels(Map<String, String> row) {
    Map<String, String> labels = new LinkedHashMap<>();
    for (String column : LABEL_COLUMNS) {
      String value = row.get(column);
      if (value != null && !value.isBlank()) {
        labels.put(column, value);
      }
    }
    return labels;
  }

  private static List<DatasetAnnotation> annotations(
      Map<String, String> row, double durationSeconds) {
    Double start = parseDouble(firstPresent(row, ANNOTATION_START_COLUMNS));
    Double end = parseDouble(firstPresent(row, ANNOTATION_END_COLUMNS));
    if (start != null) {
      if (end == null) {
        Double length = parseDouble(firstPresent(row, ANNOTATION_LENGTH_COLUMNS));
        end = length == null ? durationSeconds : start + length;
      }
      if (end >= start) {
        return List.of(
            new DatasetAnnotation(start, end, annotationLabel(row), annotationMetadata(row)));
      }
    }
    if (durationSeconds > 0.0) {
      String label = annotationLabel(row);
      if (!label.isBlank()) {
        return List.of(new DatasetAnnotation(0.0, durationSeconds, label, annotationMetadata(row)));
      }
    }
    return List.of();
  }

  private static String annotationLabel(Map<String, String> row) {
    return firstNonBlank(
        row.get("event_label"),
        row.get("annotation_label"),
        row.get(SOUND_TYPE),
        row.get(SPECIES),
        "recording");
  }

  private static Map<String, String> annotationMetadata(Map<String, String> row) {
    Map<String, String> metadata = new LinkedHashMap<>();
    putIfPresent(metadata, "row_id", row.get("id"));
    putIfPresent(metadata, SOUND_TYPE, row.get(SOUND_TYPE));
    putIfPresent(metadata, SPECIES, row.get(SPECIES));
    putIfPresent(metadata, GENDER, row.get(GENDER));
    return metadata;
  }

  private static Map<String, String> metadata(Map<String, String> row, Path root, Path csvFile) {
    Map<String, String> metadata = new TreeMap<>();
    putIfPresent(metadata, "metadata_csv", root.relativize(csvFile).toString());
    for (Map.Entry<String, String> entry : row.entrySet()) {
      if (RESERVED_COLUMNS.contains(entry.getKey())) {
        continue;
      }
      String value = entry.getValue();
      if (value != null && !value.isBlank()) {
        metadata.put(entry.getKey(), value);
      }
    }
    return metadata;
  }

  private static Path resolveAudioPath(
      Path root, AudioFileIndex audioFileIndex, Map<String, String> row) {
    List<String> candidates = new ArrayList<>();
    for (String column : AUDIO_PATH_COLUMNS) {
      String value = row.get(column);
      if (value != null && !value.isBlank()) {
        candidates.add(value.trim());
      }
    }
    String id = row.get("id");
    if (id != null && !id.isBlank()) {
      candidates.add(id.trim() + ".wav");
    }
    Path normalizedRoot = root.normalize();
    for (String candidate : candidates) {
      Path direct = root.resolve(candidate).normalize();
      if (direct.startsWith(normalizedRoot) && Files.isRegularFile(direct)) {
        return direct;
      }
      Path indexed = audioFileIndex.resolve(candidate);
      if (indexed != null) {
        return indexed;
      }
    }
    return null;
  }

  private static List<Path> metadataCsvFiles(Path root) throws IOException {
    Path metadataDir = root.resolve("data").resolve("metadata");
    if (Files.isDirectory(metadataDir)) {
      try (Stream<Path> paths = Files.walk(metadataDir, 2)) {
        return paths
            .filter(Files::isRegularFile)
            .filter(path -> lowerCaseFileName(path).endsWith(".csv"))
            .sorted()
            .toList();
      }
    }
    try (Stream<Path> paths = Files.walk(root, 3)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> lowerCaseFileName(path).endsWith(".csv"))
          .sorted()
          .toList();
    }
  }

  private static Map<String, String> metadataSchema() {
    Map<String, String> schema = new LinkedHashMap<>();
    schema.put(SOUND_TYPE, "Clip-level sound/event label from HumBugDB metadata.");
    schema.put(SPECIES, "Mosquito species label when available.");
    schema.put(GENDER, "Mosquito sex label when available.");
    schema.put("fed", "Feeding/blood-meal status when available.");
    schema.put("plurality", "Single/plural mosquito capture label.");
    schema.put("age", "Mosquito age label when available.");
    schema.put("record_datetime", "Original recording date/time from HumBugDB metadata.");
    schema.put("method", "Collection or experiment method.");
    schema.put("mic_type", "Microphone type or sensor class.");
    schema.put("device_type", "Capture device information.");
    schema.put("country", "Recording country.");
    schema.put("district", "Recording district.");
    schema.put("province", "Recording province/region.");
    schema.put("place", "Free-text recording place.");
    schema.put("location_type", "Location category such as culture/cup/house.");
    schema.put("metadata_csv", "Relative metadata CSV path inside the local export.");
    return schema;
  }

  private static Map<String, String> row(List<String> headers, List<String> values) {
    Map<String, String> row = new LinkedHashMap<>();
    for (int i = 0; i < headers.size(); i++) {
      String value = i < values.size() ? values.get(i).trim() : "";
      row.put(headers.get(i), value);
    }
    return row;
  }

  private static List<String> parseCsvLine(String line) {
    List<String> values = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    int i = 0;
    while (i < line.length()) {
      char ch = line.charAt(i);
      if (ch == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          current.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (ch == ',' && !quoted) {
        values.add(current.toString());
        current.setLength(0);
      } else {
        current.append(ch);
      }
      i++;
    }
    values.add(current.toString());
    return values;
  }

  private static String firstPresent(Map<String, String> row, Set<String> columns) {
    for (String column : columns) {
      String value = row.get(column);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static String fileName(Map<String, String> row) {
    return firstNonBlank(firstPresent(row, AUDIO_PATH_COLUMNS), row.get("id"), "recording");
  }

  private static String stripExtension(String value) {
    int dot = value.lastIndexOf('.');
    return dot > 0 ? value.substring(0, dot) : value;
  }

  private static String lowerCaseFileName(Path path) {
    return lastPathSegment(path.toString()).toLowerCase(Locale.ROOT);
  }

  private static String lastPathSegment(String value) {
    int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
    return slash >= 0 ? value.substring(slash + 1) : value;
  }

  private static Double parseDouble(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Double.parseDouble(value.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static void putIfPresent(Map<String, String> map, String key, String value) {
    if (value != null && !value.isBlank()) {
      map.put(key, value);
    }
  }

  private static Set<String> buildReservedColumns() {
    Set<String> reserved = new LinkedHashSet<>();
    reserved.add("id");
    reserved.addAll(LABEL_COLUMNS);
    reserved.addAll(AUDIO_PATH_COLUMNS);
    reserved.addAll(DURATION_COLUMNS);
    reserved.addAll(SAMPLE_RATE_COLUMNS);
    reserved.addAll(ANNOTATION_START_COLUMNS);
    reserved.addAll(ANNOTATION_END_COLUMNS);
    reserved.addAll(ANNOTATION_LENGTH_COLUMNS);
    reserved.addAll(ANNOTATION_LABEL_COLUMNS);
    return Set.copyOf(reserved);
  }

  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private record AudioFileIndex(Map<String, Path> byName, Map<String, Path> byStem) {

    static AudioFileIndex build(Path root) throws IOException {
      try (Stream<Path> paths = Files.walk(root, 5)) {
        List<Path> audioFiles =
            paths
                .filter(Files::isRegularFile)
                .filter(path -> lowerCaseFileName(path).endsWith(".wav"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        Map<String, Path> byName = new LinkedHashMap<>();
        Map<String, Path> byStem = new LinkedHashMap<>();
        for (Path audioFile : audioFiles) {
          String fileName = lowerCaseFileName(audioFile);
          byName.putIfAbsent(fileName, audioFile);
          byStem.putIfAbsent(stripExtension(fileName), audioFile);
        }
        return new AudioFileIndex(Map.copyOf(byName), Map.copyOf(byStem));
      }
    }

    Path resolve(String candidate) {
      String normalized = candidate.trim().toLowerCase(Locale.ROOT);
      String fileName = lastPathSegment(normalized);
      Path byExactName = byName.get(fileName);
      if (byExactName != null) {
        return byExactName;
      }
      return byStem.get(stripExtension(fileName));
    }
  }
}
