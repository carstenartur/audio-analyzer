package org.hammer.audio.experiment.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 utilities used by portable document, workflow, schema and asset contracts. */
public final class DocumentHashes {

  private static final int BUFFER_SIZE = 8192;

  private DocumentHashes() {
    // utility class
  }

  /** Return the lower-case SHA-256 of UTF-8 text. */
  public static String sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  /** Return the lower-case SHA-256 of bytes. */
  public static String sha256(byte[] value) {
    MessageDigest digest = newSha256();
    return hexadecimal(digest.digest(value));
  }

  /** Stream one local asset without loading it completely into memory. The stream remains open. */
  public static String sha256(InputStream input) throws IOException {
    MessageDigest digest = newSha256();
    byte[] buffer = new byte[BUFFER_SIZE];
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if (read > 0) {
        digest.update(buffer, 0, read);
      }
    }
    return hexadecimal(digest.digest());
  }

  /** Stream and hash one local asset path. */
  public static String sha256(Path path) throws IOException {
    try (InputStream input = Files.newInputStream(path)) {
      return sha256(input);
    }
  }

  private static String hexadecimal(byte[] value) {
    StringBuilder hex = new StringBuilder(value.length * 2);
    for (byte item : value) {
      hex.append(Character.forDigit((item >>> 4) & 0x0f, 16))
          .append(Character.forDigit(item & 0x0f, 16));
    }
    return hex.toString();
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
    }
  }
}
