package org.hammer.audio.experiment.document.examples;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.hammer.audio.plugin.document.DocumentDiagnostic;
import org.hammer.audio.plugin.document.DocumentValidationResult;
import org.hammer.audio.plugin.document.DocumentValue;
import org.hammer.audio.plugin.document.ExperimentDocumentContribution;
import org.hammer.audio.plugin.document.ExperimentSectionMigration;

/** Copyable plugin-author example using only the framework-neutral stable plugin API. */
public final class GainDocumentContribution implements ExperimentDocumentContribution {
  private static final String SCHEMA =
      "{\"additionalProperties\":false,\"properties\":{\"gain\":{\"description\":\"Linear gain;"
          + " algorithm"
          + " gain/2\",\"minimum\":0,\"type\":\"number\"}},\"required\":[\"gain\"],\"type\":\"object\"}";

  @Override
  public String sectionId() {
    return "gain-settings";
  }

  @Override
  public int schemaVersion() {
    return 2;
  }

  @Override
  public String algorithmVersion() {
    return "gain/2";
  }

  @Override
  public String name() {
    return "Example gain";
  }

  @Override
  public String description() {
    return "Versioned gain parameters with an explicit legacy alias migration";
  }

  @Override
  public boolean requiredForExecution() {
    return false;
  }

  @Override
  public String schemaId() {
    return "urn:audio-analyzer:example:gain-settings:2";
  }

  @Override
  public String schemaJson() {
    return SCHEMA;
  }

  @Override
  public String schemaSha256() {
    return "32a8d7454d439f756c2b85dc940546db8c1a46357865142c561c726198b3d13f";
  }

  @Override
  public List<ExperimentSectionMigration> migrations() {
    return List.of(new LegacyGainMigration());
  }

  @Override
  public DocumentValidationResult validateAndNormalize(DocumentValue value) {
    if (!(value instanceof DocumentValue.ObjectValue object)
        || !(object.fields().get("gain") instanceof DocumentValue.NumberValue gain)) {
      throw new IllegalArgumentException("Gain parameters must contain a numeric gain field");
    }
    if (gain.value().compareTo(java.math.BigDecimal.valueOf(10_000)) > 0) {
      return new DocumentValidationResult(
          value,
          List.of(
              new DocumentDiagnostic(
                  DocumentDiagnostic.Severity.ERROR,
                  "/gain",
                  "gain-too-large",
                  "Gain exceeds the example algorithm range")));
    }
    return DocumentValidationResult.valid(value);
  }

  private static final class LegacyGainMigration implements ExperimentSectionMigration {
    @Override
    public int fromVersion() {
      return 1;
    }

    @Override
    public int toVersion() {
      return 2;
    }

    @Override
    // Each migration owns its sorted copy; no mutable map is shared between callbacks.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    public DocumentValue migrate(DocumentValue source) {
      if (!(source instanceof DocumentValue.ObjectValue object)) {
        throw new IllegalArgumentException("Legacy gain parameters must be an object");
      }
      Map<String, DocumentValue> fields = new TreeMap<>(object.fields());
      DocumentValue legacy = fields.remove("gainLinear");
      if (legacy != null && fields.putIfAbsent("gain", legacy) != null) {
        throw new IllegalArgumentException("Ambiguous legacy gain fields");
      }
      return DocumentValue.object(fields);
    }
  }
}
