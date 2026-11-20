package org.hammer.audio.experiment.operations;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Finalized evidence hashing and streaming, separate from experiment lifecycle decisions. */
final class ExperimentEvidenceFiles {
  private ExperimentEvidenceFiles() {
    /* Utility class. */
  }

  static void export(Path directory, OutputStream output) throws IOException {
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      try (Stream<Path> paths = Files.list(directory)) {
        for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
          ZipEntry entry = new ZipEntry(fileName(file));
          entry.setTime(0L);
          zip.putNextEntry(entry);
          Files.copy(file, zip);
          zip.closeEntry();
        }
      }
    }
  }

  static void writeHashes(Path directory) throws IOException {
    StringBuilder checksums = new StringBuilder();
    try (Stream<Path> files = Files.list(directory)) {
      List<Path> artifacts =
          files.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList();
      for (Path file : artifacts) {
        String name = fileName(file);
        if (!"SHA256SUMS".equals(name)) {
          checksums.append(sha256(file)).append("  ").append(name).append('\n');
        }
      }
    }
    Files.writeString(directory.resolve("SHA256SUMS"), checksums.toString());
  }

  static String sha256(byte[] bytes) {
    return HexFormat.of().formatHex(digest().digest(bytes));
  }

  static String sha256(Path file) throws IOException {
    MessageDigest digest = digest();
    try (InputStream stream = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      int count = stream.read(buffer);
      while (count >= 0) {
        digest.update(buffer, 0, count);
        count = stream.read(buffer);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static String fileName(Path path) throws IOException {
    Path name = path.getFileName();
    if (name == null) {
      throw new IOException("An evidence artifact must have a filename.");
    }
    return name.toString();
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
  }
}
