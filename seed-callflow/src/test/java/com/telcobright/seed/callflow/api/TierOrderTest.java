package com.telcobright.seed.callflow.api;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.dependencies.CallFlowKit;
import com.telcobright.seed.callflow.samples.Scene;
import com.telcobright.seed.callflow.samples.VoiceFlow;
import com.telcobright.seed.callflow.spi.TenantLookup;
import com.telcobright.seed.callflow.testkit.InMemoryLedger;
import com.telcobright.seed.callflow.testkit.RecordingCdrSink;
import com.telcobright.seed.callflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.pipeline.StepMode;
import com.telcobright.statewalk.session.AdmissionVerdict;
import com.telcobright.statewalk.session.TransitionRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * B8 (CALL-0002 §1 Q3, §4): the call switch's order at a tier, word for word — {@code DefaultAdmissionService.admitAtLevel}:
 *
 * <pre>
 *   PARTNER_NOT_FOUND → PARTNER_DEACTIVATED (a status set and not ACTIVE) → INVALID_DID (no slot taken) → the partner's slot,
 *   CHANNEL_LIMIT_REACHED (the leaf) → RETAIL_CHANNEL_LIMIT_REACHED (the SIP account, after the slot; not in a dry run)
 *   → (the root) DIGIT_FILTER_DENIED → the reserve; a refusal gives back everything taken before it
 * </pre>
 *
 * The sample voice flow says the call's two checks around the slot: the calling DID must be the partner's (before), the SIP
 * account's own cap (after). One test per cause, in that order.
 */
class TierOrderTest {

    private static final String OWN_DID = "01711000001";
    private static final String FOREIGN_DID = "09999999999";

    private final Scene scene = new Scene();

    /** The sample voice call with the switch's two checks around the slot. The account is the caller's number. */
    static final class OrderedVoice extends VoiceFlow {
        final Map<Integer, Set<String>> didsOfPartner = Map.of(701, Set.of(OWN_DID), 702, Set.of(OWN_DID), 703, Set.of(OWN_DID), 704, Set.of(OWN_DID));
        final ConcurrentHashMap<String, AtomicInteger> accountActive = new ConcurrentHashMap<>();
        final Set<String> accountHeldBy = ConcurrentHashMap.newKeySet();
        volatile int accountCap = Integer.MAX_VALUE;

        OrderedVoice(CallFlowKit kit, Map<String, Integer> bySource, Map<String, BigDecimal> rates) {
            super(kit, bySource, rates, List.of(new VoiceFlow.Route("017", "GP-trunk", 5), new VoiceFlow.Route("00", "IGW", 5)));
        }

        /** Before the slot: a client must present a DID it owns. Only at the entry tier, as CheckAuthorizationStep. */
        @Override
        protected String authorizeBeforeSlot(Call call, Tenant tier, Partner partner, int levelIndex) {
            if (levelIndex != 0) return null;
            return didsOfPartner.getOrDefault(partner.getIdPartner(), Set.of()).contains(call.caller) ? null : "INVALID_DID";
        }

        /** After the slot: the SIP account's own cap. Only at the entry tier, and never in a dry run. */
        @Override
        protected String authorize(Call call, Tenant tier, Partner partner, int levelIndex) {
            if (levelIndex != 0 || call.dryRun) return null;
            AtomicInteger active = accountActive.computeIfAbsent(call.caller, k -> new AtomicInteger());
            if (active.incrementAndGet() > accountCap) {
                active.decrementAndGet();
                return "RETAIL_CHANNEL_LIMIT_REACHED";
            }
            accountHeldBy.add(call.sessionKey);
            return null;
        }

        @Override
        protected void onEnded(Call call, String outcome) {
            if (accountHeldBy.remove(call.sessionKey)) accountActive.get(call.caller).decrementAndGet();
        }

        int accountActive(String account) { AtomicInteger a = accountActive.get(account); return a == null ? 0 : a.get(); }
    }

    private OrderedVoice voice() {
        return new OrderedVoice(scene.kit(Scene.settings(8)),
            Map.of("10.0.0.7", 701, "10.0.0.3", 703, "10.0.0.2", 702, "10.0.0.4", 704),
            Map.of("res_44#701", new BigDecimal("0.60"), "res_44#702", new BigDecimal("0.60"), "res_44#703", new BigDecimal("0.60"),
                "res_44#704", new BigDecimal("0.60"), "btcl#44", new BigDecimal("0.40")));
    }

    private static VoiceFlow.Call call(String id, String sourceIp, String caller, String dialed) {
        VoiceFlow.Call call = Scene.call(id, sourceIp, dialed);
        call.caller = caller;
        return call;
    }

    /** The base's own slots of a flow (package-private on CallFlow; the sample sits in another package). */
    private static com.telcobright.seed.callflow.internal.ChannelSlots slotsOf(CallFlow<?> flow) { return flow.slots(); }

    private static AdmissionVerdict admit(VoiceFlow flow, VoiceFlow.Call call) {
        assertThat(flow.preprocess(call)).isNull();
        return flow.admission(call, StepMode.LIVE);
    }

    private Partner partner(String tenantDb, int id) {
        Tenant tenant = scene.kit(Scene.settings(8)).tenants().tenantOfPartner("btcl", id).filter(t -> t.getDbName().equals(tenantDb)).orElseThrow();
        return tenant.getContext().getPartners().get(id);
    }

    // ── PARTNER_DEACTIVATED: v2's rule, first ───────────────────────────────

    @Test
    void aStatusWordOtherThanActive_isPartnerDeactivated_asV2_beforeAnythingIsTaken() {
        partner("res_44", 701).setStatus("SUSPENDED");
        OrderedVoice voice = voice();
        VoiceFlow.Call call = call("o-1", "10.0.0.7", OWN_DID, "01712345678");

        AdmissionVerdict verdict = admit(voice, call);

        assertThat(verdict.rejectCause()).isEqualTo(CallCause.PARTNER_DEACTIVATED);
        assertThat(scene.ledger.journal()).isEmpty();
        assertThat(slotsOf(voice).held()).isZero();
        assertThat(call.partner.getIdPartner()).as("the partner is kept for the reject record").isEqualTo(701);
    }

    @Test
    void noStatus_andActiveInAnyCase_pass() {
        OrderedVoice voice = voice();
        partner("res_44", 701).setStatus(null);
        assertThat(admit(voice, call("o-2", "10.0.0.7", OWN_DID, "01712345678")).accepted()).isTrue();

        partner("res_44", 701).setStatus("active");
        assertThat(admit(voice, call("o-3", "10.0.0.7", OWN_DID, "01712345678")).accepted()).isTrue();
    }

    @Test
    void deactivated_comesBeforeTheDidCheck() {
        OrderedVoice voice = voice();

        AdmissionVerdict verdict = admit(voice, call("o-4", "10.0.0.4", FOREIGN_DID, "01712345678"));        // 704 "Closed": deactivated AND a foreign DID

        assertThat(verdict.rejectCause()).isEqualTo(CallCause.PARTNER_DEACTIVATED);
    }

    // ── INVALID_DID before the slot, CHANNEL_LIMIT_REACHED at the slot ──────

    @Test
    void aPartnerAtItsCap_presentingAForeignDid_isInvalidDid_notChannelLimitReached() {
        OrderedVoice voice = voice();
        assertThat(admit(voice, call("o-5", "10.0.0.3", OWN_DID, "01712345678")).accepted()).isTrue();       // 703 "OneLine": cap 1, now taken
        assertThat(slotsOf(voice).activeOf("res_44", 703)).isEqualTo(1);

        AdmissionVerdict foreign = admit(voice, call("o-6", "10.0.0.3", FOREIGN_DID, "01712345678"));
        AdmissionVerdict own = admit(voice, call("o-7", "10.0.0.3", OWN_DID, "01712345678"));

        assertThat(foreign.rejectCause()).as("the DID is checked before the channel is counted").isEqualTo("INVALID_DID");
        assertThat(own.rejectCause()).isEqualTo(CallCause.CHANNEL_LIMIT_REACHED);
        assertThat(slotsOf(voice).activeOf("res_44", 703)).as("neither refusal took the slot").isEqualTo(1);
        assertThat(scene.ledger.count("reserve")).as("only the first call reserved, at both tiers").isEqualTo(2);
    }

    // ── RETAIL_CHANNEL_LIMIT_REACHED after the slot ─────────────────────────

    @Test
    void theAccountsCap_afterTheSlot_isRetailChannelLimitReached_andTheSlotTakenBeforeItGoesBack() {
        OrderedVoice voice = voice();
        voice.accountCap = 1;
        partner("res_44", 701).setField2(5);                                                                 // a partner cap, so the slot is counted
        VoiceFlow.Call first = call("o-8", "10.0.0.7", OWN_DID, "01712345678");
        assertThat(admit(voice, first).accepted()).isTrue();
        assertThat(slotsOf(voice).activeOf("res_44", 701)).isEqualTo(1);

        AdmissionVerdict second = admit(voice, call("o-9", "10.0.0.7", OWN_DID, "01712345678"));

        assertThat(second.rejectCause()).isEqualTo("RETAIL_CHANNEL_LIMIT_REACHED");
        assertThat(slotsOf(voice).activeOf("res_44", 701)).as("the second call's partner slot was taken, then given back").isEqualTo(1);
        assertThat(voice.accountActive(OWN_DID)).isEqualTo(1);
        voice.close(first, CallState.FAILED, Scene.NO_MACHINE);
        assertThat(slotsOf(voice).activeOf("res_44", 701)).isZero();
        assertThat(voice.accountActive(OWN_DID)).isZero();
    }

    @Test
    void inADryRun_theAccountIsNotCounted_andNoSlotIsTaken() {
        OrderedVoice voice = voice();
        voice.accountCap = 0;
        partner("res_44", 701).setField2(5);
        VoiceFlow.Call dry = call("o-10", "10.0.0.7", OWN_DID, "01712345678");

        DryRun result = voice.simulate(dry);

        assertThat(result.admitted()).isTrue();
        assertThat(slotsOf(voice).held()).isZero();
        assertThat(voice.accountActive(OWN_DID)).isZero();
    }

    // ── the root's rules after the leaf reserved; the reserve last ──────────

    @Test
    void theRootsDigitFilter_refusesAfterTheLeafReserved_theLeafsReserveAndTheSlotGoBack() {
        OrderedVoice voice = voice();
        partner("res_44", 701).setField2(5);
        VoiceFlow.Call call = call("o-11", "10.0.0.7", OWN_DID, "0088123456");

        AdmissionVerdict verdict = admit(voice, call);

        assertThat(verdict.rejectCause()).isEqualTo("DIGIT_FILTER_DENIED");
        assertThat(scene.ledger.journal()).extracting(InMemoryLedger.Entry::verb, InMemoryLedger.Entry::reference)
            .as("the leaf reserved; the root refused before ITS reserve; the leaf given back").containsExactly(tuple("reserve", "o-11#L0"), tuple("release", "o-11#L0"));
        assertThat(slotsOf(voice).activeOf("res_44", 701)).isZero();
        voice.close(call, CallState.FAILED, Scene.NO_MACHINE);
        assertThat(voice.accountActive(OWN_DID)).as("the account given back at the end, as v2 releases it at FAILED").isZero();
    }

    @Test
    void theReserve_isTheLastCheck_itsRefusalGivesTheSlotBack() {
        OrderedVoice voice = voice();
        partner("res_44", 702).setField2(3);

        AdmissionVerdict verdict = admit(voice, call("o-12", "10.0.0.2", OWN_DID, "01712345678"));           // 702 "Poorco": no money

        assertThat(verdict.rejectCause()).isEqualTo(CallCause.INSUFFICIENT_BALANCE);
        assertThat(slotsOf(voice).activeOf("res_44", 702)).isZero();
    }

    // ── PARTNER_NOT_FOUND above the leaf: the leaf given back ───────────────

    @Test
    void noPartnerAboveTheLeaf_isPartnerNotFound_andTheLeafsReserveGoesBack() {
        Tenant root = new TenantTreeBuilder()
            .root("btcl2").partner(9, "DirectCo", PartnerType.CUSTOMER).and()
            .tenant("res_77", "btcl2").partner(701, "Unilever", PartnerType.CUSTOMER).and()
            .build();
        InMemoryLedger ledger = new InMemoryLedger().fund("res_77", 701, "10.00");
        CallFlowKit kit = CallFlowKit.builder().tenants(TenantLookup.of(root)).ledger(ledger).cdrSink(new RecordingCdrSink())
            .zone(Scene.DHAKA).settings(Scene.settings(8)).build();
        OrderedVoice voice = new OrderedVoice(kit, Map.of("10.0.0.7", 701), Map.of("res_77#701", new BigDecimal("0.60")));
        VoiceFlow.Call call = call("o-13", "10.0.0.7", OWN_DID, "01712345678");
        call.tenantName = "btcl2";

        AdmissionVerdict verdict = admit(voice, call);

        assertThat(verdict.rejectCause()).isEqualTo(CallCause.PARTNER_NOT_FOUND);
        assertThat(ledger.journal()).extracting(InMemoryLedger.Entry::verb, InMemoryLedger.Entry::reference)
            .containsExactly(tuple("reserve", "o-13#L0"), tuple("release", "o-13#L0"));
        assertThat(ledger.balanceOf("res_77", 701)).isEqualByComparingTo("10.00");
    }

    // ── the order itself, as the trace writes it ────────────────────────────

    @Test
    void theStepsOfATwoTierCall_runInV2sOrder() {
        OrderedVoice voice = voice();
        VoiceFlow.Call call = call("o-14", "10.0.0.7", OWN_DID, "01712345678");
        call.traced = true;

        assertThat(admit(voice, call).accepted()).isTrue();

        List<String> steps = call.history.snapshot().stream().filter(TransitionRecord::isNote).map(r -> r.cause().split(" ")[0])
            .filter(s -> s.equals(s.toUpperCase()) && s.contains("_") || s.equals("RATE") || s.equals("AUTHORIZE")).toList();
        assertThat(steps).containsExactly(
            "RESOLVE_TENANT", "BUILD_TASK", "SELECT_CANDIDATES",                                 // preprocessing
            "IDENTIFY_ENTRY_PARTNER",
            "CHECK_PARTNER", "AUTHORIZE_BEFORE_SLOT", "AUTHORIZE", "RATE",                       // the leaf: the slot is taken between the two authorizations
            "IDENTIFY_PARTNER", "CHECK_PARTNER", "AUTHORIZE_BEFORE_SLOT", "AUTHORIZE", "ROOT_RULES", "RATE",   // the root: its rules before its reserve
            "RESOLVE_ROUTE", "CONFIRM_ADMISSION");
    }
}
