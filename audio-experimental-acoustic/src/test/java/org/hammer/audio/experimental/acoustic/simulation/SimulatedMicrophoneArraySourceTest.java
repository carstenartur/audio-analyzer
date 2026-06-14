package org.hammer.audio.experimental.acoustic.simulation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.geometry.Vector2;
import org.junit.jupiter.api.Test;

class SimulatedMicrophoneArraySourceTest {

  @Test
  void producesTimestampedMultichannelBlocks() {
    MicrophoneArray array = array();
    SimulatedMicrophoneArraySource source = source(array, 1L, movingEmitter(), room(0.0, 0.0));

    Optional<AudioBlock> block = source.readBlock(128);
    Optional<AudioBlock> nextBlock = source.readBlock(64);

    assertTrue(block.isPresent());
    assertEquals(2, block.orElseThrow().channels());
    assertEquals(128, block.orElseThrow().frames());
    assertEquals(0L, block.orElseThrow().timestampNanos());
    assertEquals(128L, nextBlock.orElseThrow().frameIndex());
    assertEquals(16_000_000L, nextBlock.orElseThrow().timestampNanos());
  }

  @Test
  void replaysDeterministicallyWithFixedSeed() {
    SimulatedMicrophoneArraySource first = source(array(), 99L, movingEmitter(), room(0.2, 0.01));
    SimulatedMicrophoneArraySource second = source(array(), 99L, movingEmitter(), room(0.2, 0.01));

    AudioBlock firstBlock = first.readBlock(256).orElseThrow();
    AudioBlock secondBlock = second.readBlock(256).orElseThrow();

    assertSamplesEqual(firstBlock, secondBlock);
  }

  @Test
  void movingEmitterChangesGeneratedSignalOverTime() {
    SimulatedMicrophoneArraySource source = source(array(), 1L, movingEmitter(), room(0.0, 0.0));

    AudioBlock first = source.readBlock(128).orElseThrow();
    AudioBlock second = source.readBlock(128).orElseThrow();

    assertNotEquals(channelSignature(first, 0), channelSignature(second, 0));
  }

  @Test
  void movingEmitterProducesDopplerShiftTowardMicrophone() {
    SoundEmitter2D emitter =
        new SoundEmitter2D(new Vector2(1.0, 0.0), new Vector2(-1.0, 0.0), 686.0, 0.1);

    double observed =
        SimulatedMicrophoneArraySource.observedFrequencyAt(emitter, new Vector2(0.0, 0.0), 0.0);

    assertTrue(observed > emitter.frequencyHz());
    assertEquals(688.0, observed, 0.01);
  }

  @Test
  void multipleEmittersContributeDifferentSignalThanSingleEmitter() {
    MicrophoneArray array = array();
    SimulatedMicrophoneArraySource single = source(array, 1L, movingEmitter(), room(0.0, 0.0));
    SimulatedMicrophoneArraySource multiple =
        source(
            array,
            1L,
            movingEmitter(),
            new SoundEmitter2D(new Vector2(1.2, 0.8), Vector2.ZERO, 660.0, 0.08),
            room(0.0, 0.0));

    AudioBlock singleBlock = single.readBlock(256).orElseThrow();
    AudioBlock multipleBlock = multiple.readBlock(256).orElseThrow();

    assertNotEquals(channelSignature(singleBlock, 0), channelSignature(multipleBlock, 0));
  }

  @Test
  void reflectionAndNoiseConfigurationAffectsSignal() {
    MicrophoneArray array = array();
    SimulatedMicrophoneArraySource dry = source(array, 7L, movingEmitter(), room(0.0, 0.0));
    SimulatedMicrophoneArraySource reflectedNoisy =
        source(array, 7L, movingEmitter(), room(0.4, 0.02));

    AudioBlock dryBlock = dry.readBlock(256).orElseThrow();
    AudioBlock reflectedNoisyBlock = reflectedNoisy.readBlock(256).orElseThrow();

    assertNotEquals(channelSignature(dryBlock, 1), channelSignature(reflectedNoisyBlock, 1));
  }

  @Test
  void rejectsNullRoomAndArrayAtConstruction() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SimulatedMicrophoneArraySource(
                null, array(), List.of(movingEmitter()), 8_000.0f, 0.2, 1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SimulatedMicrophoneArraySource(
                room(0.0, 0.0), null, List.of(movingEmitter()), 8_000.0f, 0.2, 1L));
  }

  private static SimulatedMicrophoneArraySource source(
      MicrophoneArray array, long seed, AcousticEmitter2D emitter, Room2D room) {
    return source(array, seed, emitter, null, room);
  }

  private static SimulatedMicrophoneArraySource source(
      MicrophoneArray array,
      long seed,
      AcousticEmitter2D firstEmitter,
      AcousticEmitter2D secondEmitter,
      Room2D room) {
    List<AcousticEmitter2D> emitters =
        secondEmitter == null ? List.of(firstEmitter) : List.of(firstEmitter, secondEmitter);
    return new SimulatedMicrophoneArraySource(room, array, emitters, 8_000.0f, 0.2, seed);
  }

  private static MicrophoneArray array() {
    return new MicrophoneArray(
        List.of(
            new Microphone("a", new Vector2(0.0, 0.0), 0),
            new Microphone("b", new Vector2(0.2, 0.0), 1)));
  }

  private static SoundEmitter2D movingEmitter() {
    return new SoundEmitter2D(new Vector2(1.0, 1.0), new Vector2(0.2, -0.1), 440.0, 0.1);
  }

  private static Room2D room(double reflectionGain, double noiseAmplitude) {
    return new Room2D(2.0, 2.0, reflectionGain, noiseAmplitude);
  }

  private static void assertSamplesEqual(AudioBlock first, AudioBlock second) {
    assertEquals(first.channels(), second.channels());
    assertEquals(first.frames(), second.frames());
    for (int channel = 0; channel < first.channels(); channel++) {
      assertArrayEquals(first.channelView(channel), second.channelView(channel));
    }
  }

  private static int channelSignature(AudioBlock block, int channel) {
    return Arrays.hashCode(block.channelView(channel));
  }
}
