package org.hammer.audio.acquisition;

import java.util.Objects;
import org.hammer.audio.core.AudioFormatDescriptor;

/**
 * Reusable capture settings bound to a discovered device.
 *
 * @param device device identity selected by the user
 * @param format normalized stream format expected from the device
 * @param signed whether integer source samples are signed
 * @param bigEndian whether multi-byte source samples use big-endian byte order
 */
public record CaptureDeviceConfiguration(
    CaptureDeviceDescriptor device,
    AudioFormatDescriptor format,
    boolean signed,
    boolean bigEndian) {

  /** Validate one capture-device configuration. */
  public CaptureDeviceConfiguration {
    Objects.requireNonNull(device, "device");
    Objects.requireNonNull(format, "format");
  }
}
