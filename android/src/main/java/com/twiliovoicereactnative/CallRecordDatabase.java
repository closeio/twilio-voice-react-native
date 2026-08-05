package com.twiliovoicereactnative;

import static com.twiliovoicereactnative.CallRecordDatabase.CallRecord.CallInviteState.ACTIVE;
import static com.twiliovoicereactnative.CallRecordDatabase.CallRecord.CallInviteState.NONE;
import static com.twiliovoicereactnative.CallRecordDatabase.CallRecord.CallInviteState.USED;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.Vector;
import java.util.Map;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Promise;
import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;

public class CallRecordDatabase  {
  public static class CallRecord {
    public enum CallInviteState { NONE, ACTIVE, USED }
    public enum Direction { INCOMING, OUTGOING }
    private final UUID uuid;
    private String callSid = null;
    private Date timestamp = null;
    private int notificationId = -1;
    private Call voiceCall = null;
    private String callRecipient = "";
    private CallInvite callInvite = null;
    private CallInviteState callInviteState = NONE;
    private CancelledCallInvite cancelledCallInvite = null;
    private Promise callAcceptedPromise = null;
    private Promise callRejectedPromise = null;
    private CallException callException = null;
    private Map<String, String> customParameters = null;
    private String notificationDisplayName = null;
    private Direction direction = Direction.INCOMING;
    // Close patch: whether this incoming call was first presented as
    // a lightweight "second call" (quiet, no full-screen ring) because the user
    // was already on a call. Decided once at arrival and read on every re-post
    // so an async caller-name refresh can't re-derive it from a since-changed
    // call list and promote the quiet nudge back into a full-screen ring.
    private boolean lightweightNotification = false;
    // Close patch: this call's ring has been deliberately silenced --
    // the user opened its answer screen, or answering it failed. The invite can
    // still be ACTIVE (it is genuinely still ringing and can be answered), so
    // this is what stops VoiceService.syncRinger() from starting the sound up
    // again on the next reconcile.
    private boolean ringSilenced = false;
    public CallRecord(final UUID uuid) {
      this.uuid = uuid;
    }
    public CallRecord(final String callSid) {
      this.uuid = null;
      this.callSid = callSid;
    }
    public CallRecord(final UUID uuid, final CallInvite callInvite) {
      this.uuid = uuid;
      this.callSid = callInvite.getCallSid();
      this.callInvite = callInvite;
      this.callInviteState = ACTIVE;
    }
    public CallRecord(
      final UUID uuid,
      final Call call,
      final String recipient,
      final Map<String, String> customParameters,
      final Direction direction,
      final String notificationDisplayName
    ) {
      this.uuid = uuid;
      this.callSid = call.getSid();
      this.voiceCall = call;
      this.callRecipient = recipient;
      this.customParameters = customParameters;
      this.direction = direction;
      this.notificationDisplayName = notificationDisplayName;
    }
    public final UUID getUuid() {
      return uuid;
    }
    public String getCallSid() {
      return callSid;
    }
    public int getNotificationId() {
      return notificationId;
    }
    public Date getTimestamp() {
      return timestamp;
    }
    public Call getVoiceCall() {
      return this.voiceCall;
    }
    public final Map<String, String> getCustomParameters() {
      if (this.direction == Direction.INCOMING) {
        return this.callInvite.getCustomParameters();
      }
      return this.customParameters;
    }
    public final String getNotificationDisplayName() {
      return this.notificationDisplayName;
    }
    public final Direction getDirection() {
      return this.direction;
    }
    public CallInvite getCallInvite() {
      return this.callInvite;
    }
    public CallInviteState getCallInviteState() {
      return this.callInviteState;
    }
    public CancelledCallInvite getCancelledCallInvite() {
      return this.cancelledCallInvite;
    }
    public Promise getCallAcceptedPromise() {
      return this.callAcceptedPromise;
    }
    public Promise getCallRejectedPromise() {
      return this.callRejectedPromise;
    }
    public CallException getCallException() {
      return this.callException;
    }
    public String getCallRecipient() { return this.callRecipient; }
    public boolean isLightweightNotification() {
      return this.lightweightNotification;
    }
    public void setLightweightNotification(final boolean lightweight) {
      this.lightweightNotification = lightweight;
    }
    public boolean isRingSilenced() {
      return this.ringSilenced;
    }
    public void setRingSilenced(final boolean ringSilenced) {
      this.ringSilenced = ringSilenced;
    }
    public void setNotificationId(int notificationId) {
      this.notificationId = notificationId;
    }
    public void setTimestamp(Date timestamp) {
      this.timestamp = timestamp;
    }
    public void setCall(@NonNull Call voiceCall) {
      this.callSid = voiceCall.getSid();
      this.voiceCall = voiceCall;
    }
    public void setCallInviteUsedState() {
      this.callInviteState = (this.callInviteState == ACTIVE) ? USED : this.callInviteState;
    }
    public void setCancelledCallInvite(@NonNull CancelledCallInvite cancelledCallInvite) {
      this.callSid = cancelledCallInvite.getCallSid();
      this.cancelledCallInvite = cancelledCallInvite;
      this.callInvite = null;
      this.callInviteState = NONE;
    }
    public void setCallAcceptedPromise(@NonNull Promise callAcceptedPromise) {
      this.callAcceptedPromise = callAcceptedPromise;
    }
    public void setCallRejectedPromise(@NonNull Promise callRejectedPromise) {
      this.callRejectedPromise = callRejectedPromise;
    }
    public void setCallException(CallException callException) {
      this.callException = callException;
    }
    @Override
    public boolean equals(Object obj) {
      return (obj instanceof CallRecord) && comparator(this, (CallRecord)obj);
    }
  }
  private final List<CallRecord> callRecordList = new Vector<>();

  public void add(final CallRecord callRecord) {
    callRecordList.add(callRecord);
  }
  public void clear() {
    callRecordList.clear();
  }

  public CallRecord get(final CallRecord record) {
    try {
      return callRecordList.get(callRecordList.indexOf(record));
    } catch (IndexOutOfBoundsException e) {
      return null;
    }
  }
  public CallRecord remove(final CallRecord record) {
    try {
      return callRecordList.remove(callRecordList.indexOf(record));
    } catch (IndexOutOfBoundsException e) {
      return null;
    }
  }
  public Collection<CallRecord> getCollection() {
    return callRecordList;
  }
  // Close patch: active-call queries. getCollection() exposes the
  // live Vector, whose iterators are fail-fast and throw CME when a Twilio SDK
  // thread (incoming/onDisconnected) structurally modifies it mid-iteration.
  // These helpers iterate a snapshot, so callers don't each repeat that guard.
  public boolean hasActiveCall() {
    return !activeCalls().isEmpty();
  }
  public boolean hasActiveCallOtherThan(final CallRecord exclude) {
    return !activeCallsOtherThan(exclude).isEmpty();
  }
  // Records with a live (non-disconnected) voice Call. A CallInvite still
  // ringing has no voice Call yet, so it is not "active" here.
  public List<CallRecord> activeCalls() {
    final List<CallRecord> active = new ArrayList<>();
    for (final CallRecord record : new ArrayList<>(callRecordList)) {
      final Call call = record.getVoiceCall();
      if (null != call && Call.State.DISCONNECTED != call.getState()) {
        active.add(record);
      }
    }
    return active;
  }
  public List<CallRecord> activeCallsOtherThan(final CallRecord exclude) {
    final List<CallRecord> active = activeCalls();
    active.removeIf(record -> record.equals(exclude));
    return active;
  }
  // Close patch: records with an invite that is still ringing -- not
  // yet answered, not rejected, not cancelled. These are the calls that still
  // own an incoming-call notification.
  public List<CallRecord> pendingInviteCalls() {
    final List<CallRecord> pending = new ArrayList<>();
    for (final CallRecord record : new ArrayList<>(callRecordList)) {
      if (null == record.getVoiceCall()
          && null != record.getCallInvite()
          && ACTIVE == record.getCallInviteState()) {
        pending.add(record);
      }
    }
    return pending;
  }
  // Close patch: the pending invites whose ring should be audible --
  // everything above, minus the ones deliberately silenced. Iteration order is
  // arrival order, so firstRingingCall() is the one that has been ringing
  // longest. VoiceService.syncRinger() reconciles the ringer against this.
  public List<CallRecord> ringingCalls() {
    final List<CallRecord> ringing = pendingInviteCalls();
    ringing.removeIf(CallRecord::isRingSilenced);
    return ringing;
  }
  public boolean hasRingingCall() {
    return !ringingCalls().isEmpty();
  }
  public CallRecord firstRingingCall() {
    final List<CallRecord> ringing = ringingCalls();
    return ringing.isEmpty() ? null : ringing.get(0);
  }
  private static boolean comparator(@NonNull final CallRecord lhs, @NonNull final CallRecord rhs) {
    if (null != lhs.uuid && null != rhs.uuid) {
      return lhs.uuid.equals(rhs.uuid);
    } else if (null != lhs.callSid && null != rhs.callSid) {
      return lhs.callSid.equals(rhs.callSid);
    }
    return false;
  }
}
