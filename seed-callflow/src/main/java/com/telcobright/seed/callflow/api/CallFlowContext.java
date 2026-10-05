package com.telcobright.seed.callflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.statewalk.session.SessionContext;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Everything ONE call knows, whatever the application. The names are {@code CallOrSmsTask}'s and
 * {@code CallSupervisorContext}'s, so a voice call, an SMS and an ad view read alike. An application extends it with
 * its own facts (a call: the SIP ids; an ad: the zone, the campaign, the content).
 *
 * <p>A context is made new for every call and is the ONLY place a call keeps state: the machine is pooled and carries
 * nothing. {@link #sessionKey} (inherited) is the call id — the CDR's {@code channelCallUuid} and the Kafka key.
 * {@link #endCause} (inherited) becomes the CDR's hangup cause.
 */
public class CallFlowContext extends SessionContext {

    // ── the request ─────────────────────────────────────────────────────────

    /** The tenant the request arrived at: a schema name ({@code btcl}, {@code res_44}). Null = the partner tells (a call). */
    public volatile String tenantName;
    /** {@code VOICE} | {@code SMS} | {@code AD} — what kind of call this is. */
    public volatile String taskType;

    // ── the task ────────────────────────────────────────────────────────────

    public volatile String originatingCallingNumber;
    public volatile String originatingCalledNumber;
    /** After the digit rules. Null = the same as the originating number. */
    public volatile String terminatingCallingNumber;
    public volatile String terminatingCalledNumber;
    /** Where the call came in (the ingress). The call summary groups by it: a small set, never an end device. */
    public volatile String callerIp;
    /** Where the call went out. */
    public volatile String receiverIp;
    public volatile String codec;

    // ── what admission found ────────────────────────────────────────────────

    /** The leaf tier: the tenant the paying partner lives in. Kept from the last candidate tried, for a failed call's CDR. */
    public volatile Tenant entryTenant;
    /** The partner that pays at the leaf tier. */
    public volatile Partner partner;
    /** One admission per tier, the leaf first — every tier reserved. Empty until a candidate was admitted. */
    public volatile List<LevelAdmission> levels = List.of();
    public volatile boolean admitted;
    /** The candidate that was admitted. -1 = none. */
    public volatile int candidateIndex = -1;
    public volatile int candidatesTried;
    /** The refusal admission saw last — the reject cause when nothing was admitted. */
    public volatile String lastRefusal;
    /** The ledger itself failed while a tier was reserved. Never a customer cause. */
    public volatile String systemFault;
    /** When this call's admission budget ends (epoch ms): past it no candidate that pays is started and the ledger is not asked. 0 = not admitting yet. */
    public volatile long admissionDeadlineMs;
    /** The admission budget ran out and a candidate that pays was not tried (or not finished) because of it. */
    public volatile boolean budgetSpent;

    // ── what routing found ──────────────────────────────────────────────────

    public volatile String incomingRoute;
    public volatile String outgoingRoute;
    /** The supplier: the partner of the outgoing route. */
    public volatile Integer outPartnerId;
    /**
     * The hops routing found, in order, and the cursor on the one being tried — what the re-route ritual (C12) advances.
     * Null = one implicit hop: a failed attempt may be retried on it, never re-routed.
     */
    public volatile RoutePlan<?> routePlan;

    // ── the life ────────────────────────────────────────────────────────────

    /** The wire the signaling speaks ({@code ESL}, {@code SMPP}, {@code HTTP}) — the first word of {@code rerouteActionFor}. Null = unnamed. */
    public volatile String protocol;
    /** The child types the application spawned for the current attempt (through {@code CallMachine.spawnChild}): a retry retires exactly these. */
    public final Set<String> spawnedChildren = ConcurrentHashMap.newKeySet();
    /** The first progress report of the signaling (ringing). 0 = none. */
    public volatile long progressAtMs;
    /** The call was answered (an SMS: delivered; an ad: shown). 0 = never — the CDR's answer time is then null. */
    public volatile long answeredAtMs;
    /** The billed duration in seconds. The settle step fixes it once; the CDR carries the same number. */
    public volatile double durationSec;

    // ── the money ───────────────────────────────────────────────────────────

    /** One settlement per tier, in the order of {@link #levels}. Empty until the reserves were closed. */
    public volatile List<TierSettlement> settlements = List.of();
    /** Every reserve of this call was settled, exactly once. */
    public volatile boolean reservesClosed;
    /** The base's own: the balance child's cut, armed for the end of a partial window (C14). Null = none armed. */
    public transient volatile ScheduledFuture<?> balanceCut;
    /** The CDR of this call went to the sink. */
    public volatile boolean cdrPublished;

    /** Every step of this call is timed and written to its history (a dry run; the debug switch does it for every call). */
    public volatile boolean traced;

    public boolean answered() { return answeredAtMs > 0; }

    /** The called number billing rates on: after the digit rules, else as dialed. */
    public String billingCalledNumber() {
        return terminatingCalledNumber != null ? terminatingCalledNumber : originatingCalledNumber;
    }
}
