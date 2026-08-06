package com.twiliovoicereactnative;

import android.content.Context;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.WritableMap;
import com.twilio.voice.Call;
import com.twilio.voice.CallException;

import static com.twiliovoicereactnative.CommonConstants.CallEventConnected;
import static com.twiliovoicereactnative.CommonConstants.CallEventDisconnected;
import static com.twiliovoicereactnative.CommonConstants.CallEventReconnected;
import static com.twiliovoicereactnative.CommonConstants.CallEventReconnecting;
import static com.twiliovoicereactnative.CommonConstants.CallEventRinging;
import static com.twiliovoicereactnative.CommonConstants.ScopeCall;
import static com.twiliovoicereactnative.CommonConstants.VoiceEventType;
import static com.twiliovoicereactnative.CommonConstants.VoiceErrorKeyError;
import static com.twiliovoicereactnative.CommonConstants.CallEventCurrentWarnings;
import static com.twiliovoicereactnative.CommonConstants.CallEventPreviousWarnings;
import static com.twiliovoicereactnative.CommonConstants.CallEventConnectFailure;
import static com.twiliovoicereactnative.CommonConstants.CallEventQualityWarningsChanged;
import static com.twiliovoicereactnative.Constants.JS_EVENT_KEY_CALL_INFO;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getCallRecordDatabase;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getJSEventEmitter;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getAudioSwitchManager;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getMediaPlayerManager;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getVoiceServiceApi;
import static com.twiliovoicereactnative.JSEventEmitter.constructJSMap;
import static com.twiliovoicereactnative.ReactNativeArgumentsSerializer.*;

import com.twiliovoicereactnative.CallRecordDatabase.CallRecord;

import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

class CallListenerProxy implements Call.Listener {
  private static final SDKLog logger = new SDKLog(CallListenerProxy.class);
  private final UUID uuid;
  private final Context context;

  public CallListenerProxy(UUID uuid, Context context) {
    this.uuid = uuid;
    this.context = context;
  }

  @Override
  public void onConnectFailure(@NonNull Call call, @NonNull CallException callException) {
    debug("onConnectFailure");

    // find call record & remove
    CallRecord callRecord = Objects.requireNonNull(getCallRecordDatabase().remove(new CallRecord(uuid)));

    // stop sound and routing
    // Close patch: below the record removal so the guard sees only the remaining
    // calls, and gated -- a failed connect must not deactivate the shared audio
    // session under a still-live call. Same guard as rejectCall/onDisconnected.
    getMediaPlayerManager().stop();
    if (!getCallRecordDatabase().hasActiveCall()) {
      getAudioSwitchManager().getAudioSwitch().deactivate();
    }

    // take down notification (which reconciles the ringer after its own stop())
    getVoiceServiceApi().cancelActiveCallNotification(callRecord);

    // serialize and notify JS
    sendJSEvent(
      constructJSMap(
        new Pair<>(VoiceEventType, CallEventConnectFailure),
        new Pair<>(JS_EVENT_KEY_CALL_INFO, serializeCall(callRecord)),
        new Pair<>(VoiceErrorKeyError, serializeVoiceException(callException))));
  }

  @Override
  public void onRinging(@NonNull Call call) {
    debug("onRinging");

    // find call record
    CallRecord callRecord = Objects.requireNonNull(getCallRecordDatabase().get(new CallRecord(uuid)));
    callRecord.setCall(call);

    // create notification & sound
    callRecord.setNotificationId(NotificationUtility.createNotificationIdentifier());
    getAudioSwitchManager().getAudioSwitch().activate();
    // Close patch: play() stops every tracked sound, so this ringback cancels the
    // nudge of an incoming call that arrived during dial-out (the outgoing record
    // holds a live Call from connect()). Reconcile after, as onConnected does, or
    // that call waits out the ring timeout in silence.
    getMediaPlayerManager().play(MediaPlayerManager.SoundTable.RINGTONE);
    getVoiceServiceApi().syncRinger();
    getVoiceServiceApi().raiseOutgoingCallNotification(callRecord);

    // notify JS layer
    sendJSEvent(
      constructJSMap(
        new Pair<>(VoiceEventType, CallEventRinging),
        new Pair<>(JS_EVENT_KEY_CALL_INFO, serializeCall(callRecord))));
  }

  @Override
  public void onConnected(@NonNull Call call) {
    debug("onConnected");

    // find call record
    CallRecord callRecord = Objects.requireNonNull(getCallRecordDatabase().get(new CallRecord(uuid)));
    callRecord.setCall(call);
    callRecord.setTimestamp(new Date());
    // Close patch: this stop() ends this call's ringback, but it is global --
    // answer one of two ringing calls and it cancels the ring syncRinger() just
    // started for the other, a few hundred ms after the accept. Reconcile after.
    getMediaPlayerManager().stop();
    getVoiceServiceApi().syncRinger();

    // notify JS layer
    sendJSEvent(
      constructJSMap(
        new Pair<>(VoiceEventType, CallEventConnected),
        new Pair<>(JS_EVENT_KEY_CALL_INFO, serializeCall(callRecord))));
  }

  @Override
  public void onReconnecting(@NonNull Call call, @NonNull CallException callException) {
    debug("onReconnecting");

    // find & update call record
    CallRecord callRecord = Objects.requireNonNull(getCallRecordDatabase().get(new CallRecord(uuid)));

    // notify JS layer
    sendJSEvent(
      constructJSMap(
        new Pair<>(VoiceEventType, CallEventReconnecting),
        new Pair<>(JS_EVENT_KEY_CALL_INFO, serializeCall(callRecord)),
        new Pair<>(VoiceErrorKeyError, serializeVoiceException(callException))));
  }

  @Override
  public void onReconnected(@NonNull Call call) {
    debug("onReconnected");

    // find & update call record
    CallRecord callRecord = Objects.requireNonNull(getCallRecordDatabase().get(new CallRecord(uuid)));

    // notify JS layer
    sendJSEvent(
      constructJSMap(
        new Pair<>(VoiceEventType, CallEventReconnected),
        new Pair<>(JS_EVENT_KEY_CALL_INFO, serializeCall(callRecord))));
  }

  @Override
  public void onDisconnected(@NonNull Call call, @Nullable CallException callException) {
    debug("onDisconnected");

    // find & remove call record
    CallRecord callRecord = Objects.requireNonNull(getCallRecordDatabase().remove(new CallRecord(uuid)));

    // stop audio & cancel notification
    getMediaPlayerManager().stop();
    // Close patch: only tear down the shared audio session when no
    // other call remains active. When the user answers a second call, the first
    // call is disconnected while the second is still live -- playing the
    // disconnect tone or deactivating the AudioSwitch here would disrupt the
    // second call's audio. This (disconnecting) call's record was already
    // removed above, so the database now reflects only the remaining calls.
    if (!getCallRecordDatabase().hasActiveCall()) {
      // Close patch: no disconnect tone while another call is still ringing --
      // play() stops what is playing first, so the tone would cut the ring off,
      // and a hangup tone over a ringing phone is wrong anyway.
      if (!getCallRecordDatabase().hasRingingCall()) {
        getMediaPlayerManager().play(MediaPlayerManager.SoundTable.DISCONNECT);
      }
      getAudioSwitchManager().getAudioSwitch().deactivate();
      // Close patch: only take down the foreground notification when
      // no other call remains. removeForegroundNotification() ignores the id and
      // removes whatever is currently the service's foreground notification --
      // during "answer & end" the just-answered call has already become that
      // notification, so removing it here would tear down the surviving call's
      // notification and drop the service out of foreground while a call is live.
      getVoiceServiceApi().cancelActiveCallNotification(callRecord);
    }
    // Close patch: if a second call is still ringing after this call
    // ended, re-post its incoming notification. This (a) re-asserts it after the
    // ended call's foreground teardown and (b) refreshes its label -- with no
    // call active anymore, answering no longer ends anything, so it shows
    // "Answer" instead of the stale "End & answer".
    getVoiceServiceApi().refreshRingingIncomingCallNotifications();

    // notify JS layer
    sendJSEvent(
      constructJSMap(
        new Pair<>(VoiceEventType, CallEventDisconnected),
        new Pair<>(JS_EVENT_KEY_CALL_INFO, serializeCall(callRecord)),
        new Pair<>(VoiceErrorKeyError, serializeVoiceException(callException))));
  }

  @Override
  public void onCallQualityWarningsChanged(@NonNull Call call,
                                           @NonNull Set<Call.CallQualityWarning> currentWarnings,
                                           @NonNull Set<Call.CallQualityWarning> previousWarnings) {
    debug("onCallQualityWarningsChanged");

    // find call record
    CallRecord callRecord = Objects.requireNonNull(getCallRecordDatabase().get(new CallRecord(uuid)));

    // notify JS layer
    sendJSEvent(
      constructJSMap(
        new Pair<>(VoiceEventType, CallEventQualityWarningsChanged),
        new Pair<>(JS_EVENT_KEY_CALL_INFO, serializeCall(callRecord)),
        new Pair<>(CallEventCurrentWarnings, serializeCallQualityWarnings(currentWarnings)),
        new Pair<>(CallEventPreviousWarnings, serializeCallQualityWarnings(previousWarnings))));
  }

  private void sendJSEvent(@NonNull WritableMap event) {
    getJSEventEmitter().sendEvent(ScopeCall, event);
  }

  private void debug(final String message) {
    logger.debug(String.format("%s UUID:%s", message, uuid.toString()));
  }
}
