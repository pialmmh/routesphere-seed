package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.samples.bed.CrashingStore;
import com.telcobright.seed.sessionflow.samples.scene.FlowBed;
import com.telcobright.seed.sessionflow.samples.scene.World;
import com.telcobright.seed.sessionflow.samples.voice.EslCall.EslTimings;
import com.telcobright.seed.sessionflow.samples.voice.VoiceFlow.DialplanRoute;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.statewalk.persistence.InMemoryPersistenceProvider;
import com.telcobright.statewalk.persistence.PersistenceProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The voice sample on the base — the ESL call's C1–C23 (routesphere {@code call-flow-base-pseudocode.md} §1) as scenarios: each test
 * names the rows it covers. The report's regression table maps every row to its test.
 */
class VoiceFlowTest {

    static final EslTimings ESL = new EslTimings(5_000, 8_000, 60_000, 3_600_000, 300);
    static final EslTimings RINGS_300_MS = new EslTimings(5_000, 8_000, 300, 3_600_000, 300);

    static final Map<String, Integer> PARTNERS = Map.of(
        "btcl|10.0.0.9", 9, "btcl|10.0.0.7", 701, "btcl|10.0.0.8", 801, "btcl|10.0.0.2", 702, "btcl|10.0.0.3", 703,
        "btcl|10.0.0.4", 704, "ccl|10.0.0.7", 701, "ccl|10.0.0.9", 9);

    static final Map<String, List<DialplanRoute>> ROUTES = Map.of(
        "btcl", List.of(
            new DialplanRoute("017", new EslTarget("GP-trunk", 5, "10.10.199.20", "external", null, "rs-out")),
            new DialplanRoute("017", new EslTarget("GP-backup", 6, "10.10.199.21", "external", null, "rs-out")),
            new DialplanRoute("017", new EslTarget("GP-third", 7, "10.10.199.22", "external", null, "rs-out")),
            new DialplanRoute("017", new EslTarget("GP-never-reached", 8, "10.10.199.23", "external", null, "rs-out")),
            new DialplanRoute("018", new EslTarget("Robi-trunk", 9, "10.10.199.24", "external", null, "rs-out"))),
        "ccl", List.of(new DialplanRoute("017", new EslTarget("CCL-GP", 15, "10.10.177.20", "external", null, "ccl-out"))));

    private final List<AutoCloseable> open = new ArrayList<>();
    private final World w = new World();

    @AfterEach
    void tearDown() throws Exception { for (int i = open.size() - 1; i >= 0; i--) open.get(i).close(); }

    private FlowBed<VoiceContext, EslOut, EslFrame> bed(RoutesphereSettings s, EslTimings t) { return bed(s, t, null); }

    private FlowBed<VoiceContext, EslOut, EslFrame> bed(RoutesphereSettings s, EslTimings t, PersistenceProvider store) {
        VoiceFlow flow = new VoiceFlow(w.kit(s), PARTNERS, World.RATES, ROUTES);
        FlowBed<VoiceContext, EslOut, EslFrame> b = new FlowBed<>(flow, VoiceContext::new, EslCall.spec(t, EslCallContext::new), new EslChannel(),
            EslFrames::decode, store);
        open.add(b);
        return b;
    }

    static EslFrame park(String uuid, String esl, String sourceIp, String called) {
        return EslFrame.park(uuid, esl, "01711000001", called, sourceIp, "1001");
    }

    /** A whole answered call: park, answer, a little talk, the caller's hangup, the hangup complete. */
    static void talk(FlowBed<VoiceContext, EslOut, EslFrame> b, String uuid, String esl, String sourceIp) throws Exception {
        b.frame(uuid, park(uuid, esl, sourceIp, "01711000002"));
        assertEquals("CONNECTING/TRYING", b.states(uuid), "admitted, routed, the parked leg transferred");
        b.frame(uuid, EslFrame.answer(uuid, uuid + "-b", "PCMA"));
        assertEquals("ACTIVE/ANSWERED", b.states(uuid));
        Thread.sleep(20);
        b.frame(uuid, EslFrame.hangup(uuid, "NORMAL_CLEARING"));
        b.frame(uuid, EslFrame.hangupComplete(uuid, "NORMAL_CLEARING"));
        assertEquals("-/-", b.states(uuid));
    }

    private List<String> tiersOf(String uuid) {
        RecordingCdrSink.Published p = w.cdrs.of(uuid).get(0);
        return p.tiers().stream().map(t -> t.tenant + "/" + t.inPartnerId).toList();
    }

    private long verbs(String verb, String reference) {
        return w.ledger.journal().stream().filter(e -> e.verb().equals(verb) && e.reference().startsWith(reference)).count();
    }

    // ── C1 C2 C3 C6 C13 C16 C17 C18 C19 · one, two and three tiers ─────────────────────────────────────

    @Test
    void one_two_and_three_tiers_each_reserve_one_minute_at_their_own_rate_and_the_record_has_one_line_per_tier_leaf_first() throws Exception {
        FlowBed<VoiceContext, EslOut, EslFrame> b = bed(World.settings(), ESL);

        talk(b, "c-1t", World.ESL_BTCL, "10.0.0.9");                              // DirectCo: btcl only
        talk(b, "c-2t", World.ESL_BTCL, "10.0.0.7");                              // Unilever: res_44 → btcl
        talk(b, "c-3t", World.ESL_BTCL, "10.0.0.8");                              // Walton: res_44_7 → res_44 → btcl

        assertEquals(List.of("btcl/9"), tiersOf("c-1t"));
        assertEquals(List.of("res_44/701", "btcl/44"), tiersOf("c-2t"), "C3: above the leaf res_44 → partner 44 of btcl");
        assertEquals(List.of("res_44_7/801", "res_44/7", "btcl/44"), tiersOf("c-3t"), "three tiers, leaf first");
        assertEquals("cdr_btcl", w.cdrs.of("c-3t").get(0).topic(), "the CDR on the root's road");

        assertEquals(new BigDecimal("99.50"), w.balance("btcl", 9), "C17: one started minute at 0.50");
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701));
        assertEquals(new BigDecimal("99.00"), w.balance("res_44_7", 801));
        assertEquals(new BigDecimal("99.20"), w.balance("res_44", 7));
        assertEquals(new BigDecimal("99.20"), w.balance("btcl", 44), "the reseller paid the operator for both of its tiers' calls: 0.40 × 2");
        assertEquals(0, w.ledger.openReserves(), "every reserve settled");
        assertEquals(1, verbs("reserve", "c-1t"), "C6: one minute reserved per tier");
        assertEquals(3, verbs("reserve", "c-3t"));
        assertEquals(3, verbs("settle", "c-3t"), "C17: every tier settled once");
        VoiceContext three = b.session("c-3t");
        assertTrue(three.recordingStarted, "C13: the answer started the recording");
        assertTrue(three.durationSec > 0, "C16: the billed duration fixed once");
        assertEquals(3, three.settlements.size(), "C18: the per-tier settlements copied");
        b.assertBackInThePools("c-3t");
    }

    // ── §C · two roots, the same partner ids ───────────────────────────────────────────────────────────────

    @Test
    void two_roots_with_partner_701_in_both_each_call_walks_only_its_own_roots_tree() throws Exception {
        FlowBed<VoiceContext, EslOut, EslFrame> b = bed(World.settings(), ESL);
        talk(b, "c-btcl", World.ESL_BTCL, "10.0.0.7");
        talk(b, "c-ccl", World.ESL_CCL, "10.0.0.7");

        assertEquals("btcl", b.session("c-btcl").root);
        assertEquals("ccl", b.session("c-ccl").root);
        assertEquals(List.of("res_44/701", "btcl/44"), tiersOf("c-btcl"));
        assertEquals(List.of("ccl_44/701", "ccl/44"), tiersOf("c-ccl"), "the same partner id, the other root's tiers");
        assertEquals("cdr_ccl", w.cdrs.of("c-ccl").get(0).topic());
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701));
        assertEquals(new BigDecimal("99.30"), w.balance("ccl_44", 701));
        assertEquals(new BigDecimal("99.60"), w.balance("btcl", 44));
        assertEquals(new BigDecimal("99.55"), w.balance("ccl", 44));
        assertTrue(b.wire("c-ccl").contains("uuid_transfer c-ccl 8801711000002 XML ccl-out"), "the ccl root's own dialplan: " + b.wire("c-ccl"));
    }

    @Test
    void a_door_no_rule_names_is_nobodys_and_nothing_is_looked_up() throws Exception {
        FlowBed<VoiceContext, EslOut, EslFrame> b = bed(World.settings(), ESL);
        b.frame("c-x", park("c-x", World.NOBODYS, "10.0.0.7", "01711000002"));
        assertEquals("-/-", b.states("c-x"));
        assertEquals("NO_TENANT", b.session("c-x").endCause);
        assertNull(b.session("c-x").root, "never a default root");
        assertEquals(List.of("uuid_kill c-x CALL_REJECTED"), b.wire("c-x"));
        assertTrue(w.ledger.journal().isEmpty(), "nothing reserved");
        b.assertBackInThePools("c-x");
    }

    // ── C1 C4 C7 C5 · refusals at admission ────────────────────────────────────────────────────────────────

    @Test
    void refusals_at_admission_partner_not_found_deactivated_digit_filter_and_no_balance_at_a_middle_tier_give_every_reserve_back() throws Exception {
        w.ledger.fund("res_44", 7, "0.00");                                         // the sub-reseller has nothing at its parent
        FlowBed<VoiceContext, EslOut, EslFrame> b = bed(World.settings(), ESL);

        b.frame("c-nf", park("c-nf", World.ESL_BTCL, "10.9.9.9", "01711000002"));
        assertEquals("PARTNER_NOT_FOUND", b.session("c-nf").endCause, "C1");
        b.frame("c-da", park("c-da", World.ESL_BTCL, "10.0.0.4", "01711000002"));
        assertEquals("PARTNER_DEACTIVATED", b.session("c-da").endCause, "C4: the status before any reserve");
        b.frame("c-df", park("c-df", World.ESL_BTCL, "10.0.0.7", "00441234567"));
        assertEquals("DIGIT_FILTER_DENIED", b.session("c-df").endCause, "C5: at the root, before the root's reserve");
        b.frame("c-mb", park("c-mb", World.ESL_BTCL, "10.0.0.8", "01711000002"));
        assertEquals("INSUFFICIENT_BALANCE", b.session("c-mb").endCause, "C6/C7: the middle tier cannot pay");

        assertEquals(0, verbs("reserve", "c-nf") + verbs("reserve", "c-da"), "nothing reserved for nobody or a closed partner");
        assertEquals(1, verbs("reserve", "c-df"), "the leaf had reserved");
        assertEquals(1, verbs("release", "c-df"), "C7: and got it back");
        assertEquals(1, verbs("reserve", "c-mb"), "the leaf of the three tiers reserved");
        assertEquals(1, verbs("refused", "c-mb"), "the middle tier refused");
        assertEquals(1, verbs("release", "c-mb"), "C7: the leaf's reserve back");
        assertEquals(new BigDecimal("100.00"), w.balance("res_44_7", 801));
        assertEquals(new BigDecimal("100.00"), w.balance("res_44", 701));
        assertEquals(0, w.ledger.openReserves());
        for (String id : List.of("c-nf", "c-da", "c-df", "c-mb")) {
            assertEquals("-/-", b.states(id));
            assertEquals("uuid_kill " + id + " CALL_REJECTED", b.wire(id).get(b.wire(id).size() - 1), "C20: the parked leg killed");
        }
        b.assertBackInThePools("c-mb");
    }

    // ── C8 C9 C10 · routing ────────────────────────────────────────────────────────────────────────────────

    @Test
    void the_dialplan_of_the_root_routes_by_prefix_and_the_transfer_carries_the_digit_filtered_number_a_number_no_route_serves_is_no_route() throws Exception {
        FlowBed<VoiceContext, EslOut, EslFrame> b = bed(World.settings(), ESL);
        b.frame("c-r", park("c-r", World.ESL_BTCL, "10.0.0.7", "01711000002"));
        assertEquals(List.of("uuid_setvar c-r origination_caller_id_number 01711000001", "uuid_setvar c-r origination_caller_id_name 01711000001",
            "uuid_setvar c-r effective_caller_id_number 01711000001", "uuid_setvar c-r effective_caller_id_name 01711000001",
            "uuid_setvar c-r target_ip 10.10.199.20", "uuid_setvar c-r sofia_profile external", "uuid_transfer c-r 8801711000002 XML rs-out"),
            b.wire("c-r"), "C9/C10: the hop the plan points at, the digit-filtered number");
        assertEquals("GP-trunk", b.session("c-r").outgoingRoute, "C8");
        assertEquals(4, b.session("c-r").routePlan.hopCount(), "every route of the prefix, in order");

        b.frame("c-nr", park("c-nr", World.ESL_BTCL, "10.0.0.7", "01911000002"));
        assertEquals("NO_ROUTE", b.session("c-nr").endCause);
        assertEquals(2, verbs("release", "c-nr"), "a session no route serves gives both tiers' reserves back");
    }

    // ── C12 · rerouting ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void a_busy_hop_reroutes_to_the_next_a_temporary_failure_retries_the_same_hop_and_the_reserves_stay_as_they_were() throws Exception {
        FlowBed<VoiceContext, EslOut, EslFrame> b = bed(World.settings(), ESL);
        String id = "c-rr";
        b.frame(id, park(id, World.ESL_BTCL, "10.0.0.7", "01711000002"));
        b.frame(id, EslFrame.bridgeFailed(id, "USER_BUSY"));                     // GP-trunk busy → REROUTE
        assertEquals("CONNECTING/TRYING", b.states(id));
        b.frame(id, EslFrame.bridgeFailed(id, "NORMAL_TEMPORARY_FAILURE"));      // GP-backup congested → RETRY_SAME
        assertEquals("CONNECTING/TRYING", b.states(id));
        List<String> transfers = b.wire(id).stream().filter(l -> l.startsWith("uuid_setvar " + id + " target_ip")).toList();
        assertEquals(List.of("uuid_setvar c-rr target_ip 10.10.199.20", "uuid_setvar c-rr target_ip 10.10.199.21", "uuid_setvar c-rr target_ip 10.10.199.21"),
            transfers, "the next hop, then the same hop again");
        assertEquals(3, b.session(id).attempts);
        assertEquals(2, b.session(id).routePlan.attempts().size(), "every failed attempt recorded");
        assertEquals(2, verbs("reserve", id), "a retry never re-admits, never re-reserves");

        b.frame(id, EslFrame.answer(id, "b-x", "PCMA"));
        b.frame(id, EslFrame.hangup(id, "NORMAL_CLEARING"));
        b.frame(id, EslFrame.hangupComplete(id, "NORMAL_CLEARING"));
        assertEquals(2, verbs("settle", id));
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701), "one minute, once");
    }

    @Test
    void reroutes_stop_at_the_plans_cap_of_three_attempts_and_the_last_cause_reaches_the_caller() throws Exception {
        FlowBed<VoiceContext, EslOut, EslFrame> b = bed(World.settings(), ESL);
        String id = "c-cap";
        b.frame(id, park(id, World.ESL_BTCL, "10.0.0.7", "01711000002"));
        b.frame(id, EslFrame.bridgeFailed(id, "USER_BUSY"));
        b.frame(id, EslFrame.bridgeFailed(id, "USER_BUSY"));
        b.frame(id, EslFrame.bridgeFailed(id, "USER_BUSY"));
        assertEquals("-/-", b.states(id), "three attempts made: no fourth");
        assertEquals("USER_BUSY", b.session(id).endCause);
        assertEquals(3, b.session(id).attempts);
        assertFalse(b.wire(id).stream().anyMatch(l -> l.contains("10.10.199.23")), "GP-never-reached was never tried");
        assertEquals("uuid_kill " + id + " USER_BUSY", b.wire(id).get(b.wire(id).size() - 1));
        assertEquals(2, verbs("release", id), "C20: every reserve back");
    }

    // ── C11 · the no-answer deadline lives in the ESL machine ───────────────────────────────────────────────

    @Test
    void the_ringing_deadline_of_the_esl_machine_fails_the_call_and_every_reserve_goes_back() throws Exception {
        FlowBed<VoiceContext, EslOut, EslFrame> b = bed(World.settings(), RINGS_300_MS);
        String id = "c-na";
        b.frame(id, park(id, World.ESL_BTCL, "10.0.0.7", "01711000002"));
        b.frame(id, EslFrame.progress(id));
        b.awaitGone(id, 3_000);
        assertEquals("NO_ANSWER", b.session(id).endCause);
        assertEquals(2, verbs("release", id));
        assertEquals(0, w.ledger.openReserves());
        assertTrue(b.session(id).progressAtMs > 0, "the PDD stamped at the first progress");
    }

    // ── C13 C14 · renewals and exhaustion ─────────────────────────────────────────────────────────────────

    @Test
    void a_talking_call_renews_one_minute_per_period_and_when_the_tightest_tier_can_pay_no_more_it_is_stopped_and_settled() throws Exception {
        w.ledger.fund("res_44", 701, "1.80");                                      // three minutes at 0.60
        FlowBed<VoiceContext, EslOut, EslFrame> b = bed(World.renewing(1), ESL);
        String id = "c-bud";
        b.frame(id, park(id, World.ESL_BTCL, "10.0.0.7", "01711000002"));
        b.frame(id, EslFrame.answer(id, "b-bud", "PCMA"));
        long until = System.currentTimeMillis() + 8_000;
        while (!b.wire(id).contains("uuid_kill " + id + " CALL_REJECTED") && System.currentTimeMillis() < until) Thread.sleep(20);
        assertEquals("ACTIVE/DISCONNECTING", b.states(id), "BudgetExhausted → Stop(BALANCE_EXHAUSTED) → uuid_kill: " + b.wire(id));
        assertTrue(verbs("reserve", id + "#L0") >= 3, "C14: the leaf renewed its minute every period: " + w.ledger.journal());

        b.frame(id, EslFrame.hangupComplete(id, "CALL_REJECTED"));
        assertEquals("-/-", b.states(id));
        assertEquals("BALANCE_EXHAUSTED", b.session(id).endCause);
        assertEquals(new BigDecimal("1.20"), w.balance("res_44", 701), "C17: one started minute charged, the renewed windows back");
        assertEquals(0, w.ledger.openReserves());
        b.assertBackInThePools(id);
    }

    // ── C23 · ACTIVE's own deadline ────────────────────────────────────────────────────────────────────────

    @Test
    void the_maximum_duration_stops_the_answered_call_kills_its_legs_and_it_is_settled_never_released() throws Exception {
        FlowBed<VoiceContext, EslOut, EslFrame> b = bed(World.settings(1), ESL);
        String id = "c-max";
        b.frame(id, park(id, World.ESL_BTCL, "10.0.0.7", "01711000002"));
        b.frame(id, EslFrame.answer(id, "b-max", "PCMA"));
        b.awaitGone(id, 4_000);
        assertEquals("MAX_DURATION_REACHED", b.session(id).endCause);
        assertTrue(b.wire(id).contains("uuid_kill " + id + " NORMAL_TEMPORARY_FAILURE"), "Stop down: both legs killed " + b.wire(id));
        assertEquals(2, verbs("settle", id), "answered: settled on its real time");
        assertEquals(0, verbs("release", id));
        CdrEvent leaf = w.cdrs.of(id).get(0).tiers().get(0);
        assertEquals("MAX_DURATION_REACHED", leaf.hangupCause);
    }

    // ── C4 C19 · the partner's channel slot ───────────────────────────────────────────────────────────────

    @Test
    void a_partner_with_one_channel_takes_one_call_at_a_time_and_the_slot_is_given_back_once_at_the_end() throws Exception {
        FlowBed<VoiceContext, EslOut, EslFrame> b = bed(World.settings(), ESL);
        b.frame("c-s1", park("c-s1", World.ESL_BTCL, "10.0.0.3", "01711000002"));
        b.frame("c-s1", EslFrame.answer("c-s1", "b-s1", "PCMA"));
        b.frame("c-s2", park("c-s2", World.ESL_BTCL, "10.0.0.3", "01711000003"));
        assertEquals("CHANNEL_LIMIT_REACHED", b.session("c-s2").endCause);
        b.frame("c-s1", EslFrame.hangup("c-s1", "NORMAL_CLEARING"));
        b.frame("c-s1", EslFrame.hangupComplete("c-s1", "NORMAL_CLEARING"));
        b.frame("c-s3", park("c-s3", World.ESL_BTCL, "10.0.0.3", "01711000004"));
        assertEquals("CONNECTING/TRYING", b.states("c-s3"), "the slot came back with the end of c-s1");
    }

    // ── a crash mid-call, and a crash between the settle and the final row (§L.1) ──────────────────────────

    @Test
    void a_call_restored_from_the_store_mid_call_finishes_on_the_next_node_and_every_tier_is_settled_once() throws Exception {
        InMemoryPersistenceProvider store = new InMemoryPersistenceProvider();
        CrashingStore nodeA = new CrashingStore(store);
        FlowBed<VoiceContext, EslOut, EslFrame> a = bed(World.settings(), ESL, nodeA);
        String id = "c-crash";
        a.frame(id, park(id, World.ESL_BTCL, "10.0.0.7", "01711000002"));
        a.frame(id, EslFrame.answer(id, "b-crash", "PCMA"));
        assertEquals("ACTIVE/ANSWERED", a.states(id));
        nodeA.crash();

        FlowBed<VoiceContext, EslOut, EslFrame> b = bed(World.settings(), ESL, store);
        assertEquals("ACTIVE/ANSWERED", b.states(id), "restored with its machine");
        VoiceContext restored = b.registry().supervisorContextOf(id);
        assertEquals("btcl", restored.root);
        assertEquals(2, restored.tiers.size(), "the reserves' pictures came back with the row");
        b.frame(id, EslFrame.hangup(id, "NORMAL_CLEARING"));
        b.frame(id, EslFrame.hangupComplete(id, "NORMAL_CLEARING"));
        assertEquals("-/-", b.states(id));
        assertEquals(2, verbs("settle", id), "every tier settled once, on node B");
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701));
        assertEquals(new BigDecimal("99.60"), w.balance("btcl", 44));
        assertEquals(0, w.ledger.openReserves());
        assertEquals(List.of("res_44/701", "btcl/44"), tiersOf(id));
    }

    @Test
    void a_crash_between_the_settle_and_the_final_row_ends_the_call_again_and_nothing_moves_twice() throws Exception {
        InMemoryPersistenceProvider store = new InMemoryPersistenceProvider();
        CrashingStore nodeA = new CrashingStore(store).dieBeforeTheFinalRowOf("c-twice");
        RoutesphereSettings activeOneSecond = World.settings(1);
        FlowBed<VoiceContext, EslOut, EslFrame> a = bed(activeOneSecond, ESL, nodeA);
        String id = "c-twice";
        a.frame(id, park(id, World.ESL_BTCL, "10.0.0.7", "01711000002"));
        a.frame(id, EslFrame.answer(id, "b-twice", "PCMA"));
        a.frame(id, EslFrame.hangup(id, "NORMAL_CLEARING"));
        a.frame(id, EslFrame.hangupComplete(id, "NORMAL_CLEARING"));
        assertTrue(nodeA.isDead(), "settled and recorded, then the node died writing the final row");
        assertEquals(2, verbs("settle", id));
        BigDecimal afterTheFirstEnd = w.balance("res_44", 701);

        FlowBed<VoiceContext, EslOut, EslFrame> b = bed(activeOneSecond, ESL, store);
        b.awaitGone(id, 4_000);                                                     // the restored session ends AGAIN
        assertEquals(4, w.ledger.timesAsked("settle"), "the base asked the ledger to settle both tiers a second time");
        assertEquals(2, verbs("settle", id), "§L.1: but the ledger moved the money once per (session, tier)");
        assertEquals(afterTheFirstEnd, w.balance("res_44", 701));
        assertEquals(2, w.cdrs.of(id).size(), "the CDR was offered twice");
        assertEquals(2, w.cdrs.records().stream().filter(r -> id.equals(r.channelCallUuid) || id.equals(r.callId)).count(),
            "billing keeps one record per (tenant, call): " + w.cdrs.duplicates() + " dropped");
        assertEquals(2, w.cdrs.duplicates());
    }
}
