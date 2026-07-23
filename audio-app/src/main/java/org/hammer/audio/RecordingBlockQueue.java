package org.hammer.audio;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.hammer.audio.core.AudioBlock;

/** Bounded queue abstraction that keeps recorder coordination independent of its implementation. */
final class RecordingBlockQueue {

  private final BlockingQueue<AudioBlock> blocks;
  private final int maximumSize;

  RecordingBlockQueue(int maximumSize) {
    this.blocks = new ArrayBlockingQueue<>(maximumSize);
    this.maximumSize = maximumSize;
  }

  boolean offer(AudioBlock block) {
    return blocks.offer(block);
  }

  AudioBlock poll(long timeout, TimeUnit unit) throws InterruptedException {
    return blocks.poll(timeout, unit);
  }

  boolean isEmpty() {
    return blocks.isEmpty();
  }

  int size() {
    return blocks.size();
  }

  int capacity() {
    return maximumSize;
  }
}
