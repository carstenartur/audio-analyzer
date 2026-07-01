package org.hammer.audio.workflow;

import java.util.List;

/** Canonical built-in workflow data types. */
public final class DataTypes {

  public static final DataType DATASET = new DataType("Dataset");
  public static final DataType AUDIO_BLOCK = new DataType("AudioBlock");
  public static final DataType SPECTRUM = new DataType("Spectrum");
  public static final DataType FEATURE_SET = new DataType("FeatureSet");
  public static final DataType CLASSIFICATION_RESULT = new DataType("ClassificationResult");
  public static final DataType LOCALIZATION_RESULT = new DataType("LocalizationResult");
  public static final DataType BENCHMARK_RESULT = new DataType("BenchmarkResult");
  public static final DataType REPORT = new DataType("Report");

  private DataTypes() {
    // utility class
  }

  public static List<DataType> builtIns() {
    return List.of(
        DATASET,
        AUDIO_BLOCK,
        SPECTRUM,
        FEATURE_SET,
        CLASSIFICATION_RESULT,
        LOCALIZATION_RESULT,
        BENCHMARK_RESULT,
        REPORT);
  }
}
