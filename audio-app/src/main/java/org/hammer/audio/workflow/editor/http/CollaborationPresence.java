package org.hammer.audio.workflow.editor.http;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Bounds transport presence to explicitly supported public activity and viewport fields. */
public final class CollaborationPresence {
  private static final Set<String> NUMERIC_FIELDS =
      Set.of("cursor.x", "cursor.y", "viewport.x", "viewport.y", "viewport.zoom");
  private static final Set<String> IDENTIFIERS = Set.of("selection", "executingRun");

  private CollaborationPresence() {
    // Utility class.
  }

  /** Drops unknown or invalid values instead of forwarding arbitrary remote attributes. */
  public static Map<String, String> allowListed(Map<String, String> attributes) {
    Map<String, String> accepted = new ConcurrentHashMap<>();
    for (Map.Entry<String, String> entry : attributes.entrySet()) {
      String field =
          switch (entry.getKey()) {
            case "cursorX" -> "cursor.x";
            case "cursorY" -> "cursor.y";
            case "viewportX" -> "viewport.x";
            case "viewportY" -> "viewport.y";
            case "viewportZoom" -> "viewport.zoom";
            default -> entry.getKey();
          };
      String value = entry.getValue();
      if (value == null || value.length() > 128) {
        continue;
      }
      if (IDENTIFIERS.contains(field) && value.matches("[A-Za-z0-9._:/-]{0,128}")) {
        accepted.put(field, value);
      } else if (NUMERIC_FIELDS.contains(field) && numeric(value)) {
        accepted.put(field, value);
      }
    }
    return Map.copyOf(accepted);
  }

  private static boolean numeric(String value) {
    try {
      double number = Double.parseDouble(value);
      return Double.isFinite(number) && Math.abs(number) <= 1_000_000;
    } catch (NumberFormatException exception) {
      return false;
    }
  }
}
