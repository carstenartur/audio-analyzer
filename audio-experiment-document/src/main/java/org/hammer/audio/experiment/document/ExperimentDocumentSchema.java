package org.hammer.audio.experiment.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Validates against the complete bundled public schema; remote retrieval is disabled. */
final class ExperimentDocumentSchema {

  private static final Schema SCHEMA = load();

  private ExperimentDocumentSchema() {
    // utility class
  }

  static void validate(JsonNode document) throws ExperimentDocumentException {
    List<Error> errors =
        SCHEMA.validate(
            document,
            context ->
                context.executionConfig(
                    config -> config.formatAssertionsEnabled(true).failFast(true)));
    if (!errors.isEmpty()) {
      Error error = errors.getFirst();
      throw new ExperimentDocumentException(
          error.getInstanceLocation().toString(), "schema-invalid", error.getMessage());
    }
  }

  private static Schema load() {
    try (InputStream input =
        ExperimentDocumentSchema.class.getResourceAsStream(
            "/" + ExperimentDocumentFormat.SCHEMA_RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("Bundled experiment schema is missing");
      }
      JsonNode schema = new ObjectMapper().readTree(input);
      SchemaRegistry registry =
          SchemaRegistry.withDefaultDialect(
              SpecificationVersion.DRAFT_2020_12,
              builder ->
                  builder.resourceLoaders(
                      loaders ->
                          loaders.add(
                              iri -> {
                                throw new IllegalArgumentException(
                                    "External schema retrieval is disabled");
                              })));
      return registry.getSchema(schema);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot read bundled experiment schema", exception);
    }
  }
}
