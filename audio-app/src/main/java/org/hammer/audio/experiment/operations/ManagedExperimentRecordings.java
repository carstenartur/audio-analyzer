package org.hammer.audio.experiment.operations;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.RecordingView;
import org.hammer.audio.recording.AudioBlockRecordingReader;
import org.hammer.audio.recording.RecordingInspection;

/** Managed imported/generated recordings, including containment and non-destructive recovery. */
final class ManagedExperimentRecordings {
  private static final long MAX_IMPORT_BYTES = 512L * 1024L * 1024L;
  private final Path directory;

  ManagedExperimentRecordings(Path directory) {
    this.directory = directory;
  }

  RecordingView importRecording(InputStream input) throws IOException {
    Path imports = directory.resolve("imports");
    Files.createDirectories(imports);
    Path target = imports.resolve(UUID.randomUUID() + ".aarec");
    boolean accepted = false;
    try {
      try (OutputStream output = Files.newOutputStream(target)) {
        byte[] buffer = new byte[8192];
        long total = 0L;
        int count = input.read(buffer);
        while (count >= 0) {
          total += count;
          if (total > MAX_IMPORT_BYTES) {
            throw new IOException("Recording import exceeds 512 MiB.");
          }
          output.write(buffer, 0, count);
          count = input.read(buffer);
        }
      }
      RecordingInspection inspection = AudioBlockRecordingReader.inspect(target);
      accepted = true;
      return recordingView(target, inspection);
    } finally {
      if (!accepted) {
        Files.deleteIfExists(target);
      }
    }
  }

  RecordingView recover(String id) throws IOException {
    Path source = path(id);
    Path imports = directory.resolve("imports");
    Files.createDirectories(imports);
    Path target = imports.resolve(UUID.randomUUID() + ".aarec");
    RecordingInspection inspection = AudioBlockRecordingReader.recover(source, target);
    Files.writeString(
        target.resolveSibling(target.getFileName() + ".recovery.txt"),
        "Recovered from " + id + "\nOriginal: " + AudioBlockRecordingReader.inspect(source) + "\n");
    return recordingView(target, inspection);
  }

  List<RecordingView> list() throws IOException {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    List<RecordingView> result = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(directory, 3)) {
      List<Path> candidates =
          paths
              .filter(Files::isRegularFile)
              .filter(ManagedExperimentRecordings::recordingFile)
              .sorted()
              .toList();
      for (Path path : candidates) {
        try {
          result.add(recordingView(path, AudioBlockRecordingReader.inspect(path)));
        } catch (IOException exception) {
          result.add(
              new RecordingView(
                  directory.relativize(path).toString(),
                  ExperimentEvidenceFiles.fileName(path),
                  "UNREADABLE",
                  0L));
        }
      }
    }
    return List.copyOf(result);
  }

  private RecordingView recordingView(Path path, RecordingInspection inspection)
      throws IOException {
    return new RecordingView(
        directory.relativize(path).toString().replace('\\', '/'),
        ExperimentEvidenceFiles.fileName(path),
        inspection.integrity().name(),
        inspection.totalFrames());
  }

  private static boolean recordingFile(Path path) {
    Path name = path.getFileName();
    if (name == null) {
      return false;
    }
    String filename = name.toString();
    return filename.endsWith(".aarec") || filename.endsWith(".aarec.partial");
  }

  Path path(String id) throws IOException {
    if (id == null || id.isBlank()) {
      throw new IOException("No recording is selected.");
    }
    Path candidate = directory.resolve(id).normalize();
    if (!candidate.startsWith(directory)
        || !Files.isRegularFile(candidate)
        || !candidate.toRealPath().startsWith(directory.toRealPath())) {
      throw new IOException("Recording must be an imported or generated input in this workspace.");
    }
    return candidate;
  }
}
