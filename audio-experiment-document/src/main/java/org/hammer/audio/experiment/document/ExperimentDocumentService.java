package org.hammer.audio.experiment.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import org.hammer.audio.plugin.AudioAnalyzerPlugin;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;

/**
 * Shared application-facing service for safe preview and normalization of portable experiment
 * documents.
 *
 * <p>Swing, HTTP and command-line adapters use this service instead of implementing independent
 * parsing, plugin-resolution or migration paths.
 */
public final class ExperimentDocumentService {

  private final ExperimentDocumentCodec codec;
  private final PluginDocumentCatalog pluginCatalog;
  private final WorkflowDslParser workflowParser = new WorkflowDslParser();

  /** Create a service for the supplied already installed and trusted plugins. */
  public ExperimentDocumentService(Collection<? extends AudioAnalyzerPlugin> installedPlugins) {
    this(new ExperimentDocumentCodec(), new PluginDocumentCatalog(installedPlugins));
  }

  ExperimentDocumentService(ExperimentDocumentCodec codec, PluginDocumentCatalog pluginCatalog) {
    this.codec = Objects.requireNonNull(codec, "codec");
    this.pluginCatalog = Objects.requireNonNull(pluginCatalog, "pluginCatalog");
  }

  /** Parse and preview untrusted document bytes without mutating application state. */
  public ExperimentDocumentPreview preview(byte[] source) throws ExperimentDocumentException {
    return pluginCatalog.preview(codec.decode(source), codec);
  }

  /** Read, bound and preview one untrusted stream without mutating application state. */
  public ExperimentDocumentPreview preview(InputStream source) throws IOException {
    return preview(readBounded(source));
  }

  /** Parse and preview one bounded document from disk without mutating application state. */
  public ExperimentDocumentPreview preview(Path source) throws IOException {
    return pluginCatalog.preview(codec.load(source), codec);
  }

  /** Return canonical normalized bytes after the same preview and plugin-resolution path. */
  public byte[] normalize(byte[] source) throws ExperimentDocumentException {
    return codec.encode(preview(source).document());
  }

  /** Read one bounded stream and return its canonical normalized representation. */
  public byte[] normalize(InputStream source) throws IOException {
    return normalize(readBounded(source));
  }

  /**
   * Save a normalized copy to a distinct path.
   *
   * <p>The imported source is never rewritten implicitly. Unknown optional sections remain in the
   * normalized copy.
   */
  public ExperimentDocumentPreview normalize(Path source, Path target) throws IOException {
    Path normalizedSource = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
    Path normalizedTarget = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
    if (normalizedSource.equals(normalizedTarget)) {
      throw new IOException("Normalize requires a distinct Save As target");
    }
    ExperimentDocumentPreview preview = preview(normalizedSource);
    codec.save(normalizedTarget, preview.document());
    return preview;
  }

  /** Rebuild the already hash-verified canonical workflow from a preview. */
  public Workflow workflow(ExperimentDocumentPreview preview) {
    Objects.requireNonNull(preview, "preview");
    return workflowParser.parse(preview.document().workflow().content());
  }

  /** Return the checked-in public v1 schema without resolving any external URI. */
  public byte[] schemaBytes() throws IOException {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    if (loader == null) {
      loader = ClassLoader.getSystemClassLoader();
    }
    try (InputStream input = loader.getResourceAsStream(ExperimentDocumentFormat.SCHEMA_RESOURCE)) {
      if (input == null) {
        throw new IOException(
            "Experiment document schema is unavailable: "
                + ExperimentDocumentFormat.SCHEMA_RESOURCE);
      }
      return readBounded(input);
    }
  }

  private static byte[] readBounded(InputStream source) throws IOException {
    Objects.requireNonNull(source, "source");
    byte[] bytes = source.readNBytes(ExperimentDocumentFormat.MAX_DOCUMENT_BYTES + 1);
    if (bytes.length > ExperimentDocumentFormat.MAX_DOCUMENT_BYTES) {
      throw new ExperimentDocumentException(
          "/", "max-bytes", "Experiment document exceeds the byte limit");
    }
    return bytes;
  }
}
