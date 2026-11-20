package org.hammer.audio;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import org.hammer.audio.acquisition.CaptureDeviceConfiguration;
import org.hammer.audio.acquisition.CaptureDeviceDescriptor;

/** Discovers JavaSound input mixers without leaking JavaSound types into the acquisition domain. */
public final class JavaSoundCaptureDeviceDiscovery {

  private final MixerCatalog catalog;

  /** Create discovery backed by the local JavaSound runtime. */
  public JavaSoundCaptureDeviceDiscovery() {
    this(new SystemMixerCatalog());
  }

  JavaSoundCaptureDeviceDiscovery(MixerCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  /** Return input-capable devices in deterministic descriptor order. */
  public List<CaptureDeviceDescriptor> discover() {
    List<CaptureDeviceDescriptor> devices = new ArrayList<>();
    for (Mixer.Info info : catalog.infos()) {
      if (catalog.inputCapable(info)) {
        devices.add(descriptor(info));
      }
    }
    devices.sort(Comparator.comparing(CaptureDeviceDescriptor::deviceId));
    return List.copyOf(devices);
  }

  /** Resolve one previously discovered device id to its JavaSound mixer identity. */
  public Optional<Mixer.Info> findMixerInfo(String deviceId) {
    if (deviceId == null || deviceId.isBlank()) {
      return Optional.empty();
    }
    for (Mixer.Info info : catalog.infos()) {
      if (deviceId.equals(descriptor(info).deviceId()) && catalog.inputCapable(info)) {
        return Optional.of(info);
      }
    }
    return Optional.empty();
  }

  /** Check the selected mixer's advertised PCM format without acquiring or opening its line. */
  public boolean supportsFormat(CaptureDeviceConfiguration configuration) {
    Optional<Mixer.Info> info = findMixerInfo(configuration.device().deviceId());
    if (info.isEmpty()) {
      return false;
    }
    org.hammer.audio.core.AudioFormatDescriptor descriptor = configuration.format();
    AudioFormat format =
        new AudioFormat(
            descriptor.sampleRate(),
            descriptor.sourceSampleSizeInBits(),
            descriptor.channels(),
            configuration.signed(),
            configuration.bigEndian());
    return AudioSystem.getMixer(info.get())
        .isLineSupported(new DataLine.Info(TargetDataLine.class, format));
  }

  private static CaptureDeviceDescriptor descriptor(Mixer.Info info) {
    return new CaptureDeviceDescriptor(
        stableId(info), info.getName(), info.getVendor(), info.getDescription(), info.getVersion());
  }

  private static String stableId(Mixer.Info info) {
    String identity =
        String.join(
            "\u0000", info.getName(), info.getVendor(), info.getDescription(), info.getVersion());
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(identity.getBytes(StandardCharsets.UTF_8));
    return "java-sound:" + encoded;
  }

  interface MixerCatalog {
    List<Mixer.Info> infos();

    boolean inputCapable(Mixer.Info info);
  }

  private static final class SystemMixerCatalog implements MixerCatalog {

    @Override
    public List<Mixer.Info> infos() {
      return List.of(AudioSystem.getMixerInfo());
    }

    @Override
    public boolean inputCapable(Mixer.Info info) {
      return AudioSystem.getMixer(info).getTargetLineInfo().length > 0;
    }
  }
}
