package com.twiliovoicereactnative;

import static com.twiliovoicereactnative.VoiceApplicationProxy.getCallRecordDatabase;
import static com.twiliovoicereactnative.VoiceApplicationProxy.getVoiceServiceApi;

import com.twiliovoicereactnative.CallRecordDatabase.CallRecord;

import android.os.PowerManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;
import com.twilio.voice.MessageListener;
import com.twilio.voice.Voice;

import java.util.UUID;

public class VoiceFirebaseMessagingService extends FirebaseMessagingService {
  private static final SDKLog logger = new SDKLog(VoiceFirebaseMessagingService.class);

  public static class MessageHandler implements MessageListener  {
    @Override
    public void onCallInvite(@NonNull CallInvite callInvite) {
      logger.log(String.format("onCallInvite %s", callInvite.getCallSid()));

      final CallRecord callRecord = new CallRecord(UUID.randomUUID(), callInvite);

      getCallRecordDatabase().add(callRecord);
      getVoiceServiceApi().incomingCall(callRecord);
    }

    @Override
    public void onCancelledCallInvite(@NonNull CancelledCallInvite cancelledCallInvite,
                                      @Nullable CallException callException) {
      logger.log(String.format("onCancelledCallInvite %s", cancelledCallInvite.getCallSid()));

      // Close patch: the record may already be gone -- the native
      // ring timeout rejects (and removes) an unanswered call after 60s, which
      // is exactly the Doze / FCM-throttling window in which a delayed cancel
      // push arrives afterwards. The upstream Objects.requireNonNull would then
      // throw an NPE on this Firebase background thread and crash the process,
      // so bail out gracefully if the call was already resolved.
      CallRecord callRecord =
        getCallRecordDatabase().remove(new CallRecord(cancelledCallInvite.getCallSid()));
      if (null == callRecord) {
        logger.warning("onCancelledCallInvite: call already resolved, ignoring");
        return;
      }

      callRecord.setCancelledCallInvite(cancelledCallInvite);
      callRecord.setCallException(callException);
      getVoiceServiceApi().cancelCall(callRecord);
    }
  }

  @Override
  public void onNewToken(@NonNull String token) {
    logger.log("Refreshed FCM token: " + token);
  }

  /**
   * Called when message is received.
   *
   * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
   */
  @Override
  public void onMessageReceived(RemoteMessage remoteMessage) {
    logger.debug("onMessageReceived remoteMessage: " + remoteMessage.toString());
    logger.debug("Bundle data: " + remoteMessage.getData());
    logger.debug("From: " + remoteMessage.getFrom());

    PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
    boolean isScreenOn = pm.isInteractive(); // check if screen is on
    if (!isScreenOn) {
      PowerManager.WakeLock wl = pm.newWakeLock(
        PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
        "VoiceFirebaseMessagingService:notificationLock");
      wl.acquire(30000); //set your time in milliseconds
    }

    // Check if message contains a data payload.
    if (!remoteMessage.getData().isEmpty()) {
      if (!Voice.handleMessage(
        this,
        remoteMessage.getData(),
        new MessageHandler(),
        new CallMessageListenerProxy())) {
        logger.error("The message was not a valid Twilio Voice SDK payload: " +
          remoteMessage.getData());
      }
    }
  }
}
