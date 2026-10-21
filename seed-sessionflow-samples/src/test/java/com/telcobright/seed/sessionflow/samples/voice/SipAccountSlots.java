package com.telcobright.seed.sessionflow.samples.voice;

import java.util.HashMap;
import java.util.Map;

/**
 * The SIP accounts' concurrent calls — C4's per-SIP-account slot ({@code RETAIL_CHANNEL_LIMIT_REACHED}), shared by every call of the
 * flow like the base's channel slots: taken at the entry tier's {@code authorize}, given back once at the call's finish ({@code onEnded}).
 */
public final class SipAccountSlots {

    private final Map<String, String> accountOfCall = new HashMap<>();
    private final Map<String, Integer> active = new HashMap<>();

    /** Take one of the account's {@code cap} channels for this call; false = all taken. */
    public synchronized boolean acquire(String callId, String account, int cap) {
        if (accountOfCall.containsKey(callId)) return true;
        int now = active.getOrDefault(account, 0);
        if (now >= cap) return false;
        active.put(account, now + 1);
        accountOfCall.put(callId, account);
        return true;
    }

    /** The call ended: its channel is free again. Safe to call twice, or for a call that held none. */
    public synchronized void release(String callId) {
        String account = accountOfCall.remove(callId);
        if (account != null) active.merge(account, -1, Integer::sum);
    }

    public synchronized int activeOf(String account) { return active.getOrDefault(account, 0); }
}
