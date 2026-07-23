package org.hammer.audio.experiment.document;

/** Public identity and safety limits for portable Audio Analyzer experiment documents. */
public final class ExperimentDocumentFormat {

  /** Preferred file extension without a leading dot. */
  public static final String FILE_EXTENSION = "audioexp";

  /** Dedicated structured JSON media type. */
  public static final String MEDIA_TYPE =
      "application/vnd.carstenartur.audio-analyzer.experiment+json";

  /** Stable document format identifier. */
  public static final String FORMAT_ID = "io.github.carstenartur.audio-analyzer.experiment";

  /** Stable workflow payload format identifier. */
  public static final String WORKFLOW_FORMAT_ID =
      "io.github.carstenartur.audio-analyzer.workflow-dsl";

  /** Current outer-envelope version. */
  public static final int VERSION = 1;

  /** Current embedded workflow payload version. */
  public static final int WORKFLOW_VERSION = 1;

  /** Public schema resource path. */
  public static final String SCHEMA_RESOURCE =
      "schemas/audio-analyzer-experiment-v1.schema.json";

  /** Maximum accepted UTF-8 document bytes. */
  public static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;

  /** Maximum object/array nesting depth. */
  public static final int MAX_NESTING_DEPTH = 64;

  /** Maximum collection elements at any one level. */
  public static final int MAX_COLLECTION_SIZE = 10_000;

  /** Maximum string length. */
  public static final int MAX_STRING_LENGTH = 1_000_000;

  private ExperimentDocumentFormat() {
    // utility class
  }
}
