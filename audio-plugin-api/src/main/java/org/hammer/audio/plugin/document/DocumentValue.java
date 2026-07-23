package org.hammer.audio.plugin.document;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable, framework-neutral value model for bounded experiment-document sections.
 *
 * <p>The host owns JSON parsing and serialization. Plugins receive only this data model and cannot
 * request polymorphic object deserialization.
 */
public sealed interface DocumentValue
    permits DocumentValue.ArrayValue,
        DocumentValue.BooleanValue,
        DocumentValue.NullValue,
        DocumentValue.NumberValue,
        DocumentValue.ObjectValue,
        DocumentValue.StringValue {

  /** Create an immutable object value with lexicographically ordered keys. */
  static ObjectValue object(Map<String, ? extends DocumentValue> fields) {
    return new ObjectValue(fields);
  }

  /** Create an immutable array value. */
  static ArrayValue array(List<? extends DocumentValue> values) {
    return new ArrayValue(values);
  }

  /** Create a string value. */
  static StringValue string(String value) {
    return new StringValue(value);
  }

  /** Create a canonical finite decimal value. */
  static NumberValue number(BigDecimal value) {
    return new NumberValue(value);
  }

  /** Create a boolean value. */
  static BooleanValue bool(boolean value) {
    return new BooleanValue(value);
  }

  /** Return the singleton null value. */
  static NullValue nullValue() {
    return NullValue.INSTANCE;
  }

  /**
   * Immutable object value.
   *
   * @param fields lexicographically ordered immutable field map
   */
  record ObjectValue(Map<String, DocumentValue> fields) implements DocumentValue {

    /** Validate and defensively copy all fields. */
    public ObjectValue {
      Objects.requireNonNull(fields, "fields");
      TreeMap<String, DocumentValue> copy = new TreeMap<>();
      fields.forEach(
          (key, value) ->
              copy.put(
                  requireNonBlank(key, "object field name"),
                  Objects.requireNonNull(value, "object field value")));
      fields = Collections.unmodifiableMap(copy);
    }
  }

  /**
   * Immutable array value.
   *
   * @param values ordered immutable values
   */
  record ArrayValue(List<DocumentValue> values) implements DocumentValue {

    /** Validate and defensively copy all values. */
    public ArrayValue {
      Objects.requireNonNull(values, "values");
      ArrayList<DocumentValue> copy = new ArrayList<>(values.size());
      for (DocumentValue value : values) {
        copy.add(Objects.requireNonNull(value, "array value"));
      }
      values = Collections.unmodifiableList(copy);
    }
  }

  /**
   * Immutable string value.
   *
   * @param value text value
   */
  record StringValue(String value) implements DocumentValue {

    /** Validate the string value. */
    public StringValue {
      Objects.requireNonNull(value, "value");
    }
  }

  /**
   * Canonical finite decimal value.
   *
   * @param value canonical decimal value
   */
  record NumberValue(BigDecimal value) implements DocumentValue {

    /** Normalize equivalent decimal representations. */
    public NumberValue {
      Objects.requireNonNull(value, "value");
      value = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }
  }

  /**
   * Immutable boolean value.
   *
   * @param value boolean value
   */
  record BooleanValue(boolean value) implements DocumentValue {}

  /** Singleton null value. */
  enum NullValue implements DocumentValue {
    /** The only null value instance. */
    INSTANCE
  }

  private static String requireNonBlank(String value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
