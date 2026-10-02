package com.telcobright.seed.callflow.internal;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * The concurrent calls of a partner against its cap ({@code partner.maxConcurrentChannels}) — the call switch's channel
 * slot, for every application. A slot is held BY A CALL ID: the release needs no bookkeeping of reject causes, it is the
 * same one line on every end path, and a second release does nothing.
 *
 * <p>Partner ids repeat across tenants (every schema numbers its own partners), so the key is the tenant and the id.
 *
 * <p>{@link #reconcile} is the self-healing of the call switch's reconciler: a slot whose call no longer exists is
 * given back. It needs two sweeps in a row to agree, so a call that is just being admitted is never touched.
 */
public final class ChannelSlots {

    private static final Logger log = LoggerFactory.getLogger(ChannelSlots.class);

    private final ConcurrentHashMap<String, AtomicInteger> activeByPartner = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> partnerByCall = new ConcurrentHashMap<>();
    private final Set<String> suspects = ConcurrentHashMap.newKeySet();

    /** Take the partner's slot for this call. False = the cap is reached. A partner with no cap passes and holds nothing. */
    public boolean acquire(String callId, String tenantDb, Partner partner) {
        Integer cap = partner.getMaxConcurrentChannels();
        if (cap == null || cap <= 0) return true;
        String key = keyOf(tenantDb, partner.getIdPartner());
        AtomicInteger active = activeByPartner.computeIfAbsent(key, k -> new AtomicInteger());
        if (active.incrementAndGet() > cap) {
            active.decrementAndGet();
            return false;
        }
        String previous = partnerByCall.put(callId, key);
        if (previous != null) giveBack(previous);          // the call still held the slot of a candidate it dropped
        return true;
    }

    /** The call ended, or its candidate was dropped: its slot is free again. Safe to call twice, or with no slot held. */
    public void release(String callId) {
        if (callId == null) return;
        suspects.remove(callId);
        String key = partnerByCall.remove(callId);
        if (key != null) giveBack(key);
    }

    public int activeOf(String tenantDb, int partnerId) {
        AtomicInteger active = activeByPartner.get(keyOf(tenantDb, partnerId));
        return active == null ? 0 : active.get();
    }

    /** How many calls hold a slot right now. */
    public int held() { return partnerByCall.size(); }

    /**
     * Give back the slots of calls that are gone. A call must be seen gone on two sweeps in a row.
     *
     * @return how many slots this sweep gave back
     */
    public int reconcile(Predicate<String> callIsLive) {
        int healed = 0;
        for (String callId : partnerByCall.keySet()) {
            if (callIsLive.test(callId)) { suspects.remove(callId); continue; }
            if (!suspects.add(callId)) {
                log.warn("[SLOT-RECONCILER] call {} is gone and still held a channel slot of {} — given back", callId, partnerByCall.get(callId));
                release(callId);
                healed++;
            }
        }
        suspects.retainAll(partnerByCall.keySet());
        return healed;
    }

    private void giveBack(String key) {
        AtomicInteger active = activeByPartner.get(key);
        if (active != null) active.updateAndGet(n -> n > 0 ? n - 1 : 0);
    }

    private static String keyOf(String tenantDb, Integer partnerId) { return tenantDb + "#" + partnerId; }
}
