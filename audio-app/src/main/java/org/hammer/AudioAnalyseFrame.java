package org.hammer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.hammer.audio.AudioCaptureService;
import org.hammer.audio.AudioCaptureServiceImpl;
import org.hammer.audio.DemoAudioCaptureService;
import org.hammer.audio.DemoSignalType;
import org.hammer.audio.analysis.MeasurementCalculator;
import org.hammer.audio.analysis.MeasurementSnapshot;
import org.hammer.audio.analysis.SpectrumSnapshot;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.localization.StereoDelayAnalyzer;
import org.hammer.audio.localization.StereoDelaySnapshot;
import org.hammer.audio.localization.StereoDelayStatus;
import org.hammer.audio.pluginhost.PluginManager;
import org.hammer.audio.pluginhost.PluginMenuBuilder;
import org.hammer.audio.ui.theme.UiTheme;

/**
 * Main application frame for audio analysis and visualization.
 *
 * <p>Refactored to use dependency injection with AudioCaptureService instead of direct singleton
 * access. The frame creates the service and injects it into UI panels. Audio capture is
 * started/stopped via the menu.
 *
 * @author chammer
 */
@SuppressWarnings("PMD.CouplingBetweenObjects")
public class AudioAnalyseFrame extends JFrame {
  private static final long serialVersionUID = 1L;
  private static final Logger LOGGER = Logger.getLogger(AudioAnalyseFrame.class.getName());
  private static final String ERROR_TITLE = "Error";
  private static final int CONTENT_PANE_HGAP = 5;
  private static final int CONTENT_PANE_VGAP = 5;
  private static final int CONTENT_PANE_PADDING = 8;
  private static final int DEFAULT_WINDOW_WIDTH = 1440;
  private static final int DEFAULT_WINDOW_HEIGHT = 1000;

  // Keep the historic capture format so existing tests and supported-device checks stay aligned.
  private static final float DEFAULT_SAMPLE_RATE = 16000.0f;
  private static final int DEFAULT_SAMPLE_BITS = 8;
  private static final int DEFAULT_CHANNELS = 2;
  private static final boolean DEFAULT_SIGNED = false;
  private static final boolean DEFAULT_BIG_ENDIAN = false;
  private static final int TOP_PANEL_HGAP = 8;
  private static final MeasurementSnapshot NO_MEASUREMENT =
      new MeasurementSnapshot(Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, false);

  private final JPanel contentPane;
  private final JPanel visualizationPanel = new JPanel(new BorderLayout(4, 4));
  private final WaveformPanel waveformPanel = new WaveformPanel();
  private final PhaseDiagramPanel phaseDiagramPanel = new PhaseDiagramPanel();
  private final SpectrumPanel spectrumPanel = new SpectrumPanel();
  private final SpectrogramPanel spectrogramPanel = new SpectrogramPanel();
  private final DiagnosisPanel diagnosisPanel = new DiagnosisPanel();
  private final transient org.hammer.audio.diagnosis.DiagnosisAnalyzer diagnosisAnalyzer =
      new org.hammer.audio.diagnosis.DiagnosisAnalyzer();
  private transient org.hammer.audio.diagnosis.DiagnosisSnapshot lastDiagnosis =
      org.hammer.audio.diagnosis.DiagnosisSnapshot.empty();

  private final JTextField textFieldDataSize;
  private final JTextField textFieldDivisor;
  private final JTextField textFieldAudioFormat;
  private final JTextField textFieldPeakFrequency;
  private final JTextField textFieldRms;
  private final JTextField textFieldPeakLevel;
  private final JTextField textFieldDominantFrequency;
  private final JTextField textFieldStereoCorrelation;
  private final JTextField textFieldClipping;
  private final JTextField textFieldMicrophoneSpacing;
  private final JTextField textFieldStereoDelay;
  private final JTextField textFieldStereoAngle;
  private final JTextField textFieldStereoConfidence;
  private final JComboBox<AudioDeviceItem> comboBoxAudioDevice;
  private final JComboBox<DemoSignalType> comboBoxDemoSignal;
  private final JRadioButton radioLiveMicrophone;
  private final JRadioButton radioDemoMode;
  private final MeasurementCalculator measurementCalculator = new MeasurementCalculator();

  private final JCheckBoxMenuItem mntmStart;
  private final JCheckBoxMenuItem mntmFreeze;
  private final Timer refreshTimer;

  // Audio capture service
  private AudioCaptureService audioCaptureService;
  private transient AudioBlock frozenBlock;
  private transient InputMode inputMode = InputMode.LIVE;
  private transient StereoDelayAnalyzer stereoDelayAnalyzer;
  private double stereoDelayAnalyzerSpacingMeters = Double.NaN;
  private transient org.hammer.audio.RecordingTap recordingTap;

  public static void main(String[] args) {
    EventQueue.invokeLater(
        () -> {
          try {
            LOGGER.info("Starting AudioAnalyseFrame application");
            UiTheme.installDarkTheme();
            AudioAnalyseFrame frame = new AudioAnalyseFrame();
            frame.setVisible(true);
          } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to start application", e);
            e.printStackTrace();
          }
        });
  }

  public AudioAnalyseFrame() {
    LOGGER.info("AudioAnalyseFrame constructor started");
    setTitle("AudioAnalyzer");
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);

    contentPane = new JPanel(new BorderLayout(CONTENT_PANE_HGAP, CONTENT_PANE_VGAP));
    contentPane.setBorder(
        new EmptyBorder(
            CONTENT_PANE_PADDING,
            CONTENT_PANE_PADDING,
            CONTENT_PANE_PADDING,
            CONTENT_PANE_PADDING));
    setContentPane(contentPane);

    // Initialize fields before calling init methods
    textFieldDataSize = new JTextField();
    textFieldDivisor = new JTextField();
    textFieldAudioFormat = new JTextField();
    textFieldPeakFrequency = new JTextField();
    textFieldRms = new JTextField();
    textFieldPeakLevel = new JTextField();
    textFieldDominantFrequency = new JTextField();
    textFieldStereoCorrelation = new JTextField();
    textFieldClipping = new JTextField();
    textFieldMicrophoneSpacing = new JTextField();
    textFieldStereoDelay = new JTextField();
    textFieldStereoAngle = new JTextField();
    textFieldStereoConfidence = new JTextField();
    comboBoxAudioDevice = new JComboBox<>();
    comboBoxDemoSignal = new JComboBox<>(DemoSignalType.values());
    radioLiveMicrophone = new JRadioButton("Live microphone", true);
    radioDemoMode = new JRadioButton("Demo mode");
    mntmStart = new JCheckBoxMenuItem("Start/Stop");
    mntmFreeze = new JCheckBoxMenuItem("Pause/Freeze");

    initializeAudioService(null);

    initMenu();
    initTopSettingsPanel();
    initCenterAndEast();
    initSouthSlider();

    refreshTimer = new Timer(UiConstants.REFRESH_INTERVAL_MS, e -> updateUIFromModel());
    refreshTimer.setRepeats(true);
    refreshTimer.start();

    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            if (recordingTap != null && !recordingTap.isClosed()) {
              try {
                recordingTap.stop();
              } catch (IOException ioe) {
                LOGGER.log(Level.WARNING, "Failed to close recording on window close", ioe);
              }
            }
            stopAudioIfRunning();
            if (refreshTimer != null && refreshTimer.isRunning()) {
              refreshTimer.stop();
            }
          }
        });

    pack();
    setSize(DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
    setLocationRelativeTo(null);
    LOGGER.info("AudioAnalyseFrame initialized successfully");
  }

  /** Initialize the audio capture service and inject into panels. */
  private void initializeAudioService(Mixer.Info mixerInfo) {
    LOGGER.info("Initializing audio capture service");
    int divisor = audioCaptureService != null ? audioCaptureService.getDivisor() : 1;
    if (inputMode == InputMode.DEMO) {
      audioCaptureService =
          new DemoAudioCaptureService(
              DEFAULT_SAMPLE_RATE,
              DEFAULT_SAMPLE_BITS,
              DEFAULT_CHANNELS,
              divisor,
              selectedDemoSignal());
    } else {
      audioCaptureService =
          new AudioCaptureServiceImpl(
              DEFAULT_SAMPLE_RATE,
              DEFAULT_SAMPLE_BITS,
              DEFAULT_CHANNELS,
              DEFAULT_SIGNED,
              DEFAULT_BIG_ENDIAN,
              divisor,
              mixerInfo);
    }

    waveformPanel.setAudioCaptureService(audioCaptureService);
    phaseDiagramPanel.setAudioCaptureService(audioCaptureService);
    spectrumPanel.setAudioCaptureService(audioCaptureService);
    spectrogramPanel.setAudioCaptureService(audioCaptureService);
    setFrozen(false);
  }

  private void initMenu() {
    JMenuBar menuBar = new JMenuBar();
    setJMenuBar(menuBar);

    JMenu mnFile = new JMenu("File");
    menuBar.add(mnFile);

    mntmStart.setToolTipText("Start or stop audio input");
    mntmStart.addActionListener(this::toggleAudioStartStop);
    mnFile.add(mntmStart);

    mntmFreeze.setToolTipText("Freeze waveform and spectrum snapshots for inspection/export");
    mntmFreeze.addActionListener(e -> setFrozen(mntmFreeze.isSelected()));
    mnFile.add(mntmFreeze);

    mnFile.addSeparator();

    JMenuItem exportCsv = new JMenuItem("Export measurement CSV...");
    exportCsv.addActionListener(e -> exportMeasurementCsv());
    mnFile.add(exportCsv);

    JMenuItem exportPng = new JMenuItem("Export measurement PNG...");
    exportPng.addActionListener(e -> exportMeasurementPng());
    mnFile.add(exportPng);

    JMenuItem exportBundle = new JMenuItem("Export evidence bundle...");
    exportBundle.setToolTipText(
        "Export screenshot, samples, spectrum, spectrogram, stereo delay, diagnosis and metadata"
            + " into one directory.");
    exportBundle.addActionListener(e -> exportEvidenceBundle());
    mnFile.add(exportBundle);

    mnFile.addSeparator();

    JCheckBoxMenuItem mntmPeakHold = new JCheckBoxMenuItem("Spectrum: peak hold");
    mntmPeakHold.addActionListener(
        e -> spectrumPanel.setPeakHoldEnabled(mntmPeakHold.isSelected()));
    mnFile.add(mntmPeakHold);

    JCheckBoxMenuItem mntmAveraging = new JCheckBoxMenuItem("Spectrum: averaging");
    mntmAveraging.addActionListener(
        e -> spectrumPanel.setAveragingEnabled(mntmAveraging.isSelected()));
    mnFile.add(mntmAveraging);

    JMenuItem mntmResetPeak = new JMenuItem("Spectrum: reset peak hold");
    mntmResetPeak.addActionListener(e -> spectrumPanel.resetPeakHold());
    mnFile.add(mntmResetPeak);

    mnFile.addSeparator();

    JCheckBoxMenuItem mntmTrigger = new JCheckBoxMenuItem("Waveform: trigger (oscilloscope)");
    mntmTrigger.setToolTipText(
        "Align the waveform display so each refresh starts on a rising zero crossing"
            + " (channel 0).");
    mntmTrigger.addActionListener(e -> waveformPanel.setTriggerEnabled(mntmTrigger.isSelected()));
    mnFile.add(mntmTrigger);

    JMenu mnTriggerSlope = new JMenu("Waveform: trigger slope");
    ButtonGroup slopeGroup = new ButtonGroup();
    JRadioButton mntmTriggerRising = new JRadioButton("Rising ↑", true);
    JRadioButton mntmTriggerFalling = new JRadioButton("Falling ↓");
    slopeGroup.add(mntmTriggerRising);
    slopeGroup.add(mntmTriggerFalling);
    mntmTriggerRising.addActionListener(
        e ->
            waveformPanel
                .getTrigger()
                .setSlope(org.hammer.audio.analysis.WaveformTrigger.Slope.RISING));
    mntmTriggerFalling.addActionListener(
        e ->
            waveformPanel
                .getTrigger()
                .setSlope(org.hammer.audio.analysis.WaveformTrigger.Slope.FALLING));
    mnTriggerSlope.add(mntmTriggerRising);
    mnTriggerSlope.add(mntmTriggerFalling);
    mnFile.add(mnTriggerSlope);

    JMenu mnTriggerMode = new JMenu("Waveform: trigger mode");
    ButtonGroup modeGroup = new ButtonGroup();
    JRadioButton mntmTriggerAuto = new JRadioButton("Auto (fall back if silent)", true);
    JRadioButton mntmTriggerNormal = new JRadioButton("Normal (only on event)");
    modeGroup.add(mntmTriggerAuto);
    modeGroup.add(mntmTriggerNormal);
    mntmTriggerAuto.addActionListener(
        e ->
            waveformPanel
                .getTrigger()
                .setMode(org.hammer.audio.analysis.WaveformTrigger.Mode.AUTO));
    mntmTriggerNormal.addActionListener(
        e ->
            waveformPanel
                .getTrigger()
                .setMode(org.hammer.audio.analysis.WaveformTrigger.Mode.NORMAL));
    mnTriggerMode.add(mntmTriggerAuto);
    mnTriggerMode.add(mntmTriggerNormal);
    mnFile.add(mnTriggerMode);

    mnFile.addSeparator();

    JMenuItem mntmStartRecording = new JMenuItem("Start recording...");
    mntmStartRecording.setToolTipText(
        "Capture every produced AudioBlock into an .aar file for later replay or A/B compare.");
    mntmStartRecording.addActionListener(e -> startRecording());
    mnFile.add(mntmStartRecording);

    JMenuItem mntmStopRecording = new JMenuItem("Stop recording");
    mntmStopRecording.addActionListener(e -> stopRecording());
    mnFile.add(mntmStopRecording);

    JMenuItem mntmOpenRecording = new JMenuItem("Open recording...");
    mntmOpenRecording.setToolTipText("Replay a previously captured .aar file as the audio source.");
    mntmOpenRecording.addActionListener(e -> openRecording());
    mnFile.add(mntmOpenRecording);

    JMenuItem mntmCompare = new JMenuItem("Compare two recordings...");
    mntmCompare.setToolTipText(
        "Compare measurement, spectrum and diagnosis of two .aar recordings and render a"
            + " Markdown A/B report.");
    mntmCompare.addActionListener(e -> compareRecordings());
    mnFile.add(mntmCompare);

    menuBar.add(Box.createGlue());

    JMenu mnPlugins = new PluginMenuBuilder(new PluginManager().loadPlugins()).buildMenu(this);
    menuBar.add(mnPlugins);

    JMenu mnHelp = new JMenu("Help");
    menuBar.add(mnHelp);

    JMenuItem mntmAbout = new JMenuItem();
    mntmAbout.setAction(new SwingAction());
    mnHelp.add(mntmAbout);
  }

  private void initTopSettingsPanel() {
    JPanel topContainer = new JPanel();
    topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
    topContainer.setBorder(new EmptyBorder(0, 0, 4, 0));
    contentPane.add(topContainer, BorderLayout.NORTH);
    JPanel settingsPanel = createSettingsPanel();
    JPanel measurementPanel = createMeasurementPanel();
    settingsPanel.setAlignmentX(LEFT_ALIGNMENT);
    measurementPanel.setAlignmentX(LEFT_ALIGNMENT);
    topContainer.add(settingsPanel);
    topContainer.add(Box.createVerticalStrut(TOP_PANEL_HGAP));
    topContainer.add(measurementPanel);
    updateModeControls();
    updateUIFromModel();
  }

  private JPanel createSettingsPanel() {
    JPanel settingsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
    settingsPanel.setBorder(UiTheme.createPanelBorder());

    settingsPanel.add(new JLabel("Input"));
    JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    ButtonGroup buttonGroup = new ButtonGroup();
    buttonGroup.add(radioLiveMicrophone);
    buttonGroup.add(radioDemoMode);
    radioLiveMicrophone.addActionListener(e -> switchInputMode(InputMode.LIVE));
    radioDemoMode.addActionListener(e -> switchInputMode(InputMode.DEMO));
    modePanel.add(radioLiveMicrophone);
    modePanel.add(radioDemoMode);
    settingsPanel.add(modePanel);

    settingsPanel.add(Box.createHorizontalStrut(8));
    settingsPanel.add(new JLabel("Demo"));
    comboBoxDemoSignal.addItemListener(
        event -> {
          if (event.getStateChange() == ItemEvent.SELECTED && inputMode == InputMode.DEMO) {
            switchServicePreservingRunning(null);
          }
        });
    settingsPanel.add(comboBoxDemoSignal);

    settingsPanel.add(new JLabel("Device"));
    populateAudioDeviceChoices();
    comboBoxAudioDevice.addItemListener(this::audioDeviceSelectionChanged);
    settingsPanel.add(comboBoxAudioDevice);

    settingsPanel.add(Box.createHorizontalStrut(8));
    settingsPanel.add(new JLabel("Size"));
    configureReadOnlyField(textFieldDataSize, 6);
    settingsPanel.add(textFieldDataSize);

    settingsPanel.add(new JLabel("Div"));
    configureReadOnlyField(textFieldDivisor, 4);
    settingsPanel.add(textFieldDivisor);

    settingsPanel.add(new JLabel("Format"));
    textFieldAudioFormat.setHorizontalAlignment(SwingConstants.CENTER);
    configureReadOnlyField(textFieldAudioFormat, 18);
    settingsPanel.add(textFieldAudioFormat);

    settingsPanel.add(new JLabel("Peak"));
    textFieldPeakFrequency.setHorizontalAlignment(SwingConstants.CENTER);
    configureReadOnlyField(textFieldPeakFrequency, 10);
    settingsPanel.add(textFieldPeakFrequency);

    settingsPanel.add(new JLabel("Mic spacing m"));
    textFieldMicrophoneSpacing.setText(
        String.format(Locale.ROOT, "%.2f", StereoDelayAnalyzer.DEFAULT_MICROPHONE_SPACING_METERS));
    textFieldMicrophoneSpacing.setColumns(5);
    textFieldMicrophoneSpacing.setToolTipText(
        "Stereo microphone spacing used for direction estimate");
    settingsPanel.add(textFieldMicrophoneSpacing);
    return settingsPanel;
  }

  private JPanel createMeasurementPanel() {
    JPanel measurementPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
    measurementPanel.setBorder(UiTheme.createPanelBorder());

    measurementPanel.add(new JLabel("RMS"));
    configureReadOnlyField(textFieldRms, 7);
    measurementPanel.add(textFieldRms);

    measurementPanel.add(new JLabel("Peak level"));
    configureReadOnlyField(textFieldPeakLevel, 7);
    measurementPanel.add(textFieldPeakLevel);

    measurementPanel.add(new JLabel("Dominant"));
    configureReadOnlyField(textFieldDominantFrequency, 9);
    measurementPanel.add(textFieldDominantFrequency);

    measurementPanel.add(new JLabel("Correlation"));
    configureReadOnlyField(textFieldStereoCorrelation, 7);
    measurementPanel.add(textFieldStereoCorrelation);

    measurementPanel.add(new JLabel("Clipping"));
    textFieldClipping.setHorizontalAlignment(SwingConstants.CENTER);
    textFieldClipping.setOpaque(true);
    configureReadOnlyField(textFieldClipping, 5);
    textFieldClipping.setEnabled(true);
    measurementPanel.add(textFieldClipping);

    measurementPanel.add(new JLabel("Delay"));
    configureReadOnlyField(textFieldStereoDelay, 11);
    measurementPanel.add(textFieldStereoDelay);

    measurementPanel.add(new JLabel("Angle"));
    configureReadOnlyField(textFieldStereoAngle, 8);
    measurementPanel.add(textFieldStereoAngle);

    measurementPanel.add(new JLabel("Conf"));
    configureReadOnlyField(textFieldStereoConfidence, 7);
    measurementPanel.add(textFieldStereoConfidence);
    return measurementPanel;
  }

  private void configureReadOnlyField(JTextField textField, int columns) {
    textField.setEnabled(false);
    textField.setEditable(false);
    textField.setColumns(columns);
  }

  private void initCenterAndEast() {
    waveformPanel.setBorder(UiTheme.createPanelBorder());
    phaseDiagramPanel.setBorder(UiTheme.createPanelBorder());
    spectrumPanel.setBorder(UiTheme.createPanelBorder());
    spectrogramPanel.setBorder(UiTheme.createPanelBorder());
    diagnosisPanel.setBorder(UiTheme.createPanelBorder());

    JPanel lowerPanel = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
    lowerPanel.setBorder(new EmptyBorder(8, 0, 0, 0));
    lowerPanel.add(spectrumPanel);
    lowerPanel.add(phaseDiagramPanel);

    JPanel spectrogramRow = new JPanel(new BorderLayout(8, 0));
    spectrogramRow.add(spectrogramPanel, BorderLayout.CENTER);
    spectrogramRow.add(diagnosisPanel, BorderLayout.EAST);

    JSplitPane innerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, lowerPanel, spectrogramRow);
    innerSplit.setResizeWeight(0.5);
    innerSplit.setDividerSize(7);
    innerSplit.setBorder(null);
    innerSplit.setContinuousLayout(true);

    JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, waveformPanel, innerSplit);
    splitPane.setResizeWeight(0.45);
    splitPane.setDividerSize(7);
    splitPane.setBorder(null);
    splitPane.setContinuousLayout(true);
    visualizationPanel.add(splitPane, BorderLayout.CENTER);
    contentPane.add(visualizationPanel, BorderLayout.CENTER);
  }

  private void initSouthSlider() {
    JSlider slider = new JSlider();
    slider.setMinimum(1);
    slider.setValue(audioCaptureService != null ? audioCaptureService.getDivisor() : 1);
    slider.setToolTipText("Adjust divisor (affects sampling / display)");
    slider.addChangeListener(
        new ChangeListener() {
          @Override
          public void stateChanged(ChangeEvent e) {
            int value = ((JSlider) e.getSource()).getValue();
            if (audioCaptureService != null) {
              audioCaptureService.setDivisor(value);
            }
            textFieldDivisor.setText(String.valueOf(value));
            contentPane.repaint();
          }
        });
    contentPane.add(slider, BorderLayout.SOUTH);
  }

  private void populateAudioDeviceChoices() {
    comboBoxAudioDevice.addItem(new AudioDeviceItem(null));
    AudioFormat format = defaultAudioFormat();
    DataLine.Info targetLineInfo = new DataLine.Info(TargetDataLine.class, format);
    for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
      if (supportsTargetLine(mixerInfo, targetLineInfo)) {
        comboBoxAudioDevice.addItem(new AudioDeviceItem(mixerInfo));
      }
    }
  }

  private boolean supportsTargetLine(Mixer.Info mixerInfo, DataLine.Info targetLineInfo) {
    try (Mixer mixer = AudioSystem.getMixer(mixerInfo)) {
      return mixer.isLineSupported(targetLineInfo);
    }
  }

  private void audioDeviceSelectionChanged(ItemEvent event) {
    if (event.getStateChange() != ItemEvent.SELECTED) {
      return;
    }
    if (inputMode == InputMode.DEMO) {
      return;
    }
    AudioDeviceItem item = (AudioDeviceItem) event.getItem();
    switchServicePreservingRunning(item.mixerInfo());
  }

  private void switchInputMode(InputMode newMode) {
    if (newMode == inputMode) {
      updateModeControls();
      return;
    }
    inputMode = newMode;
    updateModeControls();
    switchServicePreservingRunning(selectedMixerInfo());
  }

  private void updateModeControls() {
    boolean demoMode = inputMode == InputMode.DEMO;
    radioLiveMicrophone.setSelected(!demoMode);
    radioDemoMode.setSelected(demoMode);
    comboBoxAudioDevice.setEnabled(!demoMode);
    comboBoxDemoSignal.setEnabled(demoMode);
  }

  private void switchServicePreservingRunning(Mixer.Info mixerInfo) {
    boolean wasRunning = audioCaptureService != null && audioCaptureService.isRunning();
    stopAudioIfRunning();
    mntmStart.setSelected(false);
    initializeAudioService(mixerInfo);
    updateModeControls();
    if (wasRunning) {
      try {
        audioCaptureService.start();
        mntmStart.setSelected(true);
      } catch (Exception ex) {
        LOGGER.log(Level.SEVERE, "Failed to restart audio capture", ex);
        JOptionPane.showMessageDialog(
            this,
            "Failed to start selected source: " + ex.getMessage(),
            ERROR_TITLE,
            JOptionPane.ERROR_MESSAGE);
      }
    }
  }

  private Mixer.Info selectedMixerInfo() {
    Object selectedItem = comboBoxAudioDevice.getSelectedItem();
    if (selectedItem instanceof AudioDeviceItem audioDeviceItem) {
      return audioDeviceItem.mixerInfo();
    }
    return null;
  }

  private DemoSignalType selectedDemoSignal() {
    Object selectedItem = comboBoxDemoSignal.getSelectedItem();
    if (selectedItem instanceof DemoSignalType signalType) {
      return signalType;
    }
    return DemoSignalType.SINE;
  }

  private void toggleAudioStartStop(ActionEvent evt) {
    if (audioCaptureService == null) {
      LOGGER.warning("toggleAudioStartStop: audioCaptureService is null");
      JOptionPane.showMessageDialog(
          this, "AudioCaptureService is not available.", ERROR_TITLE, JOptionPane.ERROR_MESSAGE);
      return;
    }

    if (audioCaptureService.isRunning()) {
      LOGGER.info("Stopping audio capture");
      audioCaptureService.stop();
      mntmStart.setSelected(false);
    } else {
      LOGGER.info("Starting audio capture");
      try {
        audioCaptureService.start();
        mntmStart.setSelected(true);
      } catch (Exception ex) {
        LOGGER.log(Level.SEVERE, "Failed to start audio capture", ex);
        JOptionPane.showMessageDialog(
            this,
            "Failed to start audio capture: " + ex.getMessage(),
            ERROR_TITLE,
            JOptionPane.ERROR_MESSAGE);
        mntmStart.setSelected(false);
      }
    }
  }

  private void stopAudioIfRunning() {
    if (audioCaptureService != null && audioCaptureService.isRunning()) {
      audioCaptureService.stop();
    }
  }

  private void setFrozen(boolean frozen) {
    frozenBlock =
        frozen && audioCaptureService != null ? audioCaptureService.getLatestBlock() : null;
    waveformPanel.setFrozen(frozen);
    spectrumPanel.setFrozen(frozen);
    spectrogramPanel.setFrozen(frozen);
    diagnosisPanel.setFrozen(frozen);
    mntmFreeze.setSelected(frozen);
  }

  private void updateUIFromModel() {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(this::updateUIFromModel);
      return;
    }

    if (audioCaptureService != null) {
      textFieldDataSize.setText(String.valueOf(audioCaptureService.getLatestModel().getDataSize()));
      textFieldDivisor.setText(String.valueOf(audioCaptureService.getDivisor()));
      textFieldAudioFormat.setText(
          audioCaptureService.getFormat() != null
              ? audioCaptureService.getFormat().toString()
              : defaultAudioFormat().toString());
      double peakHz = spectrumPanel.getPeakFrequencyHz();
      textFieldPeakFrequency.setText(
          Double.isNaN(peakHz) ? "n/a" : String.format("%.1f Hz", peakHz));
      AudioBlock measurementBlock = currentMeasurementBlock();
      StereoDelaySnapshot delaySnapshot =
          measurementBlock != null && measurementBlock.channels() >= 2
              ? stereoDelayAnalyzer().analyze(measurementBlock)
              : null;
      MeasurementSnapshot measurements =
          measurementCalculator.calculate(measurementBlock, spectrumPanel.getCurrentSpectrum());
      updateMeasurementFields(measurements);
      updateStereoDelayFields(delaySnapshot);
      updateDiagnosis(measurementBlock, delaySnapshot);
      mntmStart.setSelected(audioCaptureService.isRunning());
    } else {
      textFieldDataSize.setText("");
      textFieldDivisor.setText("");
      textFieldAudioFormat.setText("");
      textFieldPeakFrequency.setText("n/a");
      updateMeasurementFields(NO_MEASUREMENT);
      updateStereoDelayFields(null);
      updateDiagnosis(null, null);
      mntmStart.setSelected(false);
    }
  }

  private void updateDiagnosis(AudioBlock block, StereoDelaySnapshot delay) {
    SpectrumSnapshot spectrum = spectrumPanel.getCurrentSpectrum();
    org.hammer.audio.spectrogram.SpectrogramHistory history = spectrogramPanel.getHistory();
    lastDiagnosis = diagnosisAnalyzer.analyze(block, spectrum, history, delay);
    diagnosisPanel.setFindings(lastDiagnosis);
  }

  private void updateMeasurementFields(MeasurementSnapshot measurements) {
    textFieldRms.setText(formatLevel(measurements.rms()));
    textFieldPeakLevel.setText(formatLevel(measurements.peakLevel()));
    textFieldDominantFrequency.setText(
        Double.isNaN(measurements.dominantFrequencyHz())
            ? "n/a"
            : String.format(Locale.ROOT, "%.1f Hz", measurements.dominantFrequencyHz()));
    textFieldStereoCorrelation.setText(
        measurements.stereoCorrelationAvailable()
            ? String.format(Locale.ROOT, "%.3f", measurements.stereoCorrelation())
            : "n/a");
    if (measurements.clipping()) {
      textFieldClipping.setText("YES");
      textFieldClipping.setForeground(Color.WHITE);
      textFieldClipping.setBackground(new Color(180, 0, 0));
    } else {
      textFieldClipping.setText("no");
      textFieldClipping.setForeground(Color.BLACK);
      textFieldClipping.setBackground(new Color(225, 240, 225));
    }
  }

  private static String formatLevel(double value) {
    return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.4f", value);
  }

  private void updateStereoDelayFields(StereoDelaySnapshot delay) {
    if (delay == null) {
      textFieldStereoDelay.setText("n/a");
      textFieldStereoAngle.setText("n/a");
      textFieldStereoConfidence.setText("n/a");
      return;
    }
    textFieldStereoConfidence.setText(String.format(Locale.ROOT, "%.2f", delay.confidence()));
    if (delay.valid()) {
      textFieldStereoDelay.setText(
          String.format(Locale.ROOT, "%+d / %+.2f ms", delay.delaySamples(), delay.delayMillis()));
      textFieldStereoAngle.setText(String.format(Locale.ROOT, "%+.1f°", delay.angleDegrees()));
    } else {
      textFieldStereoDelay.setText(delayStatusLabel(delay.status()));
      textFieldStereoAngle.setText("n/a");
    }
  }

  private double microphoneSpacingMeters() {
    try {
      double spacing = Double.parseDouble(textFieldMicrophoneSpacing.getText().trim());
      if (spacing > 0.0 && Double.isFinite(spacing)) {
        return spacing;
      }
    } catch (NumberFormatException ex) {
      LOGGER.fine(() -> "Invalid microphone spacing: " + textFieldMicrophoneSpacing.getText());
    }
    return StereoDelayAnalyzer.DEFAULT_MICROPHONE_SPACING_METERS;
  }

  private StereoDelayAnalyzer stereoDelayAnalyzer() {
    double spacingMeters = microphoneSpacingMeters();
    if (stereoDelayAnalyzer == null
        || !Double.isFinite(stereoDelayAnalyzerSpacingMeters)
        || Double.compare(stereoDelayAnalyzerSpacingMeters, spacingMeters) != 0) {
      stereoDelayAnalyzer =
          new StereoDelayAnalyzer(
              spacingMeters, StereoDelayAnalyzer.DEFAULT_SPEED_OF_SOUND_METERS_PER_SECOND, 0.35);
      stereoDelayAnalyzerSpacingMeters = spacingMeters;
    }
    return stereoDelayAnalyzer;
  }

  private static String delayStatusLabel(StereoDelayStatus status) {
    return switch (status) {
      case MONO_INPUT -> "mono";
      case SILENCE -> "silence";
      case LOW_CORRELATION -> "low corr";
      case DELAY_OUTSIDE_PHYSICAL_RANGE -> "impossible";
      case VALID -> "valid";
    };
  }

  private void exportMeasurementCsv() {
    AudioBlock block = currentMeasurementBlock();
    SpectrumSnapshot spectrum = spectrumPanel.getCurrentSpectrum();
    if (block == null && spectrum == null) {
      JOptionPane.showMessageDialog(
          this,
          "No measurement data available to export.",
          "Export CSV",
          JOptionPane.INFORMATION_MESSAGE);
      return;
    }

    JFileChooser chooser = new JFileChooser();
    chooser.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
    chooser.setSelectedFile(new java.io.File("measurement.csv"));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }

    java.io.File file = ensureExtension(chooser.getSelectedFile(), ".csv");
    try (PrintWriter writer =
        new PrintWriter(Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))) {
      writeMeasurementCsv(writer, block, spectrum);
      JOptionPane.showMessageDialog(
          this, "Measurement exported to " + file, "Export CSV", JOptionPane.INFORMATION_MESSAGE);
    } catch (IOException ex) {
      LOGGER.log(Level.SEVERE, "Failed to export CSV", ex);
      JOptionPane.showMessageDialog(
          this, "Failed to export CSV: " + ex.getMessage(), ERROR_TITLE, JOptionPane.ERROR_MESSAGE);
    }
  }

  private void exportMeasurementPng() {
    if (visualizationPanel.getWidth() <= 0 || visualizationPanel.getHeight() <= 0) {
      JOptionPane.showMessageDialog(
          this,
          "Visualization is not ready to export.",
          "Export PNG",
          JOptionPane.INFORMATION_MESSAGE);
      return;
    }

    JFileChooser chooser = new JFileChooser();
    chooser.setFileFilter(new FileNameExtensionFilter("PNG images", "png"));
    chooser.setSelectedFile(new java.io.File("measurement.png"));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }

    java.io.File file = ensureExtension(chooser.getSelectedFile(), ".png");
    BufferedImage image =
        new BufferedImage(
            visualizationPanel.getWidth(),
            visualizationPanel.getHeight(),
            BufferedImage.TYPE_INT_ARGB);
    java.awt.Graphics2D graphics = image.createGraphics();
    try {
      visualizationPanel.paintAll(graphics);
    } finally {
      graphics.dispose();
    }
    try {
      ImageIO.write(image, "png", file);
      JOptionPane.showMessageDialog(
          this, "Measurement exported to " + file, "Export PNG", JOptionPane.INFORMATION_MESSAGE);
    } catch (IOException ex) {
      LOGGER.log(Level.SEVERE, "Failed to export PNG", ex);
      JOptionPane.showMessageDialog(
          this, "Failed to export PNG: " + ex.getMessage(), ERROR_TITLE, JOptionPane.ERROR_MESSAGE);
    }
  }

  private void exportEvidenceBundle() {
    AudioBlock block = currentMeasurementBlock();
    SpectrumSnapshot spectrum = spectrumPanel.getCurrentSpectrum();
    org.hammer.audio.spectrogram.SpectrogramHistory history = spectrogramPanel.getHistory();
    StereoDelaySnapshot delay = null;
    if (block != null && block.channels() >= 2) {
      delay = stereoDelayAnalyzer().analyze(block);
    }
    org.hammer.audio.diagnosis.DiagnosisSnapshot diagnosis =
        diagnosisAnalyzer.analyze(block, spectrum, history, delay);
    lastDiagnosis = diagnosis;
    diagnosisPanel.setFindings(diagnosis);

    if (block == null
        && spectrum == null
        && (history == null || history.isEmpty())
        && delay == null
        && diagnosis.isEmpty()) {
      JOptionPane.showMessageDialog(
          this,
          "No measurement data available to export.",
          "Export evidence bundle",
          JOptionPane.INFORMATION_MESSAGE);
      return;
    }

    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Export evidence bundle");
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    java.io.File parent = chooser.getSelectedFile();
    if (parent == null) {
      return;
    }
    BufferedImage screenshot = null;
    if (visualizationPanel.getWidth() > 0 && visualizationPanel.getHeight() > 0) {
      screenshot =
          new BufferedImage(
              visualizationPanel.getWidth(),
              visualizationPanel.getHeight(),
              BufferedImage.TYPE_INT_ARGB);
      java.awt.Graphics2D graphics = screenshot.createGraphics();
      try {
        visualizationPanel.paintAll(graphics);
      } finally {
        graphics.dispose();
      }
    }
    org.hammer.audio.export.EvidenceData payload =
        org.hammer.audio.export.EvidenceData.builder()
            .timestamp(java.time.Instant.now())
            .screenshot(screenshot)
            .block(block)
            .spectrum(spectrum)
            .spectrogram(history)
            .stereoDelay(delay)
            .diagnosis(diagnosis)
            .notes(
                inputMode == InputMode.DEMO ? "Demo signal: " + selectedDemoSignal() : "Live input")
            .build();
    try {
      java.nio.file.Path bundleDir =
          new org.hammer.audio.export.EvidenceBundleExporter().export(parent.toPath(), payload);
      JOptionPane.showMessageDialog(
          this,
          "Evidence bundle exported to " + bundleDir,
          "Export evidence bundle",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (IOException | IllegalArgumentException ex) {
      LOGGER.log(Level.SEVERE, "Failed to export evidence bundle", ex);
      JOptionPane.showMessageDialog(
          this,
          "Failed to export evidence bundle: " + ex.getMessage(),
          ERROR_TITLE,
          JOptionPane.ERROR_MESSAGE);
    }
  }

  private AudioBlock currentMeasurementBlock() {
    if (frozenBlock != null) {
      return frozenBlock;
    }
    return audioCaptureService != null ? audioCaptureService.getLatestBlock() : null;
  }

  private void writeMeasurementCsv(
      PrintWriter writer, AudioBlock block, SpectrumSnapshot spectrum) {
    writer.println("section,key,value");
    if (block != null) {
      writer.printf(Locale.ROOT, "metadata,sampleRate,%.3f%n", block.format().sampleRate());
      writer.printf(Locale.ROOT, "metadata,channels,%d%n", block.channels());
      writer.printf(Locale.ROOT, "metadata,frames,%d%n", block.frames());
      writer.printf(Locale.ROOT, "metadata,frameIndex,%d%n", block.frameIndex());
      writer.println();
      writer.print("sampleIndex");
      for (int channel = 0; channel < block.channels(); channel++) {
        writer.print(",channel");
        writer.print(channel);
      }
      writer.println();
      float[][] samples = block.samples();
      for (int frame = 0; frame < block.frames(); frame++) {
        writer.print(frame);
        for (int channel = 0; channel < block.channels(); channel++) {
          writer.printf(Locale.ROOT, ",%.9f", samples[channel][frame]);
        }
        writer.println();
      }
    }
    if (spectrum != null) {
      writer.println();
      writer.println("bin,frequencyHz,magnitude");
      for (int bin = 0; bin < spectrum.binCount(); bin++) {
        writer.printf(
            Locale.ROOT,
            "%d,%.6f,%.9f%n",
            bin,
            spectrum.frequencyOfBin(bin),
            spectrum.magnitude(bin));
      }
    }
  }

  private static AudioFormat defaultAudioFormat() {
    return new AudioFormat(
        DEFAULT_SAMPLE_RATE,
        DEFAULT_SAMPLE_BITS,
        DEFAULT_CHANNELS,
        DEFAULT_SIGNED,
        DEFAULT_BIG_ENDIAN);
  }

  private static java.io.File ensureExtension(java.io.File file, String extension) {
    String name = file.getName().toLowerCase(Locale.ROOT);
    if (name.endsWith(extension)) {
      return file;
    }
    return new java.io.File(file.getParentFile(), file.getName() + extension);
  }

  private void startRecording() {
    if (recordingTap != null && !recordingTap.isClosed()) {
      JOptionPane.showMessageDialog(
          this,
          "A recording is already in progress (" + recordingTap.file() + ").",
          "Recording",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    if (audioCaptureService == null) {
      return;
    }
    JFileChooser chooser = new JFileChooser();
    chooser.setFileFilter(new FileNameExtensionFilter("AudioAnalyzer recording (*.aar)", "aar"));
    chooser.setSelectedFile(new java.io.File("recording.aar"));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    java.io.File file = ensureExtension(chooser.getSelectedFile(), ".aar");
    try {
      recordingTap =
          org.hammer.audio.RecordingTap.start(
              audioCaptureService, file.toPath(), UiConstants.REFRESH_INTERVAL_MS);
      JOptionPane.showMessageDialog(
          this,
          "Recording to " + file + " (Stop recording when done).",
          "Recording",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (IOException ex) {
      LOGGER.log(Level.SEVERE, "Failed to start recording", ex);
      JOptionPane.showMessageDialog(
          this,
          "Failed to start recording: " + ex.getMessage(),
          ERROR_TITLE,
          JOptionPane.ERROR_MESSAGE);
    }
  }

  private void stopRecording() {
    if (recordingTap == null || recordingTap.isClosed()) {
      JOptionPane.showMessageDialog(
          this, "No recording is in progress.", "Recording", JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    try {
      long blocks = recordingTap.blocksWritten();
      java.nio.file.Path file = recordingTap.file();
      recordingTap.stop();
      JOptionPane.showMessageDialog(
          this,
          "Recording stopped (" + blocks + " blocks written to " + file + ").",
          "Recording",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (IOException ex) {
      LOGGER.log(Level.SEVERE, "Failed to close recording", ex);
      JOptionPane.showMessageDialog(
          this,
          "Failed to close recording: " + ex.getMessage(),
          ERROR_TITLE,
          JOptionPane.ERROR_MESSAGE);
    }
  }

  private void stopActiveRecordingTapQuietly() {
    if (recordingTap == null || recordingTap.isClosed()) {
      return;
    }
    try {
      recordingTap.stop();
    } catch (IOException ex) {
      LOGGER.log(Level.WARNING, "Failed to stop recording tap before switching source", ex);
    }
  }

  private void openRecording() {
    JFileChooser chooser = new JFileChooser();
    chooser.setFileFilter(new FileNameExtensionFilter("AudioAnalyzer recording (*.aar)", "aar"));
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    java.io.File file = chooser.getSelectedFile();
    try {
      org.hammer.audio.RecordedAudioCaptureService replay =
          org.hammer.audio.RecordedAudioCaptureService.open(file.toPath(), false);
      // Stop any in-progress recording before swapping the capture service so the tap doesn't
      // keep polling the previous source and silently corrupt the recording.
      stopActiveRecordingTapQuietly();
      stopAudioIfRunning();
      audioCaptureService = replay;
      waveformPanel.setAudioCaptureService(replay);
      phaseDiagramPanel.setAudioCaptureService(replay);
      spectrumPanel.setAudioCaptureService(replay);
      spectrogramPanel.setAudioCaptureService(replay);
      setFrozen(false);
      replay.start();
      mntmStart.setSelected(true);
      JOptionPane.showMessageDialog(
          this,
          "Replaying " + file + " (" + replay.blockCount() + " blocks).",
          "Recording",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (IOException ex) {
      LOGGER.log(Level.SEVERE, "Failed to open recording", ex);
      JOptionPane.showMessageDialog(
          this,
          "Failed to open recording: " + ex.getMessage(),
          ERROR_TITLE,
          JOptionPane.ERROR_MESSAGE);
    }
  }

  private void compareRecordings() {
    JFileChooser chooserA = new JFileChooser();
    chooserA.setDialogTitle("Select recording A");
    chooserA.setFileFilter(new FileNameExtensionFilter("AudioAnalyzer recording (*.aar)", "aar"));
    if (chooserA.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    JFileChooser chooserB = new JFileChooser();
    chooserB.setDialogTitle("Select recording B");
    chooserB.setFileFilter(new FileNameExtensionFilter("AudioAnalyzer recording (*.aar)", "aar"));
    if (chooserB.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    java.io.File fileA = chooserA.getSelectedFile();
    java.io.File fileB = chooserB.getSelectedFile();
    try {
      org.hammer.audio.compare.ComparisonReport report =
          new org.hammer.audio.compare.RecordingComparator()
              .compareFiles(fileA.toPath(), fileB.toPath(), fileA.getName(), fileB.getName());
      String markdown =
          new org.hammer.audio.compare.MarkdownComparisonReportRenderer().render(report);

      JFileChooser save = new JFileChooser();
      save.setDialogTitle("Save A/B comparison report");
      save.setFileFilter(new FileNameExtensionFilter("Markdown (*.md)", "md"));
      save.setSelectedFile(new java.io.File("ab-report.md"));
      if (save.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
        java.io.File out = ensureExtension(save.getSelectedFile(), ".md");
        Files.writeString(out.toPath(), markdown, StandardCharsets.UTF_8);
        JOptionPane.showMessageDialog(
            this, "Report saved to " + out, "A/B comparison", JOptionPane.INFORMATION_MESSAGE);
      } else {
        // Preview in a dialog if the user does not save.
        javax.swing.JTextArea area = new javax.swing.JTextArea(markdown, 24, 80);
        area.setEditable(false);
        area.setCaretPosition(0);
        JOptionPane.showMessageDialog(
            this,
            new javax.swing.JScrollPane(area),
            "A/B comparison report",
            JOptionPane.INFORMATION_MESSAGE);
      }
    } catch (IOException ex) {
      LOGGER.log(Level.SEVERE, "Failed to compare recordings", ex);
      JOptionPane.showMessageDialog(
          this,
          "Failed to compare recordings: " + ex.getMessage(),
          ERROR_TITLE,
          JOptionPane.ERROR_MESSAGE);
    }
  }

  private record AudioDeviceItem(Mixer.Info mixerInfo) {
    @Override
    public String toString() {
      if (mixerInfo == null) {
        return "System default input";
      }
      return mixerInfo.getName() + " — " + mixerInfo.getDescription();
    }
  }

  private enum InputMode {
    LIVE,
    DEMO
  }

  private final class SwingAction extends AbstractAction {
    private static final long serialVersionUID = 1L;

    SwingAction() {
      putValue(NAME, "About");
      putValue(SHORT_DESCRIPTION, "Some short description");
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      JOptionPane.showMessageDialog(
          AudioAnalyseFrame.this, "Carsten Hammer carsten.hammer@t-online.de");
    }
  }
}
