package org.hammer.audio.experiment.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 utilities used by portable document, workflow, schema and asset contracts. */
public final class DocumentHashes {

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
    byte[] result = digest.digest(value);
    StringBuilder hex = new StringBuilder(result.length * 2);
    for (byte item : result) {
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
