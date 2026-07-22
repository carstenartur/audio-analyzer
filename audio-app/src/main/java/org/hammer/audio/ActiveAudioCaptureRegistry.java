package org.hammer.audio;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-local registry for the capture service currently running in the desktop application.
 *
 * <p>This is an application-infrastructure bridge for host controls such as experiment recording;
 * it is not part of the audio-domain model and does not replace dependency injection inside views.
 */
public final class ActiveAudioCaptureRegistry {

  private static final AtomicReference<AudioCaptureService> ACTIVE = new AtomicReference<>();

  private ActiveAudioCaptureRegistry() {}

  /** Mark a service active after its producer has started successfully. */
  public static void activate(AudioCaptureService service) {
    ACTIVE.set(java.util.Objects.requireNonNull(service, "service"));
  }

  /** Remove a service only if it is still the active instance. */
  public static void deactivate(AudioCaptureService service) {
    ACTIVE.compareAndSet(service, null);
  }

  /** Return the currently running desktop capture service, if any. */
  public static Optional<AudioCaptureService> current() {
    AudioCaptureService service = ACTIVE.get();
    if (service == null || !service.isRunning()) {
      return Optional.empty();
    }
    return Optional.of(service);
  }
}
