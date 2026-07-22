package org.hammer.audio;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
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
      Mixer mixer = catalog.mixer(info);
      if (mixer.getTargetLineInfo().length > 0) {
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
      if (deviceId.equals(descriptor(info).deviceId())
          && catalog.mixer(info).getTargetLineInfo().length > 0) {
        return Optional.of(info);
      }
    }
    return Optional.empty();
  }

  private static CaptureDeviceDescriptor descriptor(Mixer.Info info) {
    return new CaptureDeviceDescriptor(
        stableId(info),
        info.getName(),
        info.getVendor(),
        info.getDescription(),
        info.getVersion());
  }

  private static String stableId(Mixer.Info info) {
    String identity =
        String.join(
            "\u0000",
            info.getName(),
            info.getVendor(),
            info.getDescription(),
            info.getVersion());
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(identity.getBytes(StandardCharsets.UTF_8));
    return "java-sound:" + encoded;
  }

  interface MixerCatalog {
    List<Mixer.Info> infos();

    Mixer mixer(Mixer.Info info);
  }

  private static final class SystemMixerCatalog implements MixerCatalog {

    @Override
    public List<Mixer.Info> infos() {
      return List.of(AudioSystem.getMixerInfo());
    }

    @Override
    public Mixer mixer(Mixer.Info info) {
      return AudioSystem.getMixer(info);
    }
  }
}
