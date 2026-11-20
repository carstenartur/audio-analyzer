package org.hammer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import org.hammer.audio.acquisition.health.ExperimentHealthFinding;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.Catalogue;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.Configuration;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.Preflight;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.ProfileView;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.RunView;
import org.hammer.audio.experiment.operations.MicrophoneExperimentService;

/**
 * Desktop projection of the same profile, readiness, runtime and evidence service as the browser.
 */
// This view composes Swing controls and immutable domain projections, without its own backend.
@SuppressWarnings("PMD.CouplingBetweenObjects")
public final class MicrophoneExperimentPanel extends JPanel implements AutoCloseable {

  private static final long serialVersionUID = 1L;
  private final transient MicrophoneExperimentService service;
  private final JTextField name = new JTextField("Microphone-array experiment", 24);
  private final JComboBox<String> mode =
      new JComboBox<>(new String[] {"SIMULATION", "REPLAY", "LIVE"});
  private final JComboBox<String> profile = new JComboBox<>();
  private final JComboBox<String> scenario = new JComboBox<>();
  private final JComboBox<String> replay = new JComboBox<>();
  private final JSpinner duration = new JSpinner(new SpinnerNumberModel(5, 1, 3600, 1));
  private final JTextField filename = new JTextField("capture.aarec", 16);
  private final JCheckBox record = new JCheckBox("Record all consumed blocks", true);
  private final JTextArea definition = new JTextArea(18, 64);
  private final JTextArea health = readOnlyArea();
  private final JTextArea evidence = readOnlyArea();
  private final JTextArea status = runtimeStatusArea();
  private final JButton check = new JButton("Check readiness");
  private final JButton startButton = new JButton("Start experiment");
  private final JButton stop = new JButton("Stop & finalize");
  private final JButton export = new JButton("Export evidence ZIP");
  private final JButton acknowledge = new JButton("Acknowledge alert…");
  private final DefaultTableModel trackModel =
      new DefaultTableModel(new String[] {"Track", "X (m)", "Y (m)", "Confidence"}, 0);
  private final Timer timer;
  private transient Catalogue catalogue;
  private transient Preflight preflight;
  private transient Configuration reviewedConfiguration;
  private String savedDefinition = "";
  private boolean busy;

  /**
   * Construct a view; filesystem and device work is always dispatched away from the event thread.
   */
  public MicrophoneExperimentPanel(MicrophoneExperimentService service) {
    super(new BorderLayout(10, 10));
    this.service = service;
    setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    getAccessibleContext().setAccessibleName("Microphone experiment workflow");
    add(
        new JScrollPane(
            status,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER),
        BorderLayout.NORTH);
    JTabbedPane tabs = new JTabbedPane();
    tabs.addTab("1. Source and profile", setupPanel());
    tabs.addTab(
        "2. Readiness and runtime",
        new JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            new JScrollPane(health),
            new JScrollPane(new JTable(trackModel))));
    tabs.addTab("3. Evidence and history", new JScrollPane(evidence));
    add(tabs, BorderLayout.CENTER);
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
    actions.add(check);
    actions.add(startButton);
    actions.add(stop);
    actions.add(export);
    actions.add(acknowledge);
    add(actions, BorderLayout.SOUTH);
    check.addActionListener(event -> inspect());
    startButton.addActionListener(event -> start());
    stop.addActionListener(
        event ->
            background(
                () -> {
                  service.stop();
                  return service.snapshot();
                },
                this::display));
    export.addActionListener(event -> exportEvidence());
    acknowledge.addActionListener(event -> acknowledgeAlert());
    acknowledge.setToolTipText(
        "Records an unattributed local acknowledgement; source conditions and history remain"
            + " visible.");
    profile.addActionListener(event -> selectProfile());
    startButton.setEnabled(false);
    stop.setEnabled(false);
    export.setEnabled(false);
    acknowledge.setEnabled(false);
    timer = new Timer(500, event -> display(service.snapshot()));
    timer.start();
    refreshCatalogue();
  }

  private JPanel setupPanel() {
    JPanel panel = new JPanel(new BorderLayout(8, 8));
    JPanel choices = new JPanel(new GridLayout(0, 2, 8, 6));
    addField(choices, "Experiment name", name);
    addField(choices, "Source mode", mode);
    addField(choices, "Saved microphone profile", profile);
    addField(choices, "Deterministic simulation", scenario);
    addField(choices, "Managed replay input", replay);
    addField(choices, "Intended duration (seconds)", duration);
    addField(choices, "Recording filename", filename);
    choices.add(record);
    JButton importRecording = new JButton("Import recording / recovery input");
    importRecording.addActionListener(event -> importRecording());
    choices.add(importRecording);
    panel.add(choices, BorderLayout.NORTH);
    JPanel profilePanel = new JPanel(new BorderLayout(6, 6));
    profilePanel.setBorder(
        BorderFactory.createTitledBorder("Geometry, device and measured calibration"));
    definition.getAccessibleContext().setAccessibleName("Complete portable microphone profile");
    profilePanel.add(new JScrollPane(definition), BorderLayout.CENTER);
    JPanel profileActions = new JPanel(new FlowLayout(FlowLayout.LEADING));
    JButton edit = new JButton("Edit profile settings…");
    edit.addActionListener(event -> editProfile());
    JButton save = new JButton("Validate & save profile");
    save.addActionListener(
        event -> {
          String draft = definition.getText();
          background(
              () -> service.saveProfile(draft),
              result -> {
                savedDefinition = result.definition();
                definition.setText(savedDefinition);
                preflight = null;
                reviewedConfiguration = null;
                refreshCatalogue(result.id());
              });
        });
    JButton recover = new JButton("Recover selected input to new file");
    recover.addActionListener(
        event -> {
          String id = (String) replay.getSelectedItem();
          background(() -> service.recoverRecording(id), result -> refreshCatalogue());
        });
    profileActions.add(edit);
    profileActions.add(save);
    profileActions.add(recover);
    profilePanel.add(profileActions, BorderLayout.SOUTH);
    panel.add(profilePanel, BorderLayout.CENTER);
    return panel;
  }

  private void editProfile() {
    if (catalogue == null) {
      return;
    }
    try {
      MicrophoneProfileEditorPanel editor =
          new MicrophoneProfileEditorPanel(definition.getText(), catalogue.devices());
      if (JOptionPane.showConfirmDialog(
              this,
              editor,
              "Microphone profile and measured calibration",
              JOptionPane.OK_CANCEL_OPTION,
              JOptionPane.PLAIN_MESSAGE)
          == JOptionPane.OK_OPTION) {
        definition.setText(editor.definition());
        preflight = null;
      }
    } catch (RuntimeException exception) {
      showFailure(exception);
    }
  }

  private void inspect() {
    if (!definition.getText().equals(savedDefinition)) {
      showFailure(
          new IllegalStateException("Validate and save the edited profile before preflight."));
      return;
    }
    Configuration configuration;
    try {
      configuration = configuration();
    } catch (IllegalArgumentException exception) {
      showFailure(exception);
      return;
    }
    background(
        () -> service.preflight(configuration),
        result -> {
          reviewedConfiguration = configuration;
          preflight = result;
          health.setText(describe(result.health()));
          status.setText(result.health().state() + " — " + result.health().stage());
          startButton.setEnabled(result.health().startAllowed());
        });
  }

  private void start() {
    Configuration configuration;
    try {
      configuration = configuration();
    } catch (IllegalArgumentException exception) {
      showFailure(exception);
      return;
    }
    if (preflight == null
        || !configuration.equals(reviewedConfiguration)
        || !definition.getText().equals(savedDefinition)) {
      showFailure(new IllegalStateException("Choices changed; review a fresh preflight."));
      return;
    }
    boolean warning =
        preflight.health().findings().stream()
            .anyMatch(finding -> "storage.margin".equals(finding.code()));
    if (warning
        && JOptionPane.showConfirmDialog(
                this,
                "The displayed storage margin is below the planned duration. Continue?",
                "Recording storage warning",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE)
            != JOptionPane.YES_OPTION) {
      return;
    }
    String token = preflight.token();
    background(
        () -> service.start(configuration, token, warning),
        result -> {
          preflight = null;
          reviewedConfiguration = null;
          startButton.setEnabled(false);
          display(service.snapshot());
        });
  }

  private void display(RunView snapshot) {
    boolean running = updateControls(snapshot);
    if (preflight != null && !running) {
      return;
    }
    status.setText(
        snapshot.health().experimentName()
            + " · "
            + snapshot.health().state()
            + " · "
            + snapshot.health().stage()
            + " · Intended: "
            + snapshot.health().facts().getOrDefault("intended.seconds", "unknown")
            + " s\n"
            + RecordingStatusText.summary(snapshot.recording()));
    status.setCaretPosition(0);
    status.setForeground(
        snapshot.recording() != null
                && snapshot.recording().state()
                    == org.hammer.audio.recording.runtime.RecordingState.RECORDING
            ? new Color(255, 160, 160)
            : javax.swing.UIManager.getColor("Label.foreground"));
    health.setText(
        describe(snapshot.health())
            + "\n\nRecording runtime:\n"
            + RecordingStatusText.details(snapshot.recording()));
    trackModel.setRowCount(0);
    snapshot
        .tracks()
        .forEach(
            track ->
                trackModel.addRow(
                    new Object[] {track.id(), track.x(), track.y(), track.confidence()}));
    evidence.setText(
        "Evidence complete: "
            + snapshot.health().facts().getOrDefault("evidence.complete", "Pending")
            + "\nBenchmark: "
            + snapshot.health().facts().getOrDefault("benchmark.status", "Pending")
            + "\nRecording SHA-256: "
            + snapshot.health().facts().getOrDefault("recording.sha256", "Pending")
            + "\n\n"
            + "Pause is unavailable for continuous synchronized capture. An early stop remains"
            + " incomplete.\n\n"
            + snapshot.health().history()
            + "\n\nManaged recordings:\n"
            + (catalogue == null ? "Loading" : catalogue.recordings()));
  }

  private boolean updateControls(RunView snapshot) {
    boolean running =
        switch (snapshot.health().state()) {
          case RUNNING, DEGRADED, STOPPING -> true;
          default -> false;
        };
    stop.setEnabled(running && !busy);
    export.setEnabled(snapshot.exportAvailable() && !busy);
    acknowledge.setEnabled(acknowledgementAvailable(snapshot));
    boolean editable = !running && !busy;
    check.setEnabled(editable && catalogue != null);
    startButton.setEnabled(editable && reviewedChoicesReady());
    return running;
  }

  private boolean reviewedChoicesReady() {
    return preflight != null
        && preflight.health().startAllowed()
        && definition.getText().equals(savedDefinition)
        && sameChoices();
  }

  private boolean acknowledgementAvailable(RunView snapshot) {
    return !busy && preflight == null && !acknowledgeableFindings(snapshot).isEmpty();
  }

  private static List<ExperimentHealthFinding> acknowledgeableFindings(RunView snapshot) {
    if (snapshot.runId().isEmpty()) {
      return List.of();
    }
    return snapshot.health().findings().stream()
        .filter(
            finding ->
                finding.acknowledgeable()
                    && !finding.acknowledged()
                    && finding.severity() != ExperimentHealthFinding.Severity.BLOCKING)
        .toList();
  }

  private void acknowledgeAlert() {
    RunView snapshot = service.snapshot();
    if (!acknowledgementAvailable(snapshot)) {
      return;
    }
    List<ExperimentHealthFinding> findings = acknowledgeableFindings(snapshot);
    List<String> choices =
        findings.stream().map(finding -> finding.message() + " [" + finding.code() + "]").toList();
    Object selected =
        JOptionPane.showInputDialog(
            this,
            "Acknowledge one alert as an unattributed local operator. The condition and history"
                + " remain visible.",
            "Acknowledge experiment alert",
            JOptionPane.QUESTION_MESSAGE,
            null,
            choices.toArray(String[]::new),
            choices.getFirst());
    int index = choices.indexOf(selected);
    if (index < 0) {
      return;
    }
    String code = findings.get(index).code();
    background(
        () -> {
          service.acknowledge(code, "Unattributed local operator");
          return service.snapshot();
        },
        this::display);
  }

  private boolean sameChoices() {
    try {
      return configuration().equals(reviewedConfiguration);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private Configuration configuration() {
    return new Configuration(
        name.getText(),
        (String) mode.getSelectedItem(),
        (String) profile.getSelectedItem(),
        (String) scenario.getSelectedItem(),
        (String) replay.getSelectedItem(),
        ((Number) duration.getValue()).intValue(),
        record.isSelected(),
        filename.getText());
  }

  private void selectProfile() {
    if (catalogue == null) {
      return;
    }
    catalogue.profiles().stream()
        .filter(item -> item.id().equals(profile.getSelectedItem()))
        .findFirst()
        .ifPresent(
            item -> {
              savedDefinition = item.definition();
              definition.setText(savedDefinition);
              preflight = null;
            });
  }

  private void refreshCatalogue() {
    refreshCatalogue((String) profile.getSelectedItem());
  }

  private void refreshCatalogue(String selected) {
    background(
        service::catalogue,
        result -> {
          catalogue = result;
          profile.removeAllItems();
          result.profiles().stream().map(ProfileView::id).forEach(profile::addItem);
          if (selected != null) {
            profile.setSelectedItem(selected);
          }
          scenario.removeAllItems();
          result.scenarios().forEach(scenario::addItem);
          replay.removeAllItems();
          result.recordings().forEach(item -> replay.addItem(item.id()));
          selectProfile();
        });
  }

  private void importRecording() {
    JFileChooser chooser = new JFileChooser();
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    Path path = chooser.getSelectedFile().toPath();
    background(
        () -> {
          try (InputStream input = Files.newInputStream(path)) {
            return service.importRecording(input);
          }
        },
        result -> refreshCatalogue());
  }

  private void exportEvidence() {
    JFileChooser chooser = new JFileChooser();
    chooser.setSelectedFile(new java.io.File("experiment-evidence.zip"));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    Path path = chooser.getSelectedFile().toPath();
    background(
        () -> Files.exists(path),
        exists -> {
          if (!exists
              || JOptionPane.showConfirmDialog(
                      this, "Replace " + path + "?", "Export evidence", JOptionPane.YES_NO_OPTION)
                  == JOptionPane.YES_OPTION) {
            writeEvidence(path);
          }
        });
  }

  private void writeEvidence(Path path) {
    background(
        () -> {
          service.exportEvidence(path);
          return path;
        },
        result -> JOptionPane.showMessageDialog(this, "Evidence saved to " + result));
  }

  private <T> void background(Callable<T> task, Consumer<T> success) {
    busy = true;
    new SwingWorker<T, Void>() {
      @Override
      protected T doInBackground() throws Exception {
        return task.call();
      }

      @Override
      protected void done() {
        busy = false;
        try {
          success.accept(get());
        } catch (Exception exception) {
          showFailure(exception);
        }
      }
    }.execute();
  }

  private void showFailure(Exception exception) {
    JOptionPane.showMessageDialog(
        this, exception.getMessage(), "Experiment operation", JOptionPane.ERROR_MESSAGE);
  }

  private static String describe(
      org.hammer.audio.acquisition.health.ExperimentHealthSnapshot snapshot) {
    return ExperimentHealthText.describe(snapshot);
  }

  private static JTextArea readOnlyArea() {
    JTextArea area = new JTextArea(14, 64);
    area.setEditable(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    return area;
  }

  private static JTextArea runtimeStatusArea() {
    JTextArea area = new JTextArea("NOT CONFIGURED — choose a source and review readiness", 8, 0);
    area.setEditable(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.getAccessibleContext().setAccessibleName("Experiment runtime status");
    return area;
  }

  private static void addField(JPanel panel, String label, javax.swing.JComponent field) {
    JLabel caption = new JLabel(label);
    caption.setLabelFor(field);
    panel.add(caption);
    panel.add(field);
  }

  @Override
  public void close() {
    closeWhenFinished(
        () -> {
          /* No window is owned by this reusable panel. */
        });
  }

  /** Keep the host window available until background recording/evidence finalization completes. */
  public void closeWhenFinished(Runnable completed) {
    timer.stop();
    background(
        () -> {
          service.close();
          return null;
        },
        ignored -> completed.run());
  }
}
