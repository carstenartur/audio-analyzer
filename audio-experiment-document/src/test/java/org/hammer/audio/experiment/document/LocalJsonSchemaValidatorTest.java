package org.hammer.audio.experiment.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.hammer.audio.plugin.document.DocumentValue;
import org.junit.jupiter.api.Test;

class LocalJsonSchemaValidatorTest {
  @Test
  void largeStringAndArrayBoundsKeepTheirExactIntegerMeaning() throws Exception {
    var validator = new LocalJsonSchemaValidator();
    DocumentValue string = DocumentValue.string("a");
    DocumentValue array = DocumentValue.array(List.of(string));
    for (String kind : List.of("Length", "Items")) {
      DocumentValue value = "Length".equals(kind) ? string : array;
      String minimum = "{\"min" + kind + "\":4294967296}";
      String maximum = "{\"max" + kind + "\":4294967296}";
      var rejected =
          validator.validate(minimum, PluginDocumentTestKit.schemaSha256(minimum), value, "/data");
      assertEquals(1, rejected.size());
      assertEquals(
          "Length".equals(kind) ? "schema-min-length" : "schema-min-items",
          rejected.getFirst().code());
      assertTrue(
          validator
              .validate(maximum, PluginDocumentTestKit.schemaSha256(maximum), value, "/data")
              .isEmpty());
    }
  }
}
