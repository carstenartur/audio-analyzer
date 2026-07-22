package org.hammer.audio.acquisition;

/**
 * Stable, API-neutral description of one discoverable capture device.
 *
 * @param deviceId deterministic provider-specific identity
 * @param name human-readable device name
 * @param vendor provider or hardware vendor
 * @param description longer provider description
 * @param version provider or driver version
 */
public record CaptureDeviceDescriptor(
    String deviceId, String name, String vendor, String description, String version) {

  // Validate one discovered capture-device descriptor.
  public CaptureDeviceDescriptor {
    requireText(deviceId, "deviceId");
    requireText(name, "name");
    vendor = normalize(vendor);
    description = normalize(description);
    version = normalize(version);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
