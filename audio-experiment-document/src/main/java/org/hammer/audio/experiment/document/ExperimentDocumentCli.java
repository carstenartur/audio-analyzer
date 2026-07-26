package org.hammer.audio.experiment.document;

import java.io.IOException;
import java.nio.file.Path;

/** Headless validation and normalization entry point for portable experiment documents. */
public final class ExperimentDocumentCli {

  private ExperimentDocumentCli() {
    // utility class
  }

  /**
   * Validate or normalize a portable document.
   *
   * <p>Usage: {@code validate input.audioexp} or {@code normalize input.audioexp output.audioexp}.
   */
  public static void main(String[] args) {
    int exit = run(args, System.out, System.err);
    if (exit != 0) {
      System.exit(exit);
    }
  }

  static int run(String[] args, java.io.PrintStream out, java.io.PrintStream error) {
    if (args.length < 2 || args.length > 3) {
      error.println(
          "Usage: validate <input.audioexp> | normalize <input.audioexp> <output.audioexp>");
      return 2;
    }
    ExperimentDocumentCodec codec = new ExperimentDocumentCodec();
    Path source = Path.of(args[1]);
    try {
      ExperimentDocument document = codec.load(source);
      ExperimentDocumentPreview preview = PluginDocumentCatalog.empty().preview(document, codec);
      if ("validate".equals(args[0]) && args.length == 2) {
        out.println("VALID " + preview.canonicalSha256());
        out.println("experiment=" + preview.document().experiment().name());
        out.println("executionAllowed=" + preview.executionAllowed());
        out.println("readOnly=" + preview.readOnly());
        preview
            .diagnostics()
            .forEach(
                item -> out.println(item.severity() + " " + item.pointer() + " " + item.message()));
        return preview.executionAllowed() ? 0 : 1;
      }
      if ("normalize".equals(args[0]) && args.length == 3) {
        codec.save(Path.of(args[2]), preview.document());
        out.println("SAVED " + preview.canonicalSha256());
        return 0;
      }
      error.println("Unsupported command or argument count");
      return 2;
    } catch (ExperimentDocumentException exception) {
      error.println(exception.code() + " " + exception.pointer() + " " + exception.getMessage());
      return 1;
    } catch (IOException | RuntimeException exception) {
      error.println(exception.getClass().getSimpleName() + ": " + exception.getMessage());
      return 1;
    }
  }
}
