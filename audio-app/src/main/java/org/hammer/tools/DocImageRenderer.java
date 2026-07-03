package org.hammer.tools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.hammer.audio.analysis.MeasurementCalculator;
import org.hammer.audio.analysis.MeasurementSnapshot;
import org.hammer.audio.analysis.PeakHoldSpectrum;
import org.hammer.audio.analysis.SpectrumAnalyzer;
import org.hammer.audio.analysis.SpectrumAverager;
import org.hammer.audio.analysis.SpectrumSnapshot;
import org.hammer.audio.analysis.WaveformTrigger;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.diagnosis.DiagnosisAnalyzer;
import org.hammer.audio.diagnosis.DiagnosisFinding;
import org.hammer.audio.diagnosis.DiagnosisSnapshot;
import org.hammer.audio.signal.SineGenerator;
import org.hammer.audio.signal.SquareGenerator;
import org.hammer.audio.spectrogram.SpectrogramAnalyzer;
import org.hammer.audio.spectrogram.SpectrogramHistory;
import org.hammer.audio.ui.theme.PlotRenderTheme;

/**
 * Headless utility that renders deterministic PNG screenshots used in the README and feature
 * documentation.
 *
 * <p>Re-run with:
 *
 * <pre>
 *   ./mvnw -pl audio-app -am package -DskipTests
 *   java -cp "audio-app/target/classes:audio-app/target/lib/*" \
 *        org.hammer.tools.DocImageRenderer docs/images
 * </pre>
 *
 * <p>On Windows, replace {@code :} with {@code ;} in the classpath and use {@code ^} instead of
 * {@code \} for line continuation (or run the command on a single line).
 *
 * <p>The output directory defaults to {@code docs/images} when no argument is given. The README
 * screenshot is written to {@code screenshot.png}; feature images are written to the {@code
 * features/} child directory.
 */
@SuppressWarnings("PMD.CouplingBetweenObjects")
public final class DocImageRenderer {

  private static final int W = 760;
  private static final int H = 320;
  private static final int WB_H = 480;
  private static final int DASHBOARD_W = 1600;
  private static final int DASHBOARD_H = 1000;
  private static final int DASHBOARD_SPECTROGRAM_HISTORY_FRAMES = 180;
  private static final int DASHBOARD_SPECTROGRAM_SEED_BLOCKS = 64;
  private static final int DASHBOARD_WAVEFORM_VISIBLE_SAMPLES = 2200;
  private static final float SYNTHETIC_SPECTROGRAM_PULSE_BASE = 0.45f;
  private static final float SYNTHETIC_SPECTROGRAM_PULSE_SWING = 0.55f;
  private static final double SYNTHETIC_SPECTROGRAM_PULSE_PERIOD = 18.0;
  private static final float SYNTHETIC_SPECTROGRAM_BAND_POSITION = 0.72f;
  private static final float SYNTHETIC_SPECTROGRAM_BAND_WIDTH = 18f;
  private static final AudioFormatDescriptor MONO_44K = new AudioFormatDescriptor(44100f, 1, 16);
  private static final int FFT = 1024;

  private DocImageRenderer() {}

  /**
   * @param args optional output directory; defaults to {@code docs/images/features}
   * @throws IOException if any of the PNGs cannot be written
   */
  public static void main(String[] args) throws IOException {
    Path imageDir = Path.of(args.length > 0 ? args[0] : "docs/images");
    Path featureDir = imageDir.resolve("features");
    Files.createDirectories(featureDir);

    writePng(imageDir.resolve("screenshot.png"), renderDashboardScreenshot());
    writePng(featureDir.resolve("waveform-trigger.png"), renderTrigger());
    writePng(featureDir.resolve("spectrum-peak-hold.png"), renderSpectrumPeakHold());
    writePng(featureDir.resolve("recording-format.png"), renderRecordingFormat());
    writePng(featureDir.resolve("ab-comparison.png"), renderAbComparison());
    writePng(featureDir.resolve("simulation-workbench.png"), renderSimulationWorkbench());
    writePng(featureDir.resolve("playback-explorer.png"), renderPlaybackExplorer());
    writePng(
        featureDir.resolve("imported-recording-workbench.png"), renderImportedRecordingWorkbench());
    writePng(featureDir.resolve("generator-calibration.png"), renderGeneratorCalibration());
  }

  /**
   * Render the deterministic README dashboard screenshot.
   *
   * @return a 1600x1000 PNG-ready image showing a 440 Hz demo signal and the main dashboard panels
   */
  public static BufferedImage renderDashboardScreenshot() {
    SineGenerator gen = new SineGenerator(MONO_44K, 440.0, 0.7f);
    AudioBlock block = gen.nextBlock(4096);
    SpectrumAnalyzer spectrumAnalyzer = new SpectrumAnalyzer(FFT, 0, MONO_44K.sampleRate());
    SpectrumSnapshot spectrum = spectrumAnalyzer.analyze(block);
    MeasurementSnapshot measurement = new MeasurementCalculator().calculate(block, spectrum);
    SpectrogramAnalyzer spectrogramAnalyzer =
        new SpectrogramAnalyzer(
            FFT, 0, MONO_44K.sampleRate(), DASHBOARD_SPECTROGRAM_HISTORY_FRAMES);
    for (int i = 0; i < DASHBOARD_SPECTROGRAM_SEED_BLOCKS; i++) {
      spectrogramAnalyzer.analyze(gen.nextBlock(FFT));
    }
    SpectrogramHistory history = spectrogramAnalyzer.history();
    DiagnosisSnapshot diagnosis = new DiagnosisAnalyzer().analyze(block, spectrum, history, null);

    BufferedImage img = new BufferedImage(DASHBOARD_W, DASHBOARD_H, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    try {
      applyHints(g);
      g.setColor(new Color(19, 24, 32));
      g.fillRect(0, 0, DASHBOARD_W, DASHBOARD_H);
      drawAppChrome(g);
      drawControlBar(g, measurement, spectrum);
      drawWaveform(g, new Rectangle(28, 150, 1544, 330), block);
      drawSpectrumPanel(
          g,
          new Rectangle(28, 508, 754, 245),
          spectrum,
          "Spectrum — 440 Hz sine demo",
          PlotRenderTheme.SPECTRUM_LINE);
      drawMeasurements(g, new Rectangle(810, 508, 762, 245), measurement);
      drawSpectrogram(g, new Rectangle(28, 782, 1050, 180));
      drawDiagnosis(g, new Rectangle(1104, 782, 468, 180), diagnosis);
    } finally {
      g.dispose();
    }
    return img;
  }

  private static void drawAppChrome(Graphics2D g) {
    g.setColor(new Color(31, 38, 48));
    g.fillRect(0, 0, DASHBOARD_W, 56);
    g.setColor(PlotRenderTheme.TEXT_PRIMARY);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
    g.drawString("Audio Analyzer", 24, 35);
    g.setFont(PlotRenderTheme.LABEL_FONT);
    g.setColor(PlotRenderTheme.TEXT_MUTED);
    g.drawString("File   View   Plugins   Help", 210, 35);
    g.setColor(new Color(52, 168, 83));
    g.fillRoundRect(DASHBOARD_W - 170, 16, 132, 24, 12, 12);
    g.setColor(Color.WHITE);
    g.drawString("Demo frozen", DASHBOARD_W - 148, 33);
  }

  private static void drawControlBar(
      Graphics2D g, MeasurementSnapshot measurement, SpectrumSnapshot spectrum) {
    Rectangle controls = new Rectangle(28, 76, 1544, 50);
    g.setColor(new Color(35, 43, 55));
    g.fillRoundRect(controls.x, controls.y, controls.width, controls.height, 14, 14);
    g.setColor(new Color(72, 84, 102));
    g.drawRoundRect(controls.x, controls.y, controls.width, controls.height, 14, 14);
    String[] items = {
      "Input: Demo mode",
      "Demo: Sine",
      "Format: 44.1 kHz / mono / 16-bit",
      String.format(Locale.ROOT, "Peak: %.1f Hz", strongestFrequency(spectrum)),
      String.format(Locale.ROOT, "RMS: %.3f", measurement.rms()),
      String.format(Locale.ROOT, "Level: %.2f", measurement.peakLevel())
    };
    g.setFont(PlotRenderTheme.LABEL_FONT);
    int x = controls.x + 18;
    for (String item : items) {
      drawPill(g, x, controls.y + 12, item);
      x += g.getFontMetrics().stringWidth(item) + 42;
    }
  }

  private static void drawPill(Graphics2D g, int x, int y, String text) {
    int width = g.getFontMetrics().stringWidth(text) + 20;
    g.setColor(new Color(47, 58, 74));
    g.fillRoundRect(x, y, width, 26, 13, 13);
    g.setColor(PlotRenderTheme.TEXT_PRIMARY);
    g.drawString(text, x + 10, y + 18);
  }

  private static void drawWaveform(Graphics2D g, Rectangle plot, AudioBlock block) {
    PlotRenderTheme.drawPlotBackground(g, plot.width, plot.height, plot);
    PlotRenderTheme.drawGrid(g, plot, 16, 8);
    PlotRenderTheme.drawTitle(g, plot.x + 12, plot.y + 22, "Waveform — reproducible 440 Hz sine");
    PlotRenderTheme.drawYAxisLabel(g, plot, "Amplitude [-1..1]");
    PlotRenderTheme.drawYTicks(
        g, plot, new double[] {0.0d, 0.5d, 1.0d}, new String[] {"+1", "0", "-1"});
    PlotRenderTheme.drawXAxisLabel(g, plot, "Time [ms]");
    float[] samples = block.channelView(0);
    int visible = Math.min(samples.length, DASHBOARD_WAVEFORM_VISIBLE_SAMPLES);
    double durationMs = 1000.0d * Math.max(0, visible - 1) / MONO_44K.sampleRate();
    PlotRenderTheme.drawXTicks(
        g,
        plot,
        new double[] {0.0d, 0.5d, 1.0d},
        new String[] {
          "0 ms",
          String.format(Locale.ROOT, "%.1f ms", durationMs / 2.0d),
          String.format(Locale.ROOT, "%.1f ms", durationMs)
        });
    int centerY = plot.y + plot.height / 2;
    int amplitude = plot.height / 2 - 38;
    Path2D path = new Path2D.Float();
    for (int i = 0; i < visible; i++) {
      double x = plot.x + (double) i * (plot.width - 1) / Math.max(1, visible - 1);
      double y = centerY - Math.max(-1f, Math.min(1f, samples[i])) * amplitude;
      if (i == 0) {
        path.moveTo(x, y);
      } else {
        path.lineTo(x, y);
      }
    }
    g.setColor(PlotRenderTheme.CENTER_LINE);
    g.drawLine(plot.x, centerY, plot.x + plot.width, centerY);
    g.setColor(PlotRenderTheme.WAVEFORM_LEFT);
    g.setStroke(PlotRenderTheme.TRACE_STROKE);
    g.draw(path);
    g.setColor(PlotRenderTheme.TEXT_MUTED);
    g.setFont(PlotRenderTheme.LABEL_FONT);
    g.drawString("Frozen demo buffer, amplitude 0.70, no clipping", plot.x + 12, plot.y + 44);
  }

  private static void drawMeasurements(
      Graphics2D g, Rectangle panel, MeasurementSnapshot measurement) {
    drawPanelShell(g, panel, "Measurements");
    String[] rows = {
      String.format(
          Locale.ROOT, "Dominant frequency     %.1f Hz", measurement.dominantFrequencyHz()),
      String.format(Locale.ROOT, "RMS level              %.3f", measurement.rms()),
      String.format(Locale.ROOT, "Peak level             %.3f", measurement.peakLevel()),
      "Clipping               no",
      "Stereo delay           n/a (mono demo)",
      "Confidence             n/a"
    };
    g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 18));
    int y = panel.y + 66;
    for (String row : rows) {
      g.setColor(new Color(43, 53, 68));
      g.fillRoundRect(panel.x + 24, y - 24, panel.width - 48, 34, 10, 10);
      g.setColor(PlotRenderTheme.TEXT_PRIMARY);
      g.drawString(row, panel.x + 42, y);
      y += 34;
    }
  }

  private static void drawSpectrogram(Graphics2D g, Rectangle panel) {
    drawPanelShell(g, panel, "Spectrogram / waterfall");
    int x0 = panel.x + 20;
    int y0 = panel.y + 44;
    int w = panel.width - 40;
    int h = panel.height - 64;
    for (int x = 0; x < w; x++) {
      float pulse =
          SYNTHETIC_SPECTROGRAM_PULSE_BASE
              + SYNTHETIC_SPECTROGRAM_PULSE_SWING
                  * (float) Math.sin(x / SYNTHETIC_SPECTROGRAM_PULSE_PERIOD);
      for (int y = 0; y < h; y++) {
        float band =
            Math.max(
                    0f,
                    1f
                        - Math.abs(y - h * SYNTHETIC_SPECTROGRAM_BAND_POSITION)
                            / SYNTHETIC_SPECTROGRAM_BAND_WIDTH)
                * pulse;
        g.setColor(
            new Color(
                16,
                Math.min(210, 55 + (int) (band * 155)),
                Math.min(255, 90 + (int) (band * 165))));
        g.drawLine(x0 + x, y0 + y, x0 + x, y0 + y);
      }
    }
    Rectangle plot = new Rectangle(x0, y0, w, h);
    PlotRenderTheme.drawGrid(g, plot, 8, 4);
    PlotRenderTheme.drawYAxisLabel(g, plot, "Frequency [Hz]");
    PlotRenderTheme.drawYTicks(
        g, plot, new double[] {0.0d, 0.5d, 1.0d}, new String[] {"22.1 kHz", "11.0 kHz", "0 Hz"});
    PlotRenderTheme.drawXTicks(
        g, plot, new double[] {0.0d, 0.5d, 1.0d}, new String[] {"0.0 s", "0.5 s", "1.0 s"});
    PlotRenderTheme.drawXAxisLabel(g, plot, "Time [s; older → newer]");
    PlotRenderTheme.drawLabel(g, x0 + w - 132, y0 + 14, "Color: relative magnitude [-]");
  }

  private static void drawDiagnosis(Graphics2D g, Rectangle panel, DiagnosisSnapshot diagnosis) {
    drawPanelShell(g, panel, "Diagnosis");
    g.setFont(PlotRenderTheme.LABEL_FONT);
    int y = panel.y + 58;
    if (diagnosis.findings().isEmpty()) {
      g.setColor(new Color(150, 210, 180));
      g.drawString("INFO   Stable single-tone demo; no findings.", panel.x + 24, y);
      return;
    }
    for (DiagnosisFinding finding : diagnosis.findings()) {
      g.setColor(PlotRenderTheme.TEXT_PRIMARY);
      g.drawString(
          String.format(
              Locale.ROOT,
              "%s   %s (conf %.2f)",
              finding.severity(),
              finding.message(),
              finding.confidence()),
          panel.x + 24,
          y);
      y += 28;
      if (y > panel.y + panel.height - 22) {
        break;
      }
    }
  }

  private static void drawPanelShell(Graphics2D g, Rectangle panel, String title) {
    g.setColor(PlotRenderTheme.PANEL_BACKGROUND);
    g.fillRoundRect(panel.x, panel.y, panel.width, panel.height, 14, 14);
    g.setColor(new Color(72, 84, 102));
    g.drawRoundRect(panel.x, panel.y, panel.width, panel.height, 14, 14);
    g.setColor(PlotRenderTheme.TEXT_PRIMARY);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
    g.drawString(title, panel.x + 16, panel.y + 28);
  }

  private static double strongestFrequency(SpectrumSnapshot spectrum) {
    int peakBin = 0;
    float peak = 0f;
    for (int i = 1; i < spectrum.binCount(); i++) {
      if (spectrum.magnitude(i) > peak) {
        peak = spectrum.magnitude(i);
        peakBin = i;
      }
    }
    return spectrum.frequencyOfBin(peakBin);
  }

  static BufferedImage renderTrigger() {
    SineGenerator gen = new SineGenerator(MONO_44K, 220.0, 0.7f);
    AudioBlock block = gen.nextBlock(4096);
    WaveformTrigger trigger = new WaveformTrigger(1024);
    trigger.setMode(WaveformTrigger.Mode.NORMAL);
    trigger.setHoldoffFrames(64);
    WaveformTrigger.TriggeredView view = trigger.process(block, 0).orElseThrow();

    BufferedImage img = createImage();
    Graphics2D g = img.createGraphics();
    try {
      applyHints(g);
      Rectangle plot = new Rectangle(48, 24, W - 64, H - 58);
      PlotRenderTheme.drawPlotBackground(g, W, H, plot);
      PlotRenderTheme.drawGrid(g, plot, 10, 8);
      PlotRenderTheme.drawTitle(g, plot.x, 16, "Waveform (triggered)");
      PlotRenderTheme.drawYAxisLabel(g, plot, "Amplitude [-1..1]");
      PlotRenderTheme.drawYTicks(
          g, plot, new double[] {0.0d, 0.5d, 1.0d}, new String[] {"+1", "0", "-1"});
      PlotRenderTheme.drawXAxisLabel(g, plot, "Sample index");

      float[] samples = view.samplesView();
      int n = samples.length;
      int centerY = plot.y + plot.height / 2;
      int amplitude = plot.height / 2 - 8;
      int[] xs = new int[n];
      int[] ys = new int[n];
      for (int i = 0; i < n; i++) {
        xs[i] = plot.x + (int) ((long) i * (plot.width - 1) / Math.max(1, n - 1));
        ys[i] = centerY - (int) (Math.max(-1f, Math.min(1f, samples[i])) * amplitude);
      }
      g.setColor(PlotRenderTheme.CENTER_LINE);
      g.setStroke(PlotRenderTheme.AXIS_STROKE);
      g.drawLine(plot.x, centerY, plot.x + plot.width - 1, centerY);

      g.setColor(PlotRenderTheme.WAVEFORM_LEFT);
      g.setStroke(PlotRenderTheme.TRACE_STROKE);
      g.drawPolyline(xs, ys, n);

      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.setFont(PlotRenderTheme.LABEL_FONT);
      g.drawString(
          String.format(
              Locale.ROOT,
              "Trig: FIRED  Slope: rising  Level: %+.2f  view=1024 samples",
              view.level()),
          plot.x,
          32);
      PlotRenderTheme.drawXTicks(
          g,
          plot,
          new double[] {0.0d, 0.5d, 1.0d},
          new String[] {"0", Integer.toString(n / 2), Integer.toString(n - 1)});
    } finally {
      g.dispose();
    }
    return img;
  }

  static BufferedImage renderSpectrumPeakHold() {
    SineGenerator gen = new SineGenerator(MONO_44K, 1200.0, 0.6f);
    SpectrumAnalyzer analyzer = new SpectrumAnalyzer(FFT, 0, MONO_44K.sampleRate());
    SpectrumAverager avg = new SpectrumAverager(0.3f);
    PeakHoldSpectrum peak = new PeakHoldSpectrum(0.999f);

    for (int i = 0; i < 12; i++) {
      AudioBlock b = gen.nextBlock(FFT);
      SpectrumSnapshot s = analyzer.analyze(b);
      avg.update(s.magnitudesView());
      peak.update(s.magnitudesView());
    }
    SquareGenerator extra = new SquareGenerator(MONO_44K, 4400.0, 0.4f);
    AudioBlock burst = extra.nextBlock(FFT);
    SpectrumSnapshot burstSnap = analyzer.analyze(burst);
    peak.update(burstSnap.magnitudesView());

    BufferedImage img = createImage();
    Graphics2D g = img.createGraphics();
    try {
      applyHints(g);
      Rectangle plot = new Rectangle(52, 24, W - 68, H - 58);
      PlotRenderTheme.drawPlotBackground(g, W, H, plot);
      PlotRenderTheme.drawGrid(g, plot, 10, 8);
      PlotRenderTheme.drawTitle(g, plot.x, 16, "Spectrum (averaged + peak hold)");
      PlotRenderTheme.drawYAxisLabel(g, plot, "Magnitude [dB rel. peak]");
      PlotRenderTheme.drawYTicks(
          g, plot, new double[] {0.0d, 0.5d, 1.0d}, new String[] {"0 dB", "-40 dB", "-80 dB"});
      PlotRenderTheme.drawXTicks(
          g, plot, new double[] {0.0d, 0.5d, 1.0d}, new String[] {"0 Hz", "11025 Hz", "22050 Hz"});
      PlotRenderTheme.drawXAxisLabel(g, plot, "Frequency [Hz]");

      float[] live = avg.averageView();
      float[] held = peak.peaks();
      int bins = live.length;
      float maxMag = 1e-6f;
      for (float v : live) {
        if (Math.abs(v) > maxMag) {
          maxMag = Math.abs(v);
        }
      }
      for (float v : held) {
        if (Math.abs(v) > maxMag) {
          maxMag = Math.abs(v);
        }
      }
      int floor = plot.y + plot.height - 1;
      int top = plot.y + 36;
      int[] xs = new int[bins];
      int[] ysLive = new int[bins];
      int[] ysPeak = new int[bins];
      for (int i = 0; i < bins; i++) {
        xs[i] = plot.x + (int) ((long) i * (plot.width - 1) / Math.max(1, bins - 1));
        ysLive[i] = floor - (int) ((Math.abs(live[i]) / maxMag) * (floor - top));
        ysPeak[i] = floor - (int) ((Math.abs(held[i]) / maxMag) * (floor - top));
      }
      g.setColor(PlotRenderTheme.SPECTRUM_LINE);
      g.setStroke(PlotRenderTheme.TRACE_STROKE);
      g.drawPolyline(xs, ysLive, bins);
      g.setColor(PlotRenderTheme.HIGHLIGHT);
      g.setStroke(PlotRenderTheme.PEAK_STROKE);
      g.drawPolyline(xs, ysPeak, bins);

      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.setFont(PlotRenderTheme.LABEL_FONT);
      g.drawString("Legend: blue averaged live spectrum, orange peak hold", plot.x, 32);
    } finally {
      g.dispose();
    }
    return img;
  }

  static BufferedImage renderRecordingFormat() {
    BufferedImage img = createImage();
    Graphics2D g = img.createGraphics();
    try {
      applyHints(g);
      g.setColor(PlotRenderTheme.PANEL_BACKGROUND);
      g.fillRect(0, 0, W, H);
      g.setColor(PlotRenderTheme.TEXT_PRIMARY);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
      g.drawString("AudioAnalyzer .aar recording layout (big-endian)", 16, 26);

      int y = 56;
      int x = 16;
      int w = W - 32;
      int rowH = 28;

      drawBlock(g, x, y, w, rowH, "Header", PlotRenderTheme.SPECTRUM_LINE);
      y += rowH;
      drawField(g, x + 16, y, "u32 magic 'AAR1'", "u16 version", "u16 channels");
      y += rowH;
      drawField(g, x + 16, y, "f32 sampleRate", "u16 bitsPerSample", "u16 reserved=0");
      y += rowH + 12;

      drawBlock(g, x, y, w, rowH, "Frame record (repeats until EOF)", PlotRenderTheme.HIGHLIGHT);
      y += rowH;
      drawField(g, x + 16, y, "u32 frames", "i64 frameIndex", "i64 timestampNanos");
      y += rowH;
      drawField(g, x + 16, y, "f32 ch0 sample[0..frames)", "f32 ch1 sample[0..frames)", "...");
      y += rowH + 8;
      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.setFont(PlotRenderTheme.LABEL_FONT);
      g.drawString("Channels are stored non-interleaved within each frame record.", x + 4, y + 16);
      g.drawString(
          "Reader/writer live in audio-dsp; the format is stable from version 1 onward.",
          x + 4,
          y + 32);
    } finally {
      g.dispose();
    }
    return img;
  }

  private static void drawBlock(
      Graphics2D g, int x, int y, int w, int h, String label, Color color) {
    g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
    g.fillRect(x, y, w, h);
    g.setColor(color);
    g.setStroke(new BasicStroke(1.2f));
    g.drawRect(x, y, w, h);
    g.setColor(PlotRenderTheme.TEXT_PRIMARY);
    g.setFont(PlotRenderTheme.TITLE_FONT);
    g.drawString(label, x + 8, y + h - 9);
  }

  private static void drawField(Graphics2D g, int x, int y, String a, String b, String c) {
    int colW = (W - 64) / 3;
    drawCell(g, x, y, colW, a);
    drawCell(g, x + colW + 4, y, colW, b);
    drawCell(g, x + 2 * (colW + 4), y, colW, c);
  }

  private static void drawCell(Graphics2D g, int x, int y, int w, String label) {
    g.setColor(PlotRenderTheme.PLOT_BACKGROUND);
    g.fillRect(x, y, w, 24);
    g.setColor(PlotRenderTheme.AXIS_COLOR);
    g.drawRect(x, y, w, 24);
    g.setColor(PlotRenderTheme.TEXT_PRIMARY);
    g.setFont(PlotRenderTheme.LABEL_FONT);
    g.drawString(label, x + 6, y + 16);
  }

  static BufferedImage renderAbComparison() {
    SpectrumAnalyzer analyzer = new SpectrumAnalyzer(FFT, 0, MONO_44K.sampleRate());
    SineGenerator a = new SineGenerator(MONO_44K, 440.0, 0.6f);
    SineGenerator b = new SineGenerator(MONO_44K, 880.0, 0.6f);
    SpectrumSnapshot sa = null;
    SpectrumSnapshot sb = null;
    for (int i = 0; i < 6; i++) {
      sa = analyzer.analyze(a.nextBlock(FFT));
    }
    for (int i = 0; i < 6; i++) {
      sb = analyzer.analyze(b.nextBlock(FFT));
    }

    BufferedImage img = createImage();
    Graphics2D g = img.createGraphics();
    try {
      applyHints(g);
      g.setColor(PlotRenderTheme.PANEL_BACKGROUND);
      g.fillRect(0, 0, W, H);
      int halfW = W / 2 - 4;
      Rectangle leftPlot = new Rectangle(44, 24, halfW - 56, H - 58);
      Rectangle rightPlot = new Rectangle(halfW + 52, 24, halfW - 56, H - 58);

      drawSpectrumPanel(g, leftPlot, sa, "A — 440 Hz", PlotRenderTheme.SPECTRUM_LINE);
      drawSpectrumPanel(g, rightPlot, sb, "B — 880 Hz", PlotRenderTheme.WAVEFORM_RIGHT);

      g.setColor(PlotRenderTheme.HIGHLIGHT);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
      g.drawString("|delta dominant freq| ~ 440 Hz", W / 2 - 96, H - 12);
    } finally {
      g.dispose();
    }
    return img;
  }

  private static void drawSpectrumPanel(
      Graphics2D g, Rectangle plot, SpectrumSnapshot snap, String title, Color color) {
    g.setColor(PlotRenderTheme.PLOT_BACKGROUND);
    g.fillRect(plot.x, plot.y, plot.width, plot.height);
    PlotRenderTheme.drawGrid(g, plot, 10, 8);
    PlotRenderTheme.drawTitle(g, plot.x + 8, plot.y + 16, title);
    PlotRenderTheme.drawYAxisLabel(g, plot, "Magnitude [dB rel. peak]");
    PlotRenderTheme.drawYTicks(
        g, plot, new double[] {0.0d, 0.5d, 1.0d}, new String[] {"0 dB", "-40 dB", "-80 dB"});
    PlotRenderTheme.drawXTicks(
        g, plot, new double[] {0.0d, 0.5d, 1.0d}, new String[] {"0 Hz", "11025 Hz", "22050 Hz"});
    PlotRenderTheme.drawXAxisLabel(g, plot, "Frequency [Hz]");
    float[] mag = snap.magnitudesView();
    int bins = mag.length;
    float max = 1e-6f;
    for (float v : mag) {
      if (Math.abs(v) > max) {
        max = Math.abs(v);
      }
    }
    int floor = plot.y + plot.height - 1;
    int top = plot.y + 36;
    int[] xs = new int[bins];
    int[] ys = new int[bins];
    for (int i = 0; i < bins; i++) {
      xs[i] = plot.x + (int) ((long) i * (plot.width - 1) / Math.max(1, bins - 1));
      ys[i] = floor - (int) ((Math.abs(mag[i]) / max) * (floor - top));
    }
    g.setColor(color);
    g.setStroke(PlotRenderTheme.TRACE_STROKE);
    g.drawPolyline(xs, ys, bins);
  }

  private static BufferedImage createImage() {
    return new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
  }

  private static BufferedImage createWbImage() {
    return new BufferedImage(W, WB_H, BufferedImage.TYPE_INT_RGB);
  }

  /**
   * Renders the acoustic localization simulation workbench screenshot.
   *
   * <p>Shows a 2-D top-down room layout for the {@code twoCloseFrequencies} scenario: a 3 × 2 m
   * anechoic room, the default 4-microphone array near the bottom edge, and two stationary 600/640
   * Hz tonal emitters at distinct positions. Microphones are drawn as filled circles with channel
   * labels; emitters are drawn as star markers with frequency labels.
   *
   * @return a 760 × 480 image
   */
  public static BufferedImage renderSimulationWorkbench() {
    // Hardcoded data from SimulationScenarios.twoCloseFrequencies()
    String scenarioName = "two-close-frequencies";
    double roomW = 3.0;
    double roomH = 2.0;
    String[] micIds = {"m0", "m1", "m2", "m3"};
    double[] micXm = {1.35, 1.65, 1.35, 1.65};
    double[] micYm = {0.0, 0.0, 0.3, 0.3};
    double[] emitterXm = {1.0, 2.0};
    double[] emitterYm = {1.0, 1.0};
    double[] emitterFreq = {600.0, 640.0};
    float sampleRate = 16_000.0f;

    BufferedImage img = createWbImage();
    Graphics2D g = img.createGraphics();
    try {
      applyHints(g);
      g.setColor(PlotRenderTheme.PANEL_BACKGROUND);
      g.fillRect(0, 0, W, WB_H);

      // Title bar
      g.setColor(new Color(31, 38, 48));
      g.fillRect(0, 0, W, 44);
      g.setColor(PlotRenderTheme.TEXT_PRIMARY);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
      g.drawString("Acoustic Localization Simulation Workbench", 16, 28);
      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.setFont(PlotRenderTheme.LABEL_FONT);
      g.drawString("Scenario: " + scenarioName, 16, 42);

      // Room map
      int mapX = 48;
      int mapY = 60;
      int mapW = W - 96;
      int mapH = WB_H - 130;

      g.setColor(PlotRenderTheme.PLOT_BACKGROUND);
      g.fillRect(mapX, mapY, mapW, mapH);
      g.setColor(PlotRenderTheme.AXIS_COLOR);
      g.setStroke(new BasicStroke(1.5f));
      g.drawRect(mapX, mapY, mapW, mapH);
      PlotRenderTheme.drawGrid(g, new Rectangle(mapX, mapY, mapW, mapH), 6, 4);

      // Axis labels
      g.setFont(PlotRenderTheme.LABEL_FONT);
      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.drawString(String.format(Locale.ROOT, "0 m"), mapX - 4, mapY + mapH + 14);
      g.drawString(String.format(Locale.ROOT, "%.1f m", roomW), mapX + mapW - 16, mapY + mapH + 14);
      g.drawString(String.format(Locale.ROOT, "0 m"), mapX - 36, mapY + mapH);
      g.drawString(String.format(Locale.ROOT, "%.1f m", roomH), mapX - 36, mapY + 10);

      // Draw microphones
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
      for (int i = 0; i < micIds.length; i++) {
        int mx = mapX + (int) (micXm[i] / roomW * (mapW - 1));
        int my = mapY + mapH - 1 - (int) (micYm[i] / roomH * (mapH - 1));
        g.setColor(new Color(104, 188, 255, 200));
        g.fillOval(mx - 7, my - 7, 14, 14);
        g.setColor(PlotRenderTheme.TEXT_PRIMARY);
        g.drawOval(mx - 7, my - 7, 14, 14);
        g.setColor(PlotRenderTheme.TEXT_PRIMARY);
        drawClippedString(g, micIds[i], mx + 10, my + 4, 40);
      }

      // Draw emitters (star marker)
      Color[] emitterColors = {new Color(255, 164, 91, 220), new Color(102, 235, 187, 220)};
      for (int i = 0; i < emitterXm.length; i++) {
        int ex = mapX + (int) (emitterXm[i] / roomW * (mapW - 1));
        int ey = mapY + mapH - 1 - (int) (emitterYm[i] / roomH * (mapH - 1));
        drawStarMarker(g, ex, ey, 10, emitterColors[i % emitterColors.length]);
        g.setFont(PlotRenderTheme.LABEL_FONT);
        g.setColor(emitterColors[i % emitterColors.length]);
        drawClippedString(
            g,
            String.format(Locale.ROOT, "src%d %.0f Hz", i + 1, emitterFreq[i]),
            ex + 13,
            ey + 4,
            100);
      }

      // Legend
      int legendY = WB_H - 56;
      g.setFont(PlotRenderTheme.LABEL_FONT);
      g.setColor(new Color(104, 188, 255, 200));
      g.fillOval(16, legendY - 6, 10, 10);
      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.drawString("Microphone", 30, legendY + 4);
      drawStarMarker(g, 120, legendY - 1, 7, new Color(255, 164, 91, 220));
      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.drawString("Sound emitter", 132, legendY + 4);

      // Status bar
      int statusY = WB_H - 28;
      g.setColor(new Color(35, 43, 55));
      g.fillRect(0, statusY, W, 28);
      g.setFont(PlotRenderTheme.LABEL_FONT);
      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.drawString(
          String.format(
              Locale.ROOT,
              "Room: %.1f × %.1f m  |  Mics: %d  |  Emitters: %d  |  Sample rate: %.0f Hz",
              roomW,
              roomH,
              micIds.length,
              emitterXm.length,
              sampleRate),
          12,
          statusY + 17);
    } finally {
      g.dispose();
    }
    return img;
  }

  private static void drawStarMarker(Graphics2D g, int cx, int cy, int r, Color color) {
    int[] xs = new int[10];
    int[] ys = new int[10];
    for (int i = 0; i < 10; i++) {
      double angle = Math.PI / 2.0 + i * Math.PI / 5.0;
      int radius = (i % 2 == 0) ? r : r / 2;
      xs[i] = cx + (int) (radius * Math.cos(angle));
      ys[i] = cy - (int) (radius * Math.sin(angle));
    }
    g.setColor(color);
    g.fillPolygon(xs, ys, 10);
    g.setColor(color.brighter());
    g.drawPolygon(xs, ys, 10);
  }

  /**
   * Renders the playback explorer screenshot.
   *
   * <p>Shows a waveform panel with a playback cursor frozen at one-quarter of the buffer, and a
   * transport control bar with Play, Stop and loop indicators.
   *
   * @return a 760 × 320 image
   */
  public static BufferedImage renderPlaybackExplorer() {
    SineGenerator gen = new SineGenerator(MONO_44K, 330.0, 0.55f);
    AudioBlock block = gen.nextBlock(4096);
    float[] samples = block.channelView(0);

    BufferedImage img = createImage();
    Graphics2D g = img.createGraphics();
    try {
      applyHints(g);
      g.setColor(PlotRenderTheme.PANEL_BACKGROUND);
      g.fillRect(0, 0, W, H);

      // File info bar
      g.setColor(new Color(31, 38, 48));
      g.fillRect(0, 0, W, 36);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
      g.setColor(PlotRenderTheme.TEXT_PRIMARY);
      g.drawString("Playback Explorer", 12, 23);
      g.setFont(PlotRenderTheme.LABEL_FONT);
      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.drawString("demo-recording.aar  |  44 100 Hz / mono / 16-bit  |  0.09 s", 148, 23);

      // Waveform area
      Rectangle plot = new Rectangle(48, 48, W - 64, H - 100);
      PlotRenderTheme.drawPlotBackground(g, W, H, plot);
      PlotRenderTheme.drawGrid(g, plot, 10, 6);
      PlotRenderTheme.drawTitle(g, plot.x + 6, plot.y + 16, "Waveform — 330 Hz demo");
      PlotRenderTheme.drawYAxisLabel(g, plot, "Amplitude [-1..1]");
      PlotRenderTheme.drawYTicks(
          g, plot, new double[] {0.0d, 0.5d, 1.0d}, new String[] {"+1", "0", "-1"});
      PlotRenderTheme.drawXAxisLabel(g, plot, "Time [ms]");
      int n = Math.min(samples.length, 2048);
      double durationMs = 1000.0d * (n - 1) / MONO_44K.sampleRate();
      PlotRenderTheme.drawXTicks(
          g,
          plot,
          new double[] {0.0d, 0.5d, 1.0d},
          new String[] {
            "0 ms",
            String.format(Locale.ROOT, "%.1f ms", durationMs / 2.0d),
            String.format(Locale.ROOT, "%.1f ms", durationMs)
          });
      int centerY = plot.y + plot.height / 2;
      int amplitude = plot.height / 2 - 12;
      Path2D path = new Path2D.Float();
      for (int i = 0; i < n; i++) {
        double x = plot.x + (double) i * (plot.width - 1) / Math.max(1, n - 1);
        double y = centerY - Math.max(-1f, Math.min(1f, samples[i])) * amplitude;
        if (i == 0) path.moveTo(x, y);
        else path.lineTo(x, y);
      }
      g.setColor(PlotRenderTheme.CENTER_LINE);
      g.drawLine(plot.x, centerY, plot.x + plot.width, centerY);
      g.setColor(PlotRenderTheme.WAVEFORM_LEFT);
      g.setStroke(PlotRenderTheme.TRACE_STROKE);
      g.draw(path);

      // Playback cursor at 25 %
      int cursorX = plot.x + plot.width / 4;
      g.setColor(new Color(255, 220, 60, 180));
      g.setStroke(
          new BasicStroke(
              1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {4f, 3f}, 0f));
      g.drawLine(cursorX, plot.y, cursorX, plot.y + plot.height);
      g.setStroke(PlotRenderTheme.AXIS_STROKE);
      g.setColor(new Color(255, 220, 60, 200));
      g.setFont(PlotRenderTheme.LABEL_FONT);
      g.drawString(
          String.format(Locale.ROOT, "%.1f ms", durationMs / 4.0d), cursorX + 4, plot.y + 14);

      // Transport controls
      int ctrlY = H - 38;
      g.setColor(new Color(35, 43, 55));
      g.fillRect(0, ctrlY, W, 38);
      g.setColor(new Color(72, 84, 102));
      g.drawLine(0, ctrlY, W, ctrlY);
      drawTransportButton(g, 16, ctrlY + 7, "▶ Play");
      drawTransportButton(g, 84, ctrlY + 7, "■ Stop");
      drawTransportButton(g, 152, ctrlY + 7, "↺ Loop: OFF");

      // Position slider track
      int trackX = 270;
      int trackW = W - trackX - 16;
      g.setColor(new Color(50, 61, 76));
      g.fillRoundRect(trackX, ctrlY + 14, trackW, 8, 4, 4);
      g.setColor(new Color(104, 188, 255, 160));
      g.fillRoundRect(trackX, ctrlY + 14, trackW / 4, 8, 4, 4);
      g.setColor(new Color(222, 226, 234));
      g.fillOval(trackX + trackW / 4 - 6, ctrlY + 10, 12, 16);

      // Time readout
      g.setFont(PlotRenderTheme.LABEL_FONT);
      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.drawString(
          String.format(Locale.ROOT, "%.1f ms / %.1f ms", durationMs / 4.0d, durationMs),
          trackX + trackW + 4,
          ctrlY + 22);
    } finally {
      g.dispose();
    }
    return img;
  }

  private static void drawTransportButton(Graphics2D g, int x, int y, String label) {
    FontMetrics fm = g.getFontMetrics(PlotRenderTheme.LABEL_FONT);
    int w = fm.stringWidth(label) + 14;
    g.setColor(new Color(47, 58, 74));
    g.fillRoundRect(x, y, w, 22, 8, 8);
    g.setColor(new Color(72, 84, 102));
    g.drawRoundRect(x, y, w, 22, 8, 8);
    g.setFont(PlotRenderTheme.LABEL_FONT);
    g.setColor(PlotRenderTheme.TEXT_PRIMARY);
    g.drawString(label, x + 7, y + 15);
  }

  /**
   * Renders the imported recording workbench screenshot.
   *
   * <p>Shows a two-panel layout: on the left, a feature table listing extracted wingbeat feature
   * values for a synthetic 380 Hz mosquito-like recording; on the right, a bar chart of the
   * harmonic amplitude profile.
   *
   * @return a 760 × 480 image
   */
  public static BufferedImage renderImportedRecordingWorkbench() {
    BufferedImage img = createWbImage();
    Graphics2D g = img.createGraphics();
    try {
      applyHints(g);
      g.setColor(PlotRenderTheme.PANEL_BACKGROUND);
      g.fillRect(0, 0, W, WB_H);

      // Title bar
      g.setColor(new Color(31, 38, 48));
      g.fillRect(0, 0, W, 44);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
      g.setColor(PlotRenderTheme.TEXT_PRIMARY);
      g.drawString("Imported Recording Workbench", 16, 28);
      g.setFont(PlotRenderTheme.LABEL_FONT);
      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.drawString(
          "Dataset: HumBugDB  |  Recording: rec_0042  |  Label: Culex quinquefasciatus", 16, 42);

      // Feature table (left panel)
      int tableX = 12;
      int tableY = 52;
      int tableW = 390;
      int tableH = WB_H - 80;
      g.setColor(PlotRenderTheme.PLOT_BACKGROUND);
      g.fillRect(tableX, tableY, tableW, tableH);
      g.setColor(new Color(72, 84, 102));
      g.drawRect(tableX, tableY, tableW, tableH);

      // Table header
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
      g.setColor(new Color(35, 43, 55));
      g.fillRect(tableX, tableY, tableW, 22);
      g.setColor(PlotRenderTheme.TEXT_PRIMARY);
      g.drawString("Feature", tableX + 8, tableY + 15);
      g.drawString("Value", tableX + 230, tableY + 15);
      g.drawString("Unit", tableX + 330, tableY + 15);

      // Feature rows
      String[][] features = {
        {"Fundamental frequency", "382.4", "Hz"},
        {"Spectral centroid", "401.2", "Hz"},
        {"Spectral bandwidth", "14.6", "Hz"},
        {"Harmonic count", "4", "—"},
        {"Harmonic ratio H2/H1", "0.48", "—"},
        {"Harmonic ratio H3/H1", "0.24", "—"},
        {"Amplitude modulation", "0.12", "[0..1]"},
        {"Frequency drift", "+0.5", "Hz/s"},
        {"Frequency jitter", "0.9", "Hz"},
        {"Signal-to-noise ratio", "18.3", "—"},
        {"Track duration", "1.42", "s"},
        {"Feature confidence", "0.91", "[0..1]"},
      };
      g.setFont(PlotRenderTheme.LABEL_FONT);
      int rowH = (tableH - 22) / features.length;
      for (int i = 0; i < features.length; i++) {
        int rowY = tableY + 22 + i * rowH;
        if (i % 2 == 0) {
          g.setColor(new Color(22, 26, 33));
          g.fillRect(tableX + 1, rowY, tableW - 2, rowH);
        }
        g.setColor(PlotRenderTheme.TEXT_PRIMARY);
        drawClippedString(g, features[i][0], tableX + 8, rowY + rowH - 5, 210);
        g.setColor(new Color(104, 188, 255));
        drawClippedString(g, features[i][1], tableX + 230, rowY + rowH - 5, 90);
        g.setColor(PlotRenderTheme.TEXT_MUTED);
        drawClippedString(g, features[i][2], tableX + 330, rowY + rowH - 5, 55);
      }

      // Harmonic profile bar chart (right panel)
      int chartX = tableX + tableW + 12;
      int chartW = W - chartX - 12;
      int chartY = 52;
      int chartH = (WB_H - 80) / 2 - 4;
      Rectangle chartBounds = new Rectangle(chartX + 42, chartY + 24, chartW - 52, chartH - 44);
      PlotRenderTheme.drawPlotBackground(g, W, WB_H, chartBounds);
      PlotRenderTheme.drawGrid(g, chartBounds, 4, 5);
      g.setFont(PlotRenderTheme.TITLE_FONT);
      g.setColor(PlotRenderTheme.TEXT_PRIMARY);
      g.drawString("Harmonic profile", chartX + 42, chartY + 16);
      PlotRenderTheme.drawYAxisLabel(g, chartBounds, "Amplitude");
      PlotRenderTheme.drawYTicks(
          g, chartBounds, new double[] {0.0d, 0.5d, 1.0d}, new String[] {"1.0", "0.5", "0.0"});
      PlotRenderTheme.drawXTicks(
          g,
          chartBounds,
          new double[] {0.125, 0.375, 0.625, 0.875},
          new String[] {"H1", "H2", "H3", "H4"});

      double[] harmonicAmps = {0.80, 0.48 * 0.80, 0.24 * 0.80, 0.09 * 0.80};
      int barCount = harmonicAmps.length;
      int barW = chartBounds.width / (barCount * 2);
      for (int i = 0; i < barCount; i++) {
        int bx = chartBounds.x + i * chartBounds.width / barCount + barW / 2;
        int barHeight = (int) (harmonicAmps[i] * (chartBounds.height - 8));
        int by = chartBounds.y + chartBounds.height - barHeight;
        g.setColor(new Color(104, 188, 255, 180));
        g.fillRect(bx, by, barW, barHeight);
        g.setColor(PlotRenderTheme.SPECTRUM_LINE);
        g.drawRect(bx, by, barW, barHeight);
      }

      // Calibration status panel (bottom right)
      int calY = chartY + chartH + 8;
      int calH = WB_H - calY - 28;
      Rectangle calBounds = new Rectangle(chartX, calY, chartW, calH);
      g.setColor(PlotRenderTheme.PLOT_BACKGROUND);
      g.fillRect(calBounds.x, calBounds.y, calBounds.width, calBounds.height);
      g.setColor(new Color(72, 84, 102));
      g.drawRect(calBounds.x, calBounds.y, calBounds.width, calBounds.height);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
      g.setColor(PlotRenderTheme.TEXT_PRIMARY);
      g.drawString("Calibration status", calBounds.x + 8, calBounds.y + 16);
      g.setFont(PlotRenderTheme.LABEL_FONT);
      g.setColor(new Color(102, 235, 187));
      g.drawString("✓ Generator calibrated from this dataset", calBounds.x + 8, calBounds.y + 34);
      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.drawString("Baseline vs real: 28 % mean rel. diff.", calBounds.x + 8, calBounds.y + 50);
      g.drawString("Calibrated vs real: 9 % mean rel. diff.", calBounds.x + 8, calBounds.y + 66);

      // Status bar
      g.setColor(new Color(35, 43, 55));
      g.fillRect(0, WB_H - 28, W, 28);
      g.setFont(PlotRenderTheme.LABEL_FONT);
      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.drawString(
          "12 recordings  |  Feature extraction: complete  |  Provenance: HumBugDB v1.0",
          12,
          WB_H - 11);
    } finally {
      g.dispose();
    }
    return img;
  }

  /**
   * Renders the generator calibration screenshot.
   *
   * <p>Displays grouped before/after bars for key wingbeat feature differences, illustrating how
   * generator calibration reduces the gap between synthetic and real recordings.
   *
   * @return a 760 × 480 image
   */
  public static BufferedImage renderGeneratorCalibration() {
    // Hardcoded representative before/after calibration data (380 Hz mosquito-like baseline)
    String[] featureNames = {
      "freq", "centroid", "bandwidth", "AM", "drift", "jitter", "SNR", "duration"
    };
    double[] beforeDiffs = {0.62, 0.48, 0.35, 0.71, 0.55, 0.41, 0.28, 0.19};
    double[] afterDiffs = {0.12, 0.09, 0.14, 0.18, 0.22, 0.17, 0.10, 0.08};
    double improvementPct = 72.0;
    double meanBefore = 0.449;
    double meanAfter = 0.138;
    int corpusSize = 10;

    BufferedImage img = createWbImage();
    Graphics2D g = img.createGraphics();
    try {
      applyHints(g);
      g.setColor(PlotRenderTheme.PANEL_BACKGROUND);
      g.fillRect(0, 0, W, WB_H);

      // Title bar
      g.setColor(new Color(31, 38, 48));
      g.fillRect(0, 0, W, 44);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
      g.setColor(PlotRenderTheme.TEXT_PRIMARY);
      g.drawString("Generator Calibration Result", 16, 28);
      g.setFont(PlotRenderTheme.LABEL_FONT);
      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.drawString(
          String.format(
              Locale.ROOT,
              "Baseline: 380 Hz mosquito-like  |  Improvement: %.0f %%",
              improvementPct),
          16,
          42);

      // Bar chart area
      int chartMarginLeft = 148;
      Rectangle chart = new Rectangle(chartMarginLeft, 60, W - chartMarginLeft - 16, WB_H - 120);
      PlotRenderTheme.drawPlotBackground(g, W, WB_H, chart);
      PlotRenderTheme.drawGrid(g, chart, 5, 8);
      PlotRenderTheme.drawYAxisLabel(g, chart, "Relative difference");
      PlotRenderTheme.drawYTicks(
          g,
          chart,
          new double[] {0.0d, 0.25d, 0.5d, 0.75d, 1.0d},
          new String[] {"0 %", "25 %", "50 %", "75 %", "≥100 %"});

      int n = featureNames.length;
      int groupW = chart.width / Math.max(1, n);
      int barW = groupW / 3;

      for (int i = 0; i < n; i++) {
        int gx = chart.x + i * groupW + barW / 2;

        // Before bar
        int bHt = (int) (Math.min(1.0, beforeDiffs[i]) * (chart.height - 4));
        int bY = chart.y + chart.height - bHt;
        g.setColor(new Color(255, 100, 80, 180));
        g.fillRect(gx, bY, barW, bHt);
        g.setColor(new Color(255, 100, 80));
        g.drawRect(gx, bY, barW, bHt);

        // After bar
        int aHt = (int) (Math.min(1.0, afterDiffs[i]) * (chart.height - 4));
        int aY = chart.y + chart.height - aHt;
        g.setColor(new Color(102, 235, 187, 180));
        g.fillRect(gx + barW + 2, aY, barW, aHt);
        g.setColor(new Color(102, 235, 187));
        g.drawRect(gx + barW + 2, aY, barW, aHt);

        // Feature name on left
        g.setFont(PlotRenderTheme.LABEL_FONT);
        g.setColor(PlotRenderTheme.TEXT_MUTED);
        g.drawString(featureNames[i], 4, chart.y + (i + 1) * chart.height / n - 4);
      }

      // X axis: feature names
      double[] xPositions = new double[n];
      for (int i = 0; i < n; i++) {
        xPositions[i] = (i + 0.5) / n;
      }
      PlotRenderTheme.drawXTicks(g, chart, xPositions, featureNames);

      // Legend
      int legendY = WB_H - 44;
      g.setColor(new Color(35, 43, 55));
      g.fillRect(0, legendY, W, 44);
      g.setColor(new Color(255, 100, 80, 180));
      g.fillRect(16, legendY + 12, 14, 14);
      g.setColor(new Color(255, 100, 80));
      g.drawRect(16, legendY + 12, 14, 14);
      g.setFont(PlotRenderTheme.LABEL_FONT);
      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.drawString("Before calibration", 34, legendY + 24);

      g.setColor(new Color(102, 235, 187, 180));
      g.fillRect(160, legendY + 12, 14, 14);
      g.setColor(new Color(102, 235, 187));
      g.drawRect(160, legendY + 12, 14, 14);
      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.drawString("After calibration", 178, legendY + 24);

      g.setColor(PlotRenderTheme.TEXT_MUTED);
      g.drawString(
          String.format(
              Locale.ROOT,
              "Corpus: %d recordings  |  Mean Δ before: %.1f %%  →  after: %.1f %%",
              corpusSize,
              meanBefore * 100.0,
              meanAfter * 100.0),
          320,
          legendY + 24);
    } finally {
      g.dispose();
    }
    return img;
  }

  /**
   * Draws a string, truncating with {@code …} if it exceeds {@code maxWidth} pixels.
   *
   * @param g graphics context
   * @param text the text to draw
   * @param x left edge of the text baseline
   * @param y baseline y position
   * @param maxWidth maximum allowed pixel width; text is clipped with an ellipsis if exceeded
   */
  static void drawClippedString(Graphics2D g, String text, int x, int y, int maxWidth) {
    FontMetrics fm = g.getFontMetrics();
    if (fm.stringWidth(text) <= maxWidth) {
      g.drawString(text, x, y);
      return;
    }
    String ellipsis = "…";
    int ellipsisW = fm.stringWidth(ellipsis);
    int available = maxWidth - ellipsisW;
    if (available <= 0) {
      g.drawString(ellipsis, x, y);
      return;
    }
    // Binary-search for the longest prefix that fits
    int lo = 0;
    int hi = text.length();
    while (lo < hi - 1) {
      int mid = (lo + hi) / 2;
      if (fm.stringWidth(text.substring(0, mid)) <= available) lo = mid;
      else hi = mid;
    }
    g.drawString(text.substring(0, lo) + ellipsis, x, y);
  }

  private static void applyHints(Graphics2D g) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
  }

  private static void writePng(Path out, BufferedImage img) throws IOException {
    if (!ImageIO.write(img, "png", out.toFile())) {
      throw new IOException("PNG writer not available for " + out);
    }
  }
}
