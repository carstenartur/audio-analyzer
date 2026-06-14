package org.hammer.audio.experimental.acoustic.scenario;

import java.util.Objects;

/**
 * Environmental context for a ground-truth scenario.
 *
 * <p>Captures acoustic propagation parameters that apply to the whole scenario. The {@link
 * #DEFAULT} constant represents standard air at 20 °C.
 */
public record ScenarioEnvironment(double speedOfSoundMetersPerSecond, String description) {

  /** Standard air at 20 °C (343 m/s). */
  public static final ScenarioEnvironment DEFAULT =
      new ScenarioEnvironment(343.0, "Standard air at 20 \u00B0C");

  /** Validate parameters. */
  public ScenarioEnvironment {
    if (!(speedOfSoundMetersPerSecond > 0.0) || !Double.isFinite(speedOfSoundMetersPerSecond)) {
      throw new IllegalArgumentException("speedOfSoundMetersPerSecond must be finite and > 0");
    }
    Objects.requireNonNull(description, "description");
  }
}
