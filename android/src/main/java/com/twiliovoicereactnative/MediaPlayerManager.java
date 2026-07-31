package com.twiliovoicereactnative;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class MediaPlayerManager {
  public enum SoundTable {
    INCOMING,
    OUTGOING,
    DISCONNECT,
    RINGTONE
  }
  private final SoundPool soundPool;
  private final Map<SoundTable, Integer> soundMap;
  // Close patch: track every active stream id rather than only the
  // last one. The upstream single `activeStream` field is overwritten whenever
  // play() runs again before stop(), which orphans the previous (looping)
  // stream so it can never be stopped -- e.g. when a second incoming call
  // arrives while the first is still ringing. That leaves the ringtone playing
  // forever until the process is killed.
  private final Set<Integer> activeStreams;

  MediaPlayerManager(Context context) {
    soundPool = (new SoundPool.Builder())
      .setMaxStreams(2)
      .setAudioAttributes(
        new AudioAttributes.Builder()
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
          .build())
      .build();
    activeStreams = new HashSet<>();
    soundMap = new HashMap<>();
    soundMap.put(SoundTable.INCOMING, soundPool.load(context, R.raw.incoming, 1));
    soundMap.put(SoundTable.OUTGOING, soundPool.load(context, R.raw.outgoing, 1));
    soundMap.put(SoundTable.DISCONNECT, soundPool.load(context, R.raw.disconnect, 1));
    soundMap.put(SoundTable.RINGTONE, soundPool.load(context, R.raw.ringtone, 1));
  }

  public synchronized void play(final SoundTable sound) {
    // Close patch: stop any currently-playing stream before
    // starting a new one so a previous looping stream can never be orphaned.
    stop();
    int streamId = soundPool.play(
      soundMap.get(sound),
      1.f,
      1.f,
      1,
      (SoundTable.DISCONNECT== sound) ? 0 : -1,
      1.f);
    if (streamId != 0) {
      activeStreams.add(streamId);
    }
  }

  public synchronized void stop() {
    // Close patch: stop *all* tracked streams, not just the last.
    for (Integer streamId : activeStreams) {
      soundPool.stop(streamId);
    }
    activeStreams.clear();
  }

  @Override
  protected void finalize() throws Throwable {
    soundPool.release();
    super.finalize();
  }
}
