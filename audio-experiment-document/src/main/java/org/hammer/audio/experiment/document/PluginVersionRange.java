package org.hammer.audio.experiment.document;

import java.math.BigInteger;
import java.util.regex.Pattern;

/** Explicit numeric package intervals; unsupported expressions fail closed. */
final class PluginVersionRange {

  private static final Pattern NUMERIC_VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+)*");

  private PluginVersionRange() {
    // utility class
  }

  static boolean matches(String range, String installed) {
    if ("*".equals(range) || installed.equals(range)) {
      return true;
    }
    if (!validInterval(range, installed)) {
      return false;
    }
    String[] ends = range.substring(1, range.length() - 1).split(",", -1);
    if (ends.length != 2 || (ends[0].isBlank() && ends[1].isBlank())) {
      return false;
    }
    String lower = ends[0].trim();
    String upper = ends[1].trim();
    return matchesBound(installed, lower, range.charAt(0) == '[', true)
        && matchesBound(installed, upper, range.charAt(range.length() - 1) == ']', false);
  }

  private static boolean validInterval(String range, String installed) {
    if (range.length() < 3
        || range.length() > 256
        || installed.length() > 128
        || !NUMERIC_VERSION.matcher(installed).matches()) {
      return false;
    }
    char first = range.charAt(0);
    char last = range.charAt(range.length() - 1);
    return (first == '[' || first == '(') && (last == ']' || last == ')');
  }

  private static boolean matchesBound(
      String installed, String bound, boolean inclusive, boolean lower) {
    if (bound.isEmpty()) {
      return true;
    }
    if (!NUMERIC_VERSION.matcher(bound).matches()) {
      return false;
    }
    int comparison = compare(installed, bound);
    if (comparison == 0) {
      return inclusive;
    }
    return lower ? comparison > 0 : comparison < 0;
  }

  private static int compare(String left, String right) {
    String[] leftParts = left.split("\\.");
    String[] rightParts = right.split("\\.");
    for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
      BigInteger a = index < leftParts.length ? new BigInteger(leftParts[index]) : BigInteger.ZERO;
      BigInteger b =
          index < rightParts.length ? new BigInteger(rightParts[index]) : BigInteger.ZERO;
      int comparison = a.compareTo(b);
      if (comparison != 0) {
        return comparison;
      }
    }
    return 0;
  }
}
