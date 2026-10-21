package com.telcobright.seed.sessionflow.samples.sms;

import com.telcobright.seed.campaign.api.TaskState;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.samples.bed.CrashingStore;
import com.telcobright.seed.sessionflow.samples.scene.FlowBed;
import com.telcobright.seed.sessionflow.samples.scene.World;
import com.telcobright.seed.sessionflow.samples.sms.SmsSubmit.SmsTimings;
import com.telcobright.statewalk.persistence.InMemoryPersistenceProvider;
import com.telcobright.statewalk.persistence.PersistenceProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The SMS sample on the base (P1 §H): the scenarios every sample runs, in the SMS's words — the receipt, the refund by config, the failover. */
class SmsFlowTest {

    static final SmsTimings SMPP = new SmsTimings(5_000, 5_000, 10_000);
    static final SmsTimings NO_RECEIPT_300_MS = new SmsTimings(5_000, 5_000, 300);
    static final Smsc SMSC_1 = new Smsc("smsc-1", 5, "10.10.199.30:2775"), SMSC_2 = new Smsc("smsc-2", 6, "10.10.199.31:2775");
    static final Smsc CCL_SMSC = new Smsc("ccl-smsc", 15, "10.10.177.30:2775");

    static final Map<String, Integer> USERS = Map.of("btcl|direct", 9, "btcl|unilever", 701, "btcl|walton", 801, "ccl|unilever", 701);
    static final Map<String, List<Smsc>> SMSCS = Map.of("btcl", List.of(SMSC_1, SMSC_2), "ccl", List.of(CCL_SMSC));

    private final List<AutoCloseable> open = new ArrayList<>();
    private final World w = new World();

    @AfterEach
    void tearDown() throws Exception { for (int i = open.size() - 1; i >= 0; i--) open.get(i).close(); }

    private FlowBed<SmsContext, SmsOut, SmsFrame> bed(boolean refund, SmsTimings t) { return bed(refund, t, World.settings(), null); }

    private FlowBed<SmsContext, SmsOut, SmsFrame> bed(boolean refund, SmsTimings t, RoutesphereSettings s, PersistenceProvider store) {
        SmsFlow flow = new SmsFlow(w.kit(s), USERS, World.RATES, SMSCS, refund);
        FlowBed<SmsContext, SmsOut, SmsFrame> b = new FlowBed<>(flow, SmsContext::new, SmsSubmit.spec(t, SmsSubmitContext::new), new SmsChannel(),
            SmsFrames::decode, store);
        open.add(b);
        return b;
    }

    static SmsFrame http(String user, String text) { return SmsFrame.http(World.SMS_HTTP_BTCL, user, "BRAND", "8801711000002", text); }

    /** A message submitted, accepted and given its receipt. */
    static void send(FlowBed<SmsContext, SmsOut, SmsFrame> b, String id, SmsFrame request, String stat) throws Exception {
        b.frame(id, request);
        assertEquals("CONNECTING/SUBMITTING", b.states(id), b.wire(id).toString());
        b.frame(id, SmsFrame.submitOk("msg-" + id));
        b.frame(id, SmsFrame.dlr(stat));
        assertEquals("-/-", b.states(id));
    }

    private List<String> tiersOf(String id) {
        return w.cdrs.of(id).get(0).tiers().stream().map(t -> t.tenant + "/" + t.inPartnerId).toList();
    }

    private long verbs(String verb, String reference) {
        return w.ledger.journal().stream().filter(e -> e.verb().equals(verb) && e.reference().startsWith(reference)).count();
    }

    @Test
    void delivered_messages_on_one_two_and_three_tiers_pay_every_segment_at_each_tiers_rate() throws Exception {
        FlowBed<SmsContext, SmsOut, SmsFrame> b = bed(true, SMPP);
        send(b, "m-1t", http("direct", "hello"), "DELIVRD");
        send(b, "m-2t", http("unilever", "x".repeat(200)), "DELIVRD");                // two segments
        send(b, "m-3t", http("walton", "hello"), "DELIVRD");
        assertEquals(List.of("btcl/9"), tiersOf("m-1t"));
        assertEquals(List.of("res_44/701", "btcl/44"), tiersOf("m-2t"));
        assertEquals(List.of("res_44_7/801", "res_44/7", "btcl/44"), tiersOf("m-3t"));
        assertEquals(new BigDecimal("99.50"), w.balance("btcl", 9));
        assertEquals(new BigDecimal("98.80"), w.balance("res_44", 701), "two segments at 0.60");
        assertEquals(new BigDecimal("98.80"), w.balance("btcl", 44), "the reseller: 2 × 0.40 + 0.40");
        assertEquals(new BigDecimal("99.00"), w.balance("res_44_7", 801));
        assertEquals("DELIVERED", b.session("m-3t").endCause);
        assertEquals(3, w.closedTasks.size(), "every message's task closed once by the base");
        assertEquals(TaskState.SENT, w.closedTasks.get(2).state());
        assertEquals(0, w.ledger.openReserves());
        b.assertBackInThePools("m-3t");
    }

    @Test
    void two_roots_the_http_door_names_btcl_and_the_kafka_topic_names_ccl() throws Exception {
        FlowBed<SmsContext, SmsOut, SmsFrame> b = bed(true, SMPP);
        send(b, "m-btcl", http("unilever", "hi"), "DELIVRD");
        send(b, "m-ccl", SmsFrame.kafka(World.SMS_TOPIC_CCL, "unilever", "BRAND", "8801711000002", "hi"), "DELIVRD");
        assertEquals(List.of("res_44/701", "btcl/44"), tiersOf("m-btcl"));
        assertEquals(List.of("ccl_44/701", "ccl/44"), tiersOf("m-ccl"));
        assertEquals("smsc:submit_sm ccl-smsc BRAND>8801711000002 segments=1", b.wire("m-ccl").get(0), "ccl's own SMSC");
        assertEquals("cdr_ccl", w.cdrs.of("m-ccl").get(0).topic());
    }

    @Test
    void an_accepted_message_that_was_not_delivered_is_refunded_by_config_or_charged_when_the_lane_charges_it() throws Exception {
        FlowBed<SmsContext, SmsOut, SmsFrame> refunding = bed(true, SMPP);
        send(refunding, "m-ur", http("unilever", "hi"), "UNDELIV");
        assertEquals("UNDELIVERED", refunding.session("m-ur").endCause);
        assertEquals(new BigDecimal("100.00"), w.balance("res_44", 701), "refund on non-delivery: the reserve is back");

        FlowBed<SmsContext, SmsOut, SmsFrame> charging = bed(false, SMPP);
        send(charging, "m-uc", http("unilever", "hi"), "EXPIRED");
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701), "the lane charges a message it could not deliver");
        assertEquals(0, w.ledger.openReserves());
    }

    @Test
    void no_receipt_within_the_wait_is_not_a_delivery_and_takes_the_refund_rule() throws Exception {
        FlowBed<SmsContext, SmsOut, SmsFrame> b = bed(true, NO_RECEIPT_300_MS);
        b.frame("m-nr", http("unilever", "hi"));
        b.frame("m-nr", SmsFrame.submitOk("msg-nr"));
        b.awaitGone("m-nr", 3_000);
        assertEquals("DLR_TIMEOUT", b.session("m-nr").endCause);
        assertEquals(new BigDecimal("100.00"), w.balance("res_44", 701));
        assertEquals(2, verbs("settle", "m-nr"), "answered (accepted): settled at zero, not released");
    }

    @Test
    void a_throttled_smsc_fails_over_to_the_next_with_the_same_reserves_and_a_refusal_no_smsc_takes_gives_everything_back() throws Exception {
        FlowBed<SmsContext, SmsOut, SmsFrame> b = bed(true, SMPP);
        b.frame("m-fo", http("unilever", "hi"));
        b.frame("m-fo", SmsFrame.submitError(0x58));
        assertEquals("smsc:submit_sm smsc-2 BRAND>8801711000002 segments=1", b.wire("m-fo").get(1), "the failover");
        assertEquals(1, b.session("m-fo").routePlan.attempts().size(), "the refused submit recorded on the plan");
        b.frame("m-fo", SmsFrame.submitOk("msg-fo"));
        b.frame("m-fo", SmsFrame.dlr("DELIVRD"));
        assertEquals(2, verbs("reserve", "m-fo"), "the failover never re-reserves");
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701));

        b.frame("m-bad", http("unilever", "hi"));
        b.frame("m-bad", SmsFrame.submitError(0x0B));
        assertEquals("INVALID_DESTINATION", b.session("m-bad").endCause);
        assertEquals("client:REJECTED INVALID_DESTINATION", b.wire("m-bad").get(1));
        assertEquals(2, verbs("release", "m-bad"), "never accepted: every reserve back");
        assertEquals(TaskState.FAILED, w.closedTasks.get(w.closedTasks.size() - 1).state(), "its task failed");
        assertEquals("INVALID_DESTINATION", w.closedTasks.get(w.closedTasks.size() - 1).endCause());
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701));
    }

    @Test
    void refusals_at_admission_an_unknown_user_and_no_balance_at_a_middle_tier() throws Exception {
        w.ledger.fund("res_44", 7, "0.00");
        FlowBed<SmsContext, SmsOut, SmsFrame> b = bed(true, SMPP);
        b.frame("m-nf", http("nobody", "hi"));
        assertEquals("PARTNER_NOT_FOUND", b.session("m-nf").endCause);
        assertEquals(List.of("client:REJECTED PARTNER_NOT_FOUND"), b.wire("m-nf"));
        b.frame("m-mid", http("walton", "hi"));
        assertEquals("INSUFFICIENT_BALANCE", b.session("m-mid").endCause);
        assertEquals(1, verbs("release", "m-mid"), "the leaf's reserve back");
        assertEquals(new BigDecimal("100.00"), w.balance("res_44_7", 801));
        assertEquals(0, w.ledger.openReserves());
    }

    @Test
    void a_message_restored_while_it_waits_for_its_receipt_is_settled_once_on_the_next_node() throws Exception {
        InMemoryPersistenceProvider store = new InMemoryPersistenceProvider();
        CrashingStore nodeA = new CrashingStore(store);
        FlowBed<SmsContext, SmsOut, SmsFrame> a = bed(true, SMPP, World.settings(), nodeA);
        a.frame("m-r", http("unilever", "hi"));
        a.frame("m-r", SmsFrame.submitOk("msg-r"));
        assertEquals("ACTIVE/SUBMITTED", a.states("m-r"));
        nodeA.crash();

        FlowBed<SmsContext, SmsOut, SmsFrame> b = bed(true, SMPP, World.settings(), store);
        assertEquals("ACTIVE/SUBMITTED", b.states("m-r"));
        b.frame("m-r", SmsFrame.dlr("DELIVRD"));
        assertEquals("-/-", b.states("m-r"));
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701));
        assertEquals(2, verbs("settle", "m-r"));
        assertEquals("smsc:deliver_sm_resp", b.wire("m-r").get(0), "the restored machine answers the SMSC on node B");
    }
}
