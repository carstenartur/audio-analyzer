package org.hammer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.DefaultTableModel;
import org.hammer.audio.acquisition.CaptureDeviceConfiguration;
import org.hammer.audio.acquisition.CaptureDeviceDescriptor;
import org.hammer.audio.acquisition.ChannelTimingCalibration;
import org.hammer.audio.acquisition.LocalizationInputMode;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.MicrophoneArrayCalibration;
import org.hammer.audio.acquisition.MicrophoneArrayLayout;
import org.hammer.audio.acquisition.MicrophoneArrayProfile;
import org.hammer.audio.acquisition.MicrophoneArrayProfileCodec;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.geometry.Vector2;

/** Form editor for measured geometry/calibration; it never fabricates hardware measurements. */
// A single form binds geometry, capture format and calibration to their existing domain values.
@SuppressWarnings("PMD.CouplingBetweenObjects")
final class MicrophoneProfileEditorPanel extends JPanel {

  private static final long serialVersionUID = 1L;
  private final JTextField identity;
  private final JTextField name;
  private final JComboBox<MicrophoneArrayLayout> layout =
      new JComboBox<>(MicrophoneArrayLayout.values());
  private final JComboBox<String> devices = new JComboBox<>();
  private final JSpinner sampleRate;
  private final JComboBox<Integer> sampleBits = new JComboBox<>(new Integer[] {16, 24, 32});
  private final JCheckBox signed = new JCheckBox("Signed PCM", true);
  private final JCheckBox bigEndian = new JCheckBox("Big endian");
  private final JCheckBox calibrated = new JCheckBox("Use supplied measured calibration");
  private final JTextField calibrationId = new JTextField(18);
  private final JTextField measuredAt = new JTextField(24);
  private final JTextField validUntil = new JTextField(24);
  private final transient List<CaptureDeviceDescriptor> availableDevices;
  private final DefaultTableModel channels;
  private final JTable table;

  MicrophoneProfileEditorPanel(String definition, List<CaptureDeviceDescriptor> availableDevices) {
    super(new BorderLayout(8, 8));
    this.availableDevices = List.copyOf(availableDevices);
    MicrophoneArrayProfile profile = new MicrophoneArrayProfileCodec().decode(definition);
    identity = new JTextField(profile.profileId(), 20);
    name = new JTextField(profile.displayName(), 24);
    layout.setSelectedItem(profile.layout());
    devices.addItem("Simulation/replay only — no live device");
    availableDevices.forEach(
        device -> devices.addItem(device.name() + " [" + device.deviceId() + "]"));
    CaptureDeviceConfiguration capture = profile.liveCapture();
    sampleRate =
        new JSpinner(
            new SpinnerNumberModel(
                capture == null ? 48000 : (int) capture.format().sampleRate(), 1, 384000, 1000));
    if (capture != null) {
      sampleBits.setSelectedItem(capture.format().sourceSampleSizeInBits());
      signed.setSelected(capture.signed());
      bigEndian.setSelected(capture.bigEndian());
      for (int index = 0; index < availableDevices.size(); index++) {
        if (availableDevices.get(index).deviceId().equals(capture.device().deviceId())) {
          devices.setSelectedIndex(index + 1);
        }
      }
    }
    JPanel fields = new JPanel(new GridLayout(0, 2, 8, 5));
    field(fields, "Profile identity", identity);
    field(fields, "Profile name", name);
    field(fields, "Layout", layout);
    JSpinner count = new JSpinner(new SpinnerNumberModel(profile.array().channels(), 2, 64, 1));
    field(fields, "Microphone channels", count);
    field(fields, "Host capture device", devices);
    field(fields, "Sample rate (Hz)", sampleRate);
    field(fields, "PCM bits", sampleBits);
    fields.add(signed);
    fields.add(bigEndian);
    field(fields, "Calibration identity", calibrationId);
    field(fields, "Measured at (ISO UTC)", measuredAt);
    field(fields, "Valid until (ISO UTC)", validUntil);
    fields.add(calibrated);
    add(fields, BorderLayout.NORTH);
    channels =
        new DefaultTableModel(
            new String[] {
              "Microphone",
              "X m",
              "Y m",
              "Reference frame",
              "Offset samples",
              "Drift ppm",
              "Residual RMS",
              "Jitter RMS",
              "Gain",
              "Inverted"
            },
            0);
    for (Microphone microphone : profile.array().microphones()) {
      ChannelTimingCalibration calibration =
          profile.calibration() == null
              ? null
              : profile.calibration().channel(microphone.channel());
      addChannel(microphone, calibration);
    }
    if (profile.calibration() != null) {
      calibrated.setSelected(true);
      calibrationId.setText(profile.calibration().profileId());
      measuredAt.setText(profile.calibration().calibratedAt().toString());
      validUntil.setText(profile.calibration().validUntil().toString());
    }
    channels.addTableModelListener(
        event -> {
          if (event.getColumn() <= 2) {
            calibrated.setSelected(false);
          }
        });
    devices.addActionListener(event -> calibrated.setSelected(false));
    sampleRate.addChangeListener(event -> calibrated.setSelected(false));
    sampleBits.addActionListener(event -> calibrated.setSelected(false));
    table = new JTable(channels);
    count.addChangeListener(
        event -> {
          int requested = ((Number) count.getValue()).intValue();
          while (channels.getRowCount() < requested) {
            channels.addRow(
                new Object[] {
                  "mic-" + channels.getRowCount(), "", "", 0L, "", "", "", "", "", false
                });
          }
          channels.setRowCount(requested);
          calibrated.setSelected(false);
          layout.setSelectedItem(MicrophoneArrayLayout.CUSTOM);
        });
    table
        .getAccessibleContext()
        .setAccessibleName("Channel order, measured positions and timing measurements");
    JScrollPane scroll = new JScrollPane(table);
    scroll.setPreferredSize(new Dimension(1000, 190));
    add(scroll, BorderLayout.CENTER);
    JPanel guidance = new JPanel(new GridLayout(0, 1));
    guidance.add(
        new JLabel(
            "Rows map to channels 0…N−1. Channel 0 is the timing reference and needs zero offset"
                + " and drift."));
    guidance.add(
        new JLabel(
            "Geometry/device edits clear calibration. Enter measured values before explicitly"
                + " enabling calibration again."));
    guidance.add(
        new JLabel(
            "No device available? Keep simulation/replay; live acquisition remains blocked until"
                + " hardware is bound."));
    add(guidance, BorderLayout.SOUTH);
  }

  private void addChannel(Microphone microphone, ChannelTimingCalibration measurement) {
    if (measurement == null) {
      channels.addRow(
          new Object[] {
            microphone.id(),
            microphone.positionMeters().x(),
            microphone.positionMeters().y(),
            0L,
            "",
            "",
            "",
            "",
            "",
            false
          });
      return;
    }
    channels.addRow(
        new Object[] {
          microphone.id(),
          microphone.positionMeters().x(),
          microphone.positionMeters().y(),
          measurement.referenceFrame(),
          measurement.offsetSamples(),
          measurement.driftPpm(),
          measurement.residualRmsSamples(),
          measurement.jitterRmsSamples(),
          measurement.gainLinear(),
          measurement.invertedPolarity()
        });
  }

  String definition() {
    if (table.isEditing()) {
      table.getCellEditor().stopCellEditing();
    }
    List<Microphone> microphones = new ArrayList<>();
    List<ChannelTimingCalibration> calibration = new ArrayList<>();
    for (int row = 0; row < channels.getRowCount(); row++) {
      microphones.add(
          new Microphone(text(row, 0), new Vector2(number(row, 1), number(row, 2)), row));
      if (calibrated.isSelected()) {
        String polarity = text(row, 9);
        if (!"true".equals(polarity) && !"false".equals(polarity)) {
          throw new IllegalArgumentException("Inverted polarity must be true or false.");
        }
        calibration.add(
            new ChannelTimingCalibration(
                row,
                Long.parseLong(text(row, 3)),
                number(row, 4),
                number(row, 5),
                number(row, 6),
                number(row, 7),
                number(row, 8),
                Boolean.parseBoolean(polarity)));
      }
    }
    MicrophoneArray array = new MicrophoneArray(microphones);
    CaptureDeviceConfiguration capture =
        devices.getSelectedIndex() == 0
            ? null
            : new CaptureDeviceConfiguration(
                availableDevices.get(devices.getSelectedIndex() - 1),
                new AudioFormatDescriptor(
                    ((Number) sampleRate.getValue()).floatValue(),
                    array.channels(),
                    (Integer) sampleBits.getSelectedItem()),
                signed.isSelected(),
                bigEndian.isSelected());
    MicrophoneArrayCalibration measurement =
        calibrated.isSelected()
            ? new MicrophoneArrayCalibration(
                calibrationId.getText(),
                array,
                0,
                calibration,
                Instant.parse(measuredAt.getText()),
                Instant.parse(validUntil.getText()))
            : null;
    return new MicrophoneArrayProfileCodec()
        .encode(
            new MicrophoneArrayProfile(
                identity.getText(),
                name.getText(),
                (MicrophoneArrayLayout) layout.getSelectedItem(),
                array,
                capture == null
                    ? Set.of(LocalizationInputMode.SIMULATION, LocalizationInputMode.REPLAY)
                    : Set.of(
                        LocalizationInputMode.SIMULATION,
                        LocalizationInputMode.REPLAY,
                        LocalizationInputMode.LIVE),
                capture,
                measurement));
  }

  private String text(int row, int column) {
    return String.valueOf(channels.getValueAt(row, column)).trim();
  }

  private double number(int row, int column) {
    return Double.parseDouble(text(row, column));
  }

  private static void field(JPanel panel, String name, javax.swing.JComponent component) {
    JLabel label = new JLabel(name);
    label.setLabelFor(component);
    panel.add(label);
    panel.add(component);
  }
}
