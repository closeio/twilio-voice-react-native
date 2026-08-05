package com.twiliovoicereactnative;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class MediaPlayerManager {
  private static final SDKLog logger = new SDKLog(MediaPlayerManager.class);
  public enum SoundTable {
    INCOMING,
    OUTGOING,
    DISCONNECT,
    RINGTONE
  }
  // Close patch: how the incoming ring is currently being presented.
  // FULL is the ringtone plus call-cadence vibration; NUDGE is the quiet
  // vibration used for a call arriving while another is in progress.
  public enum RingMode { FULL, NUDGE }
  private final Context context;
  private final SoundPool soundPool;
  private final Map<SoundTable, Integer> soundMap;
  // Close patch: track every active stream id rather than only the
  // last one. The upstream single `activeStream` field is overwritten whenever
  // play() runs again before stop(), which orphans the previous (looping)
  // stream so it can never be stopped -- e.g. when a second incoming call
  // arrives while the first is still ringing. That leaves the ringtone playing
  // forever until the process is killed.
  private final Set<Integer> activeStreams;
  // Close patch: the incoming ring is played on a dedicated
  // MediaPlayer -- NOT the SoundPool -- so it can use the device's *default
  // ringtone* on the ring audio stream (USAGE_NOTIFICATION_RINGTONE ->
  // STREAM_RING). That makes the ring honor the user's chosen ringtone and
  // ringer volume (silent / vibrate => 0 volume => inaudible), instead of the
  // bundled R.raw.incoming sample played at fixed full volume on the voice-call
  // stream (USAGE_VOICE_COMMUNICATION).
  private MediaPlayer incomingPlayer;
  // Close patch: the fallback bundled-ring stream, tracked separately
  // from activeStreams so stopRinging() can end the ring without touching an
  // unrelated sound (an outgoing ringback, a disconnect tone).
  private Integer incomingStreamId = null;
  // Close patch: what the ringer is doing and for which call.
  // VoiceService.syncRinger() derives the desired state from the call database
  // and calls startRinging()/stopRinging(); this bookkeeping is what makes those
  // idempotent, so re-posting a notification (a caller-name refresh,
  // refreshRingingIncomingCallNotifications) never restarts the ringtone from
  // the beginning.
  private RingMode ringMode = null;
  private UUID ringingFor = null;
  // Close patch: drive a continuous, repeating vibration for the
  // duration of an incoming call ring. Android only vibrates a notification
  // once (when it posts), so a proper "ringing" buzz has to be driven manually
  // and cancelled the moment ringing ends. It shares the INCOMING play()/stop()
  // lifecycle so every place that stops the ring also stops the vibration.
  private Vibrator incomingVibrator;
  // wait 0ms, buzz 1000ms, pause 1000ms -- repeats until cancelled.
  private static final long[] INCOMING_VIBRATION_PATTERN = {0L, 1000L, 1000L};
  // Close patch: gentler repeating pattern for a second call arriving
  // while already on a call -- short buzz, long gap (0ms wait, 600ms buzz, 2000ms
  // pause). Continuous so it isn't missed mid-conversation, but less aggressive
  // than the full incoming ring above.
  private static final long[] SECOND_CALL_VIBRATION_PATTERN = {0L, 600L, 2000L};

  MediaPlayerManager(Context context) {
    this.context = context.getApplicationContext();
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

  // Close patch: bring the ringer to `mode` for `uuid`. A no-op when
  // it is already ringing that way, so the caller can reconcile as often as it
  // likes -- see VoiceService.syncRinger(), the only intended caller.
  public synchronized void startRinging(@NonNull final UUID uuid, @NonNull final RingMode mode) {
    if (mode == ringMode && uuid.equals(ringingFor)) {
      return;
    }
    stopRinging();
    if (RingMode.FULL == mode) {
      play(SoundTable.INCOMING);
    } else {
      startSecondCallVibration();
    }
    // Assigned after play(), which calls stop() -> stopRinging() and would
    // otherwise clear what we just recorded.
    ringMode = mode;
    ringingFor = uuid;
  }

  // Close patch: end the incoming ring and nothing else. Unlike
  // stop(), this leaves other playback (an outgoing ringback, a disconnect
  // tone) alone.
  public synchronized void stopRinging() {
    stopIncomingVibration();
    if (null != incomingPlayer) {
      try {
        incomingPlayer.stop();
      } catch (IllegalStateException ignored) {
        // player was never started / already stopped -- nothing to do
      }
      incomingPlayer.release();
      incomingPlayer = null;
    }
    if (null != incomingStreamId) {
      soundPool.stop(incomingStreamId);
      activeStreams.remove(incomingStreamId);
      incomingStreamId = null;
    }
    ringMode = null;
    ringingFor = null;
  }

  public synchronized void play(final SoundTable sound) {
    // Close patch: stop any currently-playing stream before
    // starting a new one so a previous looping stream can never be orphaned.
    stop();
    // Close patch: route the incoming ring through the device's
    // default ringtone on the ring stream instead of the bundled SoundPool
    // sample.
    if (SoundTable.INCOMING == sound) {
      playIncomingRingtone();
      return;
    }
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

  // Close patch: play the system default ringtone, looping, on the
  // ring stream so it follows the user's ringtone choice and ringer volume.
  // Falls back to the bundled R.raw.incoming sample if the device exposes no
  // default ringtone or the MediaPlayer fails to start.
  private void playIncomingRingtone() {
    // Vibrate for the whole ring, independent of whether a ringtone sound plays
    // (e.g. Vibrate mode has no sound but should still buzz).
    startIncomingVibration();

    Uri ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(
      context, RingtoneManager.TYPE_RINGTONE);
    if (null == ringtoneUri) {
      // Some devices/profiles have no ringtone set (e.g. "None"); fall back to
      // the bundled sound rather than a system sound that isn't a ringtone.
      logger.warning("No default ringtone available, falling back to bundled sound");
      playBundledIncoming();
      return;
    }
    // Close patch: declare the player outside the try so the catch
    // can release it. If setDataSource()/prepare() throws (e.g. a stale/deleted
    // ringtone URI), the local player would otherwise leak -- guarding the
    // incomingPlayer field instead is dead code, since play() calls stop()
    // (nulling incomingPlayer) immediately before this runs.
    MediaPlayer player = null;
    try {
      player = new MediaPlayer();
      player.setAudioAttributes(
        new AudioAttributes.Builder()
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
          .build());
      player.setDataSource(context, ringtoneUri);
      player.setLooping(true);
      player.prepare();
      player.start();
      incomingPlayer = player;
    } catch (Exception e) {
      logger.warning(e, "Failed to play default ringtone, falling back to bundled sound");
      if (null != player) {
        player.release();
      }
      incomingPlayer = null;
      playBundledIncoming();
    }
  }

  private void playBundledIncoming() {
    int streamId = soundPool.play(soundMap.get(SoundTable.INCOMING), 1.f, 1.f, 1, -1, 1.f);
    if (streamId != 0) {
      activeStreams.add(streamId);
      // Close patch: remember it as the ring stream so stopRinging()
      // can end just this one.
      incomingStreamId = streamId;
    }
  }

  // Close patch: repeating call-style vibration for a normal incoming
  // call ring.
  private void startIncomingVibration() {
    startRepeatingVibration(INCOMING_VIBRATION_PATTERN);
  }

  // Close patch: continuous (but gentler) vibration for a second
  // call arriving while already on a call -- noticeable so it isn't missed
  // mid-conversation, less aggressive than the full incoming ring. Shares the
  // incomingVibrator/stop() lifecycle, so it's cancelled the moment the second
  // call is answered/declined/cancelled/times out.
  private void startSecondCallVibration() {
    stopIncomingVibration();
    startRepeatingVibration(SECOND_CALL_VIBRATION_PATTERN);
  }

  // Loops `pattern` (repeat index 0) until stopIncomingVibration() cancels it.
  // Skipped in silent/mute mode. Vibration is unaffected by the active call's
  // audio mode, so it fires reliably mid-call.
  private void startRepeatingVibration(final long[] pattern) {
    AudioManager audioManager =
      (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    if (null != audioManager
        && AudioManager.RINGER_MODE_SILENT == audioManager.getRingerMode()) {
      return;
    }
    Vibrator vibrator = resolveVibrator();
    if (null == vibrator || !vibrator.hasVibrator()) {
      logger.warning("startRepeatingVibration: no vibrator available");
      return;
    }
    // Close patch: tag the vibration with RINGTONE usage. Without a
    // usage, Android 13+/Samsung does not treat it as a call vibration and
    // cancels the *repeating* waveform after the first cycle, so it buzzes once
    // instead of continuously. VibrationAttributes (API 33+) or
    // AudioAttributes(USAGE_NOTIFICATION_RINGTONE) mark it as a ringtone buzz.
    final AudioAttributes ringtoneAudioAttributes =
      new AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .build();
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        vibrator.vibrate(
          VibrationEffect.createWaveform(pattern, 0),
          new VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_RINGTONE)
            .build());
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(
          VibrationEffect.createWaveform(pattern, 0),
          ringtoneAudioAttributes);
      } else {
        // API 24-25: VibrationEffect doesn't exist; use the legacy pattern
        // overload, which still accepts AudioAttributes for the usage hint.
        vibrator.vibrate(pattern, 0, ringtoneAudioAttributes);
      }
      incomingVibrator = vibrator;
    } catch (Exception e) {
      logger.warning(e, "Failed to start vibration");
      incomingVibrator = null;
    }
  }

  private void stopIncomingVibration() {
    if (null != incomingVibrator) {
      try {
        incomingVibrator.cancel();
      } catch (Exception ignored) {
        // best-effort cancel
      }
      incomingVibrator = null;
    }
  }

  private Vibrator resolveVibrator() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      VibratorManager vibratorManager =
        (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
      return (null != vibratorManager) ? vibratorManager.getDefaultVibrator() : null;
    }
    return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
  }

  public synchronized void stop() {
    // Close patch: the ring (vibration, ringtone player, fallback
    // stream) plus every other tracked stream.
    stopRinging();
    // Close patch: stop *all* tracked streams, not just the last.
    for (Integer streamId : activeStreams) {
      soundPool.stop(streamId);
    }
    activeStreams.clear();
  }

  @Override
  protected void finalize() throws Throwable {
    stopIncomingVibration();
    if (null != incomingPlayer) {
      incomingPlayer.release();
      incomingPlayer = null;
    }
    soundPool.release();
    super.finalize();
  }
}
