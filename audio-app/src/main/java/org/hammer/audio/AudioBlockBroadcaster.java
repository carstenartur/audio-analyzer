package org.hammer.audio;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.hammer.audio.core.AudioBlock;

/** Package-private fan-out used by capture-service implementations. */
final class AudioBlockBroadcaster {

  private static final Logger LOGGER = Logger.getLogger(AudioBlockBroadcaster.class.getName());

  private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();

  AudioBlockSubscription subscribe(AudioBlockListener listener) {
    Subscription subscription = new Subscription(Objects.requireNonNull(listener, "listener"));
    subscriptions.add(subscription);
    return subscription;
  }

  void publish(AudioBlock block) {
    Objects.requireNonNull(block, "block");
    for (Subscription subscription : subscriptions) {
      subscription.publish(block);
    }
  }

  private final class Subscription implements AudioBlockSubscription {

    private final AudioBlockListener listener;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private Subscription(AudioBlockListener listener) {
      this.listener = listener;
    }

    private void publish(AudioBlock block) {
      if (closed.get()) {
        return;
      }
      try {
        listener.onAudioBlock(block);
      } catch (RuntimeException exception) {
        LOGGER.log(Level.WARNING, "Audio-block listener failed; removing subscription", exception);
        close();
      }
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        subscriptions.remove(this);
      }
    }

    @Override
    public boolean isClosed() {
      return closed.get();
    }
  }
}
