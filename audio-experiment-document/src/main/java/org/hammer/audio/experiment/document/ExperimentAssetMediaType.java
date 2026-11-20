package org.hammer.audio.experiment.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded content sniffing; unknown formats require an explicit local MIME confirmation. */
final class ExperimentAssetMediaType {
  private ExperimentAssetMediaType() {
    // Utility class.
  }

  static String detect(Path path) throws IOException {
    byte[] prefix;
    try (java.io.InputStream input = Files.newInputStream(path)) {
      prefix = input.readNBytes(512);
    }
    String ascii = new String(prefix, StandardCharsets.ISO_8859_1);
    if (ascii.startsWith("AAR1") || ascii.startsWith("AAR2")) {
      return "application/vnd.carstenartur.audio-recording";
    }
    if (ascii.startsWith("RIFF") && ascii.length() >= 12 && "WAVE".equals(ascii.substring(8, 12))) {
      return "audio/wav";
    }
    if (ascii.startsWith("fLaC")) {
      return "audio/flac";
    }
    if (ascii.startsWith("OggS")) {
      return "audio/ogg";
    }
    if (ascii.stripLeading().startsWith("{") || ascii.stripLeading().startsWith("[")) {
      return "application/json";
    }
    return null;
  }
}
