package com.twiliovoicereactnative;


import static com.twiliovoicereactnative.CommonConstants.CallInviteEventKeyCallSid;
import static com.twiliovoicereactnative.CommonConstants.CallInviteEventKeyType;
import static com.twiliovoicereactnative.CommonConstants.CallInviteEventTypeValueAccepted;
import static com.twiliovoicereactnative.CommonConstants.CallInviteEventTypeValueCancelled;
import static com.twiliovoicereactnative.CommonConstants.CallInviteEventTypeValueNotificationTapped;
import static com.twiliovoicereactnative.CommonConstants.CallInviteEventTypeValueRejected;
import static com.twiliovoicereactnative.CommonConstants.ScopeCallInvite;
import static com.twiliovoicereactnative.CommonConstants.ScopeVoice;
import static com.twiliovoicereactnative.CommonConstants.VoiceErrorKeyError;
import static com.twiliovoicereactnative.CommonConstants.VoiceEventError;
import static com.twiliovoicereactnative.CommonConstants.VoiceEventType;
import static com.twiliovoicereactnative.CommonConstants.VoiceEventTypeValueIncomingCallInvite;
import static com.twiliovoicereactnative.Constants.ACTION_ACCEPT_CALL;
import static com.twiliovoicereactnative.Constants.ACTION_CALL_DISCONNECT;
import static com.twiliovoicereactnative.Constants.ACTION_CANCEL_CALL;
import static com.twiliovoicereactnative.Constants.ACTION_CANCEL_ACTIVE_CALL_NOTIFICATION;
import static com.twiliovoicereactnative.Constants.ACTION_FOREGROUND_AND_DEPRIORITIZE_INCOMING_CALL_NOTIFICATION;
import static com.twiliovoicereactnative.Constants.ACTION_INCOMING_CALL;
import static com.twiliovoicereactnative.Constants.ACTION_PUSH_APP_TO_FOREGROUND;
import static com.twiliovoicereactnative.Constants.ACTION_RAISE_OUTGOING_CALL_NOTIFICATION;
import static com.twiliovoicereactnative.Constants.ACTION_REJECT_CALL;
import static com.twiliovoicereactnative.Constants.JS_EVENT_KEY_CALL_INVITE_INFO;
import static com.twiliovoicereactnative.Constants.JS_EVENT_KEY_CANCELLED_CALL_INVITE_INFO;
import static com.twiliovoicereactnative.Constants.VOICE_CHANNEL_DEFAULT_IMPORTANCE;
import static com.twiliovoicereactnative.Constants.VOICE_CHANNEL_HIGH_IMPORTANCE;
import static com.twiliovoicereactnative.JSEventEmitter.constructJSMap;
import static com.twiliovoicereactnative.ReactNativeArgumentsSerializer.serializeCall;
import static com.twiliovoicereactnative.ReactNativeArgumentsSerializer.serializeCallException;
import static com.twiliovoicereactnative.ReactNativeArgumentsSerializer.serializeCallInvite;
import static com.twiliovoicereactnative.ReactNativeArgumentsSerializer.serializeCancelledCallInvite;
import static com.twiliovoicereactnative.ReactNativeArgumentsSerializer.serializeError;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getCallRecordDatabase;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getJSEventEmitter;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ServiceCompat;

import com.facebook.react.bridge.WritableMap;
import com.twilio.voice.AcceptOptions;
import com.twilio.voice.Call;
import com.twilio.voice.ConnectOptions;
import com.twilio.voice.Voice;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class VoiceService extends Service {
  private static final SDKLog logger = new SDKLog(VoiceService.class);
  // Close patch: native safety-net timeout so an unanswered
  // incoming call stops ringing even if Twilio's "cancel" push never arrives
  // (network loss / Doze). A JS timer can't do this -- RN timers are suspended
  // while the app is backgrounded/locked, which is exactly the ring-forever
  // scenario. Must exceed the longest server ring (~32s for group numbers).
  private static final long INCOMING_RING_TIMEOUT_MS = 60000L;
  private final Handler ringTimeoutHandler = new Handler(Looper.getMainLooper());
  private final Map<UUID, Runnable> ringTimeouts = new HashMap<>();
  private void scheduleRingTimeout(final CallRecordDatabase.CallRecord callRecord) {
    final UUID uuid = callRecord.getUuid();
    if (null == uuid) {
      return;
    }
    final Runnable timeout = () -> {
      ringTimeouts.remove(uuid);
      final CallRecordDatabase.CallRecord current =
        getCallRecordDatabase().get(new CallRecordDatabase.CallRecord(uuid));
      if (null != current) {
        logger.warning("Incoming call ring timed out, rejecting");
        rejectCall(current);
      }
    };
    ringTimeouts.put(uuid, timeout);
    ringTimeoutHandler.postDelayed(timeout, INCOMING_RING_TIMEOUT_MS);
  }
  private void cancelRingTimeout(final CallRecordDatabase.CallRecord callRecord) {
    final UUID uuid = callRecord.getUuid();
    if (null == uuid) {
      return;
    }
    final Runnable timeout = ringTimeouts.remove(uuid);
    if (null != timeout) {
      ringTimeoutHandler.removeCallbacks(timeout);
    }
  }
  public class VoiceServiceAPI extends Binder {
    public Call connect(@NonNull ConnectOptions cxnOptions,
                        @NonNull Call.Listener listener) {
      logger.debug("connect");
      return Voice.connect(VoiceService.this, cxnOptions, listener);
    }
    public void disconnect(final CallRecordDatabase.CallRecord callRecord) {
      VoiceService.this.disconnect(callRecord);
    }
    public void incomingCall(final CallRecordDatabase.CallRecord callRecord) {
      VoiceService.this.incomingCall(callRecord);
    }
    public void acceptCall(final CallRecordDatabase.CallRecord callRecord) {
      VoiceService.this.acceptCall(callRecord);
    }
    public void rejectCall(final CallRecordDatabase.CallRecord callRecord) {
      VoiceService.this.rejectCall(callRecord);
    }
    public void cancelCall(final CallRecordDatabase.CallRecord callRecord) {
      VoiceService.this.cancelCall(callRecord);
    }
    public void raiseOutgoingCallNotification(final CallRecordDatabase.CallRecord callRecord) {
      VoiceService.this.raiseOutgoingCallNotification(callRecord);
    }
    public void cancelActiveCallNotification(final CallRecordDatabase.CallRecord callRecord) {
      VoiceService.this.cancelActiveCallNotification(callRecord);
    }
    public void refreshRingingIncomingCallNotifications() {
      VoiceService.this.refreshRingingIncomingCallNotifications();
    }
    public void foregroundAndDeprioritizeIncomingCallNotification(final CallRecordDatabase.CallRecord callRecord) {
      VoiceService.this.foregroundAndDeprioritizeIncomingCallNotification(callRecord);
    }
    public Context getServiceContext() {
      return VoiceService.this;
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    // apparently the system can recreate the service without sending it an intent so protect
    // against that case (GH-430).
    if (null != intent) {
      switch (Objects.requireNonNull(intent.getAction())) {
        // Close patch: route every record-dependent action
        // through withCallRecord(), which tolerates an already-gone record (see below).
        case ACTION_INCOMING_CALL:
          withCallRecord(intent, "incomingCall", this::incomingCall);
          break;
        case ACTION_ACCEPT_CALL:
          withCallRecord(intent, "acceptCall", record -> {
            try {
              acceptCall(record);
            } catch (SecurityException e) {
              sendPermissionsError();
              logger.warning(e, "Cannot accept call, lacking necessary permissions");
            }
          });
          break;
        case ACTION_REJECT_CALL:
          withCallRecord(intent, "rejectCall", this::rejectCall);
          break;
        case ACTION_CANCEL_CALL:
          withCallRecord(intent, "cancelCall", this::cancelCall);
          break;
        case ACTION_CALL_DISCONNECT:
          withCallRecord(intent, "disconnect", this::disconnect);
          break;
        case ACTION_RAISE_OUTGOING_CALL_NOTIFICATION:
          withCallRecord(intent, "raiseOutgoingCallNotification",
            this::raiseOutgoingCallNotification);
          break;
        case ACTION_CANCEL_ACTIVE_CALL_NOTIFICATION:
          withCallRecord(intent, "cancelActiveCallNotification",
            this::cancelActiveCallNotification);
          break;
        case ACTION_FOREGROUND_AND_DEPRIORITIZE_INCOMING_CALL_NOTIFICATION:
          withCallRecord(intent, "foregroundAndDeprioritizeIncomingCallNotification",
            this::foregroundAndDeprioritizeIncomingCallNotification);
          break;
        case ACTION_PUSH_APP_TO_FOREGROUND:
          logger.warning("VoiceService received foreground request, ignoring");
          break;
        default:
          logger.log("Unknown notification, ignoring");
          break;
      }
    }
    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return new VoiceServiceAPI();
  }
  // Close patch: cancel any pending ring-timeout callbacks when the
  // service is destroyed. The Runnables are posted to the process-global main
  // looper and capture this VoiceService instance, so without this they would
  // stay queued for up to INCOMING_RING_TIMEOUT_MS -- leaking the service and
  // eventually running rejectCall() against a destroyed context.
  @Override
  public void onDestroy() {
    ringTimeoutHandler.removeCallbacksAndMessages(null);
    ringTimeouts.clear();
    super.onDestroy();
  }
  public static Intent constructMessage(@NonNull Context context,
                                        @NonNull final String action,
                                        @NonNull final Class<?> target,
                                        @NonNull final UUID uuid) {
    Intent intent = new Intent(context.getApplicationContext(), target);
    intent.setAction(action);
    intent.putExtra(Constants.MSG_KEY_UUID, uuid);
    // Close patch: key every call intent by action + uuid. PendingIntent
    // matching ignores extras, so without this the Answer/Decline/End actions of
    // two concurrent calls are filterEqual: same action, same target, request
    // code 0, no data. FLAG_UPDATE_CURRENT then rewrites the earlier call's UUID
    // to the later one's, so both notifications drive whichever call was rendered
    // last -- and every re-post (a caller-name refresh,
    // refreshRingingIncomingCallNotifications) shifts it again. Declining the
    // second call could reject or hang up the first.
    //
    // Done here rather than at each call site so a new notification action cannot
    // reintroduce the collision by forgetting it. Nothing dispatches on the data;
    // every consumer reads the UUID extra.
    intent.setData(Uri.parse("twilio-call://" + action + "/" + uuid));
    return intent;
  }
  // Close patch: tell the incoming-call answer screen
  // (IncomingCallActivity) that ringing has ended so it can dismiss itself.
  // This works even on cold-start, where there is no JS runtime to react to
  // the CallInvite events.
  private void broadcastIncomingCallEnded(final CallRecordDatabase.CallRecord callRecord) {
    if (null == callRecord || null == callRecord.getUuid()) {
      return;
    }
    Intent endedIntent = new Intent(getPackageName() + ".INCOMING_CALL_ENDED");
    endedIntent.setPackage(getPackageName());
    endedIntent.putExtra("uuid", callRecord.getUuid().toString());
    sendBroadcast(endedIntent);
  }
  private void disconnect(final CallRecordDatabase.CallRecord callRecord) {
    logger.debug("disconnect");
    if (null != callRecord) {
      Objects.requireNonNull(callRecord.getVoiceCall()).disconnect();
    } else {
      logger.warning("No call record found");
    }
  }
  private void incomingCall(final CallRecordDatabase.CallRecord callRecord) {
    logger.debug("incomingCall: " + callRecord.getUuid());

    // verify that mic permissions have been granted and if not, throw a error
    if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) &&
      ActivityCompat.checkSelfPermission(VoiceService.this,
        Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {

      // report to js layer lack of permissions issue
      sendPermissionsError();

      // report an error to logger
      logger.warning("WARNING: Incoming call cannot be handled, microphone permission not granted");
      return;
    }

    // Close patch: if the user is already on an active call, treat
    // this as a "second call" -- show a quiet, lightweight notification and give
    // a single vibration nudge instead of the full looping ring + full-screen
    // takeover, so it doesn't interrupt the call in progress. Applies to all
    // second calls (personal or group).
    //
    // Decide this ONCE, now, and store it on the record. Later re-posts (a
    // caller-name refresh from JS, or refreshRingingIncomingCallNotifications
    // when the first call ends) read the stored flag instead of re-deriving it
    // from the live call list -- otherwise a first call ending mid-refresh would
    // flip this to false and promote the quiet nudge back into a full-screen ring.
    final boolean secondCallWhileBusy = getCallRecordDatabase().hasActiveCall();
    callRecord.setLightweightNotification(secondCallWhileBusy);

    // put up notification
    callRecord.setNotificationId(NotificationUtility.createNotificationIdentifier());
    Notification notification = NotificationUtility.createIncomingCallNotification(
      VoiceService.this,
      callRecord,
      secondCallWhileBusy ? VOICE_CHANNEL_DEFAULT_IMPORTANCE : VOICE_CHANNEL_HIGH_IMPORTANCE,
      secondCallWhileBusy);
    createOrReplaceNotification(callRecord.getNotificationId(), notification);

    if (secondCallWhileBusy) {
      // second call: a continuous (gentler) vibration nudge, no ring sound, no
      // screen takeover. Cancelled when the call is resolved (stop()).
      //
      // If the FIRST (active) call ends while this second call is still ringing,
      // CallListenerProxy.onDisconnected -> refreshRingingIncomingCallNotifications()
      // re-posts this notification (surviving the ended call's foreground
      // teardown), relabels it "Answer" (nothing left to end), and resumes the
      // vibration. Not yet done: upgrading a now-alone second call to a full
      // ring (sound + full-screen) -- deferred to the hold/switch follow-up.
      logger.debug("incomingCall: already on an active call, vibrating for second call");
      VoiceApplicationProxy.getMediaPlayerManager().startSecondCallVibration();
    } else {
      // play ringer sound
      // Close patch: do NOT activate the AudioSwitch here. Activating
      // puts the audio system into MODE_IN_COMMUNICATION, which would route the
      // incoming ring to the earpiece at call volume and defeat playing it as a
      // real ringtone on the ring stream. The AudioSwitch is activated only once
      // the call is actually accepted (see acceptCall).
      VoiceApplicationProxy.getMediaPlayerManager().play(MediaPlayerManager.SoundTable.INCOMING);
    }

    // Close patch: stop ringing if the call is never resolved.
    scheduleRingTimeout(callRecord);

    // trigger JS layer
    sendJSEvent(
      ScopeVoice,
      constructJSMap(
        new Pair<>(VoiceEventType, VoiceEventTypeValueIncomingCallInvite),
        new Pair<>(JS_EVENT_KEY_CALL_INVITE_INFO, serializeCallInvite(callRecord))));
  }
  private void acceptCall(final CallRecordDatabase.CallRecord callRecord) {
    logger.debug("acceptCall: " + callRecord.getUuid());
    // Close patch: ignore a duplicate accept (e.g. a double-tap on
    // the lightweight notification's Answer action, which is an Activity
    // PendingIntent) -- the CallInvite is single-use. Bail if it was already
    // accepted (voice call set) or the invite is gone.
    if (null != callRecord.getVoiceCall() || null == callRecord.getCallInvite()) {
      logger.warning("acceptCall: call already accepted or invite missing, ignoring");
      return;
    }
    cancelRingTimeout(callRecord);
    broadcastIncomingCallEnded(callRecord);

    // verify that mic permissions have been granted and if not, throw a error
    if (ActivityCompat.checkSelfPermission(VoiceService.this,
      Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      // cancel incoming call notification
      removeNotification(callRecord.getNotificationId());

      // stop ringer sound
      VoiceApplicationProxy.getMediaPlayerManager().stop();
      VoiceApplicationProxy.getAudioSwitchManager().getAudioSwitch().deactivate();

      // report an error to JS layer
      sendPermissionsError();

      // report an error to logger
      logger.warning("WARNING: Call not accepted, microphone permission not granted");
      return;
    }

    // cancel existing notification & put up in call
    Notification notification = NotificationUtility.createCallAnsweredNotificationWithLowImportance(
      VoiceService.this,
      callRecord);
    createOrReplaceForegroundNotification(callRecord.getNotificationId(), notification);

    // stop ringer sound
    VoiceApplicationProxy.getMediaPlayerManager().stop();

    // Close patch: activate the AudioSwitch here (rather than at
    // ring time in incomingCall) so the accepted call gets proper in-call audio
    // routing/focus. Deferring it lets the incoming ring play as a normal
    // ringtone on the ring stream beforehand.
    VoiceApplicationProxy.getAudioSwitchManager().getAudioSwitch().activate();

    // accept call
    AcceptOptions acceptOptions = new AcceptOptions.Builder()
      .enableDscp(true)
      .callMessageListener(new CallMessageListenerProxy())
      .build();

    callRecord.setCall(
      callRecord.getCallInvite().accept(
        VoiceService.this,
        acceptOptions,
        new CallListenerProxy(callRecord.getUuid(), VoiceService.this)));
    callRecord.setCallInviteUsedState();

    // Close patch: "answer & end" -- now that this call is accepted,
    // disconnect any OTHER call that was still active (the user answered a second
    // call while on a first one). The newly accepted call replaces the previous
    // one; hold/switch between the two is a future enhancement. onDisconnected
    // skips the shared-audio teardown while this call is still active, so ending
    // the previous call doesn't kill this call's audio (see CallListenerProxy).
    endOtherActiveCalls(callRecord);

    // handle if event spawned from JS
    if (null != callRecord.getCallAcceptedPromise()) {
      callRecord.getCallAcceptedPromise().resolve(serializeCall(callRecord));
    }

    // notify JS layer
    sendJSEvent(
      ScopeCallInvite,
      constructJSMap(
        new Pair<>(CallInviteEventKeyType, CallInviteEventTypeValueAccepted),
        new Pair<>(CallInviteEventKeyCallSid, callRecord.getCallSid()),
        new Pair<>(JS_EVENT_KEY_CALL_INVITE_INFO, serializeCallInvite(callRecord))));
  }
  private void rejectCall(final CallRecordDatabase.CallRecord callRecord) {
    logger.debug("rejectCall: " + callRecord.getUuid());
    // Close patch: only a still-ringing invite can be rejected. Mirrors
    // the guard in acceptCall(). Without it a reject aimed at a call that is no
    // longer ringing -- already accepted, already rejected, cancelled by the
    // caller (setCancelledCallInvite nulls the invite), or outgoing (never had
    // one) -- threw an NPE out of getCallInvite().reject() and crashed
    // VoiceService. Reachable whenever a stale Decline action is delivered, e.g.
    // a second-call notification tapped just as the invite resolves.
    //
    // Bailing early also protects the ACCEPTED case specifically: the teardown
    // below (remove the record, deactivate the AudioSwitch) would otherwise rip
    // the audio out from under a live call and orphan it in JS.
    if (null == callRecord.getCallInvite()
        || CallRecordDatabase.CallRecord.CallInviteState.ACTIVE != callRecord.getCallInviteState()) {
      logger.warning("rejectCall: call is no longer ringing, ignoring");
      // Settle a JS-initiated reject so the promise can't hang -- callInvite_reject
      // stores it before dispatching here, and validateCallInviteRecord only
      // null-checks the invite, so a reject of an already-used invite reaches us.
      if (null != callRecord.getCallRejectedPromise()) {
        callRecord.getCallRejectedPromise().reject("Call is no longer ringing");
      }
      return;
    }
    cancelRingTimeout(callRecord);
    broadcastIncomingCallEnded(callRecord);

    // remove call record
    getCallRecordDatabase().remove(callRecord);

    // take down notification
    removeNotification(callRecord.getNotificationId());

    // stop ringer sound
    VoiceApplicationProxy.getMediaPlayerManager().stop();
    // Close patch: only tear down the shared audio session when no
    // other call is still live -- same guard as CallListenerProxy.onDisconnected.
    // Declining a second call while the first is in progress used to deactivate
    // the AudioSwitch out from under that live call, killing its audio.
    // The record was removed above, so the database reflects only remaining calls.
    if (!getCallRecordDatabase().hasActiveCall()) {
      VoiceApplicationProxy.getAudioSwitchManager().getAudioSwitch().deactivate();
    }

    // reject call
    callRecord.getCallInvite().reject(VoiceService.this);
    callRecord.setCallInviteUsedState();

    // handle if event spawned from JS
    if (null != callRecord.getCallRejectedPromise()) {
      callRecord.getCallRejectedPromise().resolve(callRecord.getUuid().toString());
    }

    // notify JS layer
    sendJSEvent(
      ScopeCallInvite,
      constructJSMap(
        new Pair<>(CallInviteEventKeyType, CallInviteEventTypeValueRejected),
        new Pair<>(CallInviteEventKeyCallSid, callRecord.getCallSid()),
        new Pair<>(JS_EVENT_KEY_CALL_INVITE_INFO, serializeCallInvite(callRecord))));
  }
  private void cancelCall(final CallRecordDatabase.CallRecord callRecord) {
    logger.debug("CancelCall: " + callRecord.getUuid());
    cancelRingTimeout(callRecord);
    broadcastIncomingCallEnded(callRecord);

    // take down notification
    removeNotification(callRecord.getNotificationId());

    // stop ringer sound
    VoiceApplicationProxy.getMediaPlayerManager().stop();
    // Close patch: same guard as rejectCall -- a second call being
    // cancelled by its caller must not deactivate the audio session of the
    // first call, which is still in progress. A cancelled invite has no voice
    // Call, so it never counts toward hasActiveCall() itself.
    if (!getCallRecordDatabase().hasActiveCall()) {
      VoiceApplicationProxy.getAudioSwitchManager().getAudioSwitch().deactivate();
    }

    // notify JS layer
    sendJSEvent(
      ScopeCallInvite,
      constructJSMap(
        new Pair<>(CallInviteEventKeyType, CallInviteEventTypeValueCancelled),
        new Pair<>(CallInviteEventKeyCallSid, callRecord.getCallSid()),
        new Pair<>(JS_EVENT_KEY_CANCELLED_CALL_INVITE_INFO, serializeCancelledCallInvite(callRecord)),
        new Pair<>(VoiceErrorKeyError, serializeCallException(callRecord))));
  }
  private void raiseOutgoingCallNotification(final CallRecordDatabase.CallRecord callRecord) {
    logger.debug("raiseOutgoingCallNotification: " + callRecord.getUuid());

    // put up outgoing call notification
    Notification notification =
      NotificationUtility.createOutgoingCallNotificationWithLowImportance(
        VoiceService.this,
        callRecord);
    createOrReplaceForegroundNotification(callRecord.getNotificationId(), notification);
  }
  private void foregroundAndDeprioritizeIncomingCallNotification(final CallRecordDatabase.CallRecord callRecord) {
    logger.debug("foregroundAndDeprioritizeIncomingCallNotification: " + callRecord.getUuid());

    // cancel existing notification & put up in call
    // Close patch: this "deprioritize" path already downgrades to
    // the default channel and stops the sound, so use the lightweight (no
    // full-screen intent) variant to avoid re-triggering a takeover.
    Notification notification = NotificationUtility.createIncomingCallNotification(
      VoiceService.this,
      callRecord,
      VOICE_CHANNEL_DEFAULT_IMPORTANCE,
      true);
    createOrReplaceNotification(callRecord.getNotificationId(), notification);

    // stop active sound (if any)
    VoiceApplicationProxy.getMediaPlayerManager().stop();

    // notify JS layer
    sendJSEvent(
      ScopeCallInvite,
      constructJSMap(
        new Pair<>(CallInviteEventKeyType, CallInviteEventTypeValueNotificationTapped),
        new Pair<>(CallInviteEventKeyCallSid, callRecord.getCallSid())));
  }
  private void cancelActiveCallNotification(final CallRecordDatabase.CallRecord callRecord) {
    logger.debug("cancelNotification");
    // only take down notification & stop any active sounds if one is active
    if (null != callRecord) {
      VoiceApplicationProxy.getMediaPlayerManager().stop();
      removeForegroundNotification();
    }
  }
  private void createOrReplaceNotification(final int notificationId,
                                           final Notification notification) {
    NotificationManager mNotificationManager =
      (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    mNotificationManager.notify(notificationId, notification);
  }
  private void createOrReplaceForegroundNotification(final int notificationId,
                                                     final Notification notification) {
    if (ActivityCompat.checkSelfPermission(VoiceService.this, Manifest.permission.POST_NOTIFICATIONS)
      == PackageManager.PERMISSION_GRANTED) {
      foregroundNotification(notificationId, notification);
    } else {
      logger.warning("WARNING: Notification not posted, permission not granted");
    }
  }
  private void removeNotification(final int notificationId) {
    logger.debug("removeNotification");
    NotificationManager mNotificationManager =
      (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    mNotificationManager.cancel(notificationId);
  }
  private void removeForegroundNotification() {
    logger.debug("removeForegroundNotification");
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
  }
  private void foregroundNotification(int id, Notification notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      try {
        startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
      } catch (Exception e) {
        sendPermissionsError();
        logger.warning(e, "Failed to place notification due to lack of permissions");
      }
    } else {
      startForeground(id, notification);
    }
  }
  private static UUID getMessageUUID(@NonNull final Intent intent) {
    return (UUID)intent.getSerializableExtra(Constants.MSG_KEY_UUID);
  }
  // Close patch: getCallRecord() (requireNonNull) removed; all
  // dispatch now goes through getCallRecordOrNull()/withCallRecord().
  private static CallRecordDatabase.CallRecord getCallRecordOrNull(final UUID uuid) {
    return (null == uuid) ? null : getCallRecordDatabase().get(new CallRecordDatabase.CallRecord(uuid));
  }
  // Close patch: run `handler` with the intent's call record,
  // or skip with a warning if it's already gone. Guards onStartCommand against a
  // notification action / service restart racing call teardown, which used to
  // throw NPE out of requireNonNull and crash VoiceService.
  private void withCallRecord(
      final Intent intent,
      final String label,
      final java.util.function.Consumer<CallRecordDatabase.CallRecord> handler) {
    final CallRecordDatabase.CallRecord record = getCallRecordOrNull(getMessageUUID(intent));
    if (null == record) {
      logger.warning(label + ": no call record found, ignoring");
      return;
    }
    handler.accept(record);
  }
  // Close patch: re-post the incoming notification for any call that
  // is still ringing (has a CallInvite, not yet accepted). Called when another
  // call ends, so a surviving second call (a) stays visible past the ended
  // call's foreground-service teardown and (b) drops the "End & answer" label
  // now that answering no longer ends an active call -- the re-post sees no
  // active call, so hasActiveCallOtherThan() is false and it renders "Answer".
  // Kept lightweight (quiet, no full-screen takeover); upgrading a now-alone
  // second call to a full ring is a deferred enhancement.
  private void refreshRingingIncomingCallNotifications() {
    boolean anyStillRinging = false;
    // Close patch: snapshot -- getCollection() is the live Vector,
    // which a Twilio SDK thread may structurally modify mid-iteration (CME).
    for (final CallRecordDatabase.CallRecord record :
        new java.util.ArrayList<>(getCallRecordDatabase().getCollection())) {
      if (null == record.getVoiceCall()
          && null != record.getCallInvite()
          && CallRecordDatabase.CallRecord.CallInviteState.ACTIVE == record.getCallInviteState()) {
        logger.debug("refreshRingingIncomingCallNotifications: re-posting " + record.getUuid());
        Notification notification = NotificationUtility.createIncomingCallNotification(
          VoiceService.this,
          record,
          VOICE_CHANNEL_DEFAULT_IMPORTANCE,
          true);
        createOrReplaceNotification(record.getNotificationId(), notification);
        anyStillRinging = true;
      }
    }
    if (anyStillRinging) {
      // Close patch: resume the second-call vibration nudge -- the
      // ended call's teardown (stop()) cancelled it. Same gentle cadence and
      // stop() lifecycle as when the second call first arrived, so it's still
      // cancelled when this call is answered/declined/cancelled/times out.
      VoiceApplicationProxy.getMediaPlayerManager().startSecondCallVibration();
    }
  }
  // Close patch: disconnect every tracked call except `keep` that
  // still has a live voice Call. activeCallsOtherThan() returns a snapshot list,
  // so disconnecting (which mutates the database via the async onDisconnected
  // callback) doesn't modify what we iterate.
  private void endOtherActiveCalls(final CallRecordDatabase.CallRecord keep) {
    for (final CallRecordDatabase.CallRecord record :
        getCallRecordDatabase().activeCallsOtherThan(keep)) {
      logger.debug("endOtherActiveCalls: ending previously active call " + record.getUuid()
        + " because a new call was accepted");
      disconnect(record);
    }
  }
  private static void sendJSEvent(@NonNull String scope, @NonNull WritableMap event) {
    getJSEventEmitter().sendEvent(scope, event);
  }
  private static void sendPermissionsError() {
    final String errorMessage = "Missing permissions.";
    final int errorCode = 31401;
    getJSEventEmitter().sendEvent(ScopeVoice, constructJSMap(
      new Pair<>(VoiceEventType, VoiceEventError),
      new Pair<>(VoiceErrorKeyError, serializeError(errorCode, errorMessage))
    ));
  }
}
