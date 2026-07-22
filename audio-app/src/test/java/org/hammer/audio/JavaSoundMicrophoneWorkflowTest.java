package org.hammer.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import org.hammer.audio.acquisition.CaptureDeviceConfiguration;
import org.hammer.audio.acquisition.CaptureDeviceDescriptor;
import org.hammer.audio.acquisition.ChannelTimingCalibration;
import org.hammer.audio.acquisition.LocalizationInputMode;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.MicrophoneArrayCalibration;
import org.hammer.audio.acquisition.MicrophoneArrayLayout;
import org.hammer.audio.acquisition.MicrophoneArrayProfile;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.geometry.Vector2;
import org.junit.jupiter.api.Test;

class JavaSoundMicrophoneWorkflowTest {

  private static final Instant OBSERVATION_TIME = Instant.parse("2026-07-22T07:00:00Z");

  @Test
  void discoversOnlyInputCapableMixersAndResolvesStableIdentity() {
    Mixer.Info inputInfo = new TestMixerInfo("Input", "Vendor", "Capture", "1");
    Mixer.Info outputInfo = new TestMixerInfo("Output", "Vendor", "Playback", "1");
    Mixer inputMixer = mock(Mixer.class);
    Mixer outputMixer = mock(Mixer.class);
    when(inputMixer.getTargetLineInfo())
        .thenReturn(new Line.Info[] {new Line.Info(TargetDataLine.class)});
    when(outputMixer.getTargetLineInfo()).thenReturn(new Line.Info[0]);
    JavaSoundCaptureDeviceDiscovery discovery =
        new JavaSoundCaptureDeviceDiscovery(
            new JavaSoundCaptureDeviceDiscovery.MixerCatalog() {
              @Override
              public List<Mixer.Info> infos() {
                return List.of(outputInfo, inputInfo);
              }

              @Override
              public Mixer mixer(Mixer.Info info) {
                return info == inputInfo ? inputMixer : outputMixer;
              }
            });

    List<CaptureDeviceDescriptor> devices = discovery.discover();

    assertEquals(1, devices.size());
    assertEquals("Input", devices.get(0).name());
    assertEquals(inputInfo, discovery.findMixerInfo(devices.get(0).deviceId()).orElseThrow());
  }

  @Test
  void readsCalibratedLivePcmThroughCommonMultichannelSource() throws Exception {
    TargetDataLine line = mock(TargetDataLine.class);
    AudioLineProvider provider = mock(AudioLineProvider.class);
    when(provider.acquireLine(any())).thenReturn(line);
    doAnswer(
            invocation -> {
              byte[] data = invocation.getArgument(0);
              int offset = invocation.getArgument(1);
              int length = invocation.getArgument(2);
              byte[] pcm = {0, 0, 0, -64, 0, 64, -1, 127};
              System.arraycopy(pcm, 0, data, offset, Math.min(length, pcm.length));
              return Math.min(length, pcm.length);
            })
        .when(line)
        .read(any(byte[].class), anyInt(), anyInt());
    JavaSoundMicrophoneArraySource source =
        new JavaSoundMicrophoneArraySource(profile(true), provider, 0.5, OBSERVATION_TIME);

    AudioBlock block = source.readBlock(2).orElseThrow();

    assertEquals(2, block.channels());
    assertEquals(2, block.frames());
    assertEquals(0.0, block.channelView(0)[0], 1.0e-6);
    assertEquals(0.5, block.channelView(0)[1], 0.01);
    assertEquals(-0.5, block.channelView(1)[0], 0.01);
    assertTrue(block.channelView(1)[1] > 0.99);
    source.close();
    verify(line).start();
    verify(line).close();
  }

  @Test
  void refusesLiveSourceWithoutCurrentCalibration() {
    AudioLineProvider provider = mock(AudioLineProvider.class);

    assertThrows(
        IllegalArgumentException.class,
        () -> new JavaSoundMicrophoneArraySource(profile(false), provider, 0.5, OBSERVATION_TIME));
  }

  private static MicrophoneArrayProfile profile(boolean calibrated) {
    MicrophoneArray array =
        new MicrophoneArray(
            List.of(
                new Microphone("left", new Vector2(-0.1, 0.0), 0),
                new Microphone("right", new Vector2(0.1, 0.0), 1)));
    CaptureDeviceConfiguration capture =
        new CaptureDeviceConfiguration(
            new CaptureDeviceDescriptor(
                "java-sound:test-device", "Test input", "Vendor", "Capture", "1"),
            new AudioFormatDescriptor(48_000.0f, 2, 16),
            true,
            false);
    MicrophoneArrayCalibration calibration =
        calibrated
            ? new MicrophoneArrayCalibration(
                "test-calibration",
                array,
                0,
                List.of(
                    new ChannelTimingCalibration(0, 0L, 0.0, 0.0, 0.05, 0.02, 1.0, false),
                    new ChannelTimingCalibration(1, 0L, 0.25, 0.0, 0.05, 0.02, 1.0, false)),
                OBSERVATION_TIME.minusSeconds(60L),
                OBSERVATION_TIME.plusSeconds(60L))
            : null;
    return new MicrophoneArrayProfile(
        "test-array",
        "Test array",
        MicrophoneArrayLayout.STEREO_PAIR,
        array,
        EnumSet.allOf(LocalizationInputMode.class),
        capture,
        calibration);
  }

  private static final class TestMixerInfo extends Mixer.Info {
    private TestMixerInfo(String name, String vendor, String description, String version) {
      super(name, vendor, description, version);
    }
  }
}
