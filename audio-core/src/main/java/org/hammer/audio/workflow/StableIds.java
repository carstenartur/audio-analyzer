package org.hammer.audio.workflow;

import java.util.regex.Pattern;

final class StableIds {

  private static final Pattern STABLE_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");

  private StableIds() {
    throw new UnsupportedOperationException("Utility class");
  }

  static void requireStable(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    if (!STABLE_ID_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException(
          fieldName + " must match " + STABLE_ID_PATTERN.pattern() + ": " + value);
    }
  }
}
