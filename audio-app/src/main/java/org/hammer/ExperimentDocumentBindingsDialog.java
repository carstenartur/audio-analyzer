package org.hammer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import org.hammer.audio.JavaSoundCaptureDeviceDiscovery;
import org.hammer.audio.acquisition.CaptureDeviceDescriptor;
import org.hammer.audio.experiment.document.ExperimentCaptureBinding;
import org.hammer.audio.experiment.document.ExperimentDocument.AssetReference;
import org.hammer.audio.experiment.document.ExperimentDocumentPreview;
import org.hammer.audio.experiment.document.ExperimentEvidenceExporter;
import org.hammer.audio.experiment.document.ExperimentLocalBindingPreview;
import org.hammer.audio.experiment.document.ExperimentLocalBindingService;
import org.hammer.audio.experiment.document.ExperimentLocalBindings;
import org.hammer.audio.experiment.document.binding.ExperimentCaptureBindingResolver;
import org.hammer.audio.plugin.document.DocumentValue;

/** Explicit local file/device bindings and separate evidence export; never executes a setup. */
// This UI adapter connects portable requirements, local chooser state and the shared validators.
@SuppressWarnings("PMD.CouplingBetweenObjects")
final class ExperimentDocumentBindingsDialog {
  private final JFrame owner;
  private final ExperimentDocumentPreview preview;
  private final Path original;
  private final JDialog dialog;
  private final BindingForm form;
  private final JTextArea diagnostics = new JTextArea(8, 70);
  private final JButton verify = new JButton("Verify local bindings");
  private final JButton evidence = new JButton("Export evidence package...");
  private final JButton cancel = new JButton("Cancel operation");
  private ExperimentDocumentTask<String> active;

  ExperimentDocumentBindingsDialog(JFrame owner, ExperimentDocumentPreview preview, Path original) {
    this.owner = owner;
    this.preview = preview;
    this.original = original;
    this.dialog = new JDialog(owner, "Local experiment bindings", false);
    this.form = new BindingForm();
  }

  void show() {
    dialog.setLayout(new BorderLayout(8, 8));
    diagnostics.setEditable(false);
    diagnostics.setLineWrap(true);
    diagnostics.setWrapStyleWord(true);
    JPanel center = new JPanel(new BorderLayout());
    center.add(new JScrollPane(form.panel()), BorderLayout.CENTER);
    center.add(new JScrollPane(diagnostics), BorderLayout.SOUTH);
    dialog.add(center, BorderLayout.CENTER);
    dialog.add(actions(), BorderLayout.SOUTH);
    dialog.setPreferredSize(new Dimension(900, 680));
    dialog.pack();
    dialog.setLocationRelativeTo(owner);
    dialog.setVisible(true);
  }

  private JPanel actions() {
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    cancel.setEnabled(false);
    verify.addActionListener(event -> run(null));
    evidence.addActionListener(
        event -> {
          JFileChooser chooser = new JFileChooser();
          chooser.setSelectedFile(new java.io.File("experiment-evidence.zip"));
          if (chooser.showSaveDialog(dialog) == JFileChooser.APPROVE_OPTION) {
            run(chooser.getSelectedFile().toPath());
          }
        });
    cancel.addActionListener(
        event -> {
          if (active != null) {
            active.requestCancellation();
            cancel.setEnabled(false);
            diagnostics.setText("Cancellation requested; waiting for the operation to finish");
          }
        });
    JButton close = new JButton("Close");
    close.addActionListener(event -> dialog.dispose());
    actions.add(verify);
    actions.add(evidence);
    actions.add(cancel);
    actions.add(close);
    return actions;
  }

  private void run(Path destination) {
    if (active != null) {
      return;
    }
    final Selection selection;
    try {
      selection = form.selection();
    } catch (IllegalArgumentException exception) {
      diagnostics.setText(exception.getMessage());
      return;
    }
    verify.setEnabled(false);
    evidence.setEnabled(false);
    cancel.setEnabled(true);
    diagnostics.setText("Inspecting selected resources…");
    active =
        new ExperimentDocumentTask<>(
            () -> inspect(selection, destination),
            finished -> {
              active = null;
              verify.setEnabled(true);
              evidence.setEnabled(true);
              cancel.setEnabled(false);
              try {
                diagnostics.setText(finished.get());
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              } catch (ExecutionException exception) {
                diagnostics.setText(exception.getCause().getMessage());
              }
            });
    active.execute();
  }

  private String inspect(Selection selection, Path destination) throws java.io.IOException {
    CaptureSelection chosen = selection.capture();
    ExperimentCaptureBinding capture =
        new ExperimentCaptureBindingResolver()
            .resolve(
                preview.document(),
                chosen.deviceId(),
                chosen.channels(),
                chosen.mapping(),
                chosen.synchronizedChannels(),
                chosen.calibrationConfirmed());
    ExperimentLocalBindings bindings =
        new ExperimentLocalBindings(
            selection.assets(), selection.directory(), capture, selection.confirmedTypes());
    if (destination != null) {
      new ExperimentEvidenceExporter().save(destination, original, preview, bindings);
      return "Evidence package saved to " + destination;
    }
    ExperimentLocalBindingPreview result =
        new ExperimentLocalBindingService().inspect(preview, bindings);
    return (result.ready()
            ? "Selected resources satisfy the setup. No device was opened.\n"
            : "Resolve these diagnostics before execution; inspection remains available.\n")
        + result.diagnostics();
  }

  private static JPanel pathSelector(JDialog owner, JTextField field, boolean directory) {
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(field, BorderLayout.CENTER);
    JButton browse = new JButton("Browse...");
    panel.add(browse, BorderLayout.EAST);
    browse.addActionListener(
        event -> {
          JFileChooser chooser = new JFileChooser();
          chooser.setFileSelectionMode(
              directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
          if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().toPath().toString());
          }
        });
    return panel;
  }

  private record CaptureSelection(
      String deviceId,
      int channels,
      List<Integer> mapping,
      boolean synchronizedChannels,
      boolean calibrationConfirmed) {
    // Immutable value; record components carry the complete state.
  }

  private record Selection(
      Map<String, Path> assets,
      Path directory,
      Map<String, String> confirmedTypes,
      CaptureSelection capture) {
    // Immutable value; record components carry the complete state.
  }

  // Swing form maps are accessed only on the event-dispatch thread and copied for worker tasks.
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private final class BindingForm {
    private final Map<String, JTextField> assets = new TreeMap<>();
    private final Map<String, JCheckBox> types = new TreeMap<>();
    private final JTextField output = new JTextField(45);
    private final List<CaptureDeviceDescriptor> discovered =
        new JavaSoundCaptureDeviceDiscovery().discover();
    private final JComboBox<String> devices = new JComboBox<>();
    private final JTextField channels = new JTextField("1");
    private final JTextField mapping = new JTextField();
    private final JCheckBox synchronizedChannels =
        new JCheckBox("I verified synchronized channels on this device");
    private final JCheckBox calibration =
        new JCheckBox("This calibration applies to the selected device and channel mapping");

    private JPanel panel() {
      JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
      panel.add(
          new JLabel(
              "Selected paths and device identities remain local and are never exported in"
                  + " .audioexp."));
      for (AssetReference asset : preview.document().assets()) {
        panel.add(
            new JLabel(asset.id() + " — " + asset.relativePath() + " — " + asset.mediaType()));
        panel.add(new JLabel(asset.sizeBytes() + " bytes — SHA-256 " + asset.sha256()));
        JTextField selected = new JTextField(45);
        assets.put(asset.id(), selected);
        panel.add(pathSelector(dialog, selected, false));
        JCheckBox confirmed = new JCheckBox("Confirm media type if content cannot be recognized");
        types.put(asset.id(), confirmed);
        panel.add(confirmed);
      }
      panel.add(new JLabel("Local output directory (selected separately)"));
      panel.add(pathSelector(dialog, output, true));
      devices.addItem("No device — read-only inspection");
      discovered.forEach(device -> devices.addItem(device.name()));
      panel.add(new JLabel("Local device; required PCM support is checked without opening it"));
      panel.add(devices);
      if (preview.document().profiles().fields().get("capture")
          instanceof DocumentValue.ObjectValue capture) {
        int count =
            ((DocumentValue.NumberValue) capture.fields().get("channels")).value().intValueExact();
        channels.setText(Integer.toString(count));
        mapping.setText(
            java.util.stream.IntStream.range(0, count)
                .mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(",")));
      }
      panel.add(new JLabel("Physical capture channel count"));
      panel.add(channels);
      panel.add(new JLabel("Logical-to-physical channel mapping, zero based (for example 0,1)"));
      panel.add(mapping);
      panel.add(synchronizedChannels);
      panel.add(calibration);
      return panel;
    }

    private Selection selection() {
      Map<String, Path> selectedAssets = new TreeMap<>();
      Map<String, String> confirmedTypes = new TreeMap<>();
      assets.forEach(
          (id, field) -> {
            if (!field.getText().isBlank()) {
              selectedAssets.put(id, Path.of(field.getText()));
            }
          });
      preview
          .document()
          .assets()
          .forEach(
              asset -> {
                if (types.get(asset.id()).isSelected()) {
                  confirmedTypes.put(asset.id(), asset.mediaType());
                }
              });
      List<Integer> mapped = new ArrayList<>();
      if (!mapping.getText().isBlank()) {
        for (String index : mapping.getText().split(",")) {
          mapped.add(Integer.parseInt(index.trim()));
        }
      }
      String deviceId =
          devices.getSelectedIndex() <= 0
              ? ""
              : discovered.get(devices.getSelectedIndex() - 1).deviceId();
      CaptureSelection capture =
          new CaptureSelection(
              deviceId,
              Integer.parseInt(channels.getText()),
              mapped,
              synchronizedChannels.isSelected(),
              calibration.isSelected());
      return new Selection(
          selectedAssets,
          output.getText().isBlank() ? null : Path.of(output.getText()),
          confirmedTypes,
          capture);
    }
  }
}
