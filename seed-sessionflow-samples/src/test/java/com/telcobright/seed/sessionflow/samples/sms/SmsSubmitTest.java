package com.telcobright.seed.sessionflow.samples.sms;

import com.telcobright.seed.sessionflow.samples.bed.Bed;
import com.telcobright.seed.sessionflow.samples.bed.Script;
import com.telcobright.seed.sessionflow.samples.sms.SmsSubmit.SmsTimings;
import com.telcobright.statewalk.session.Verdict;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** The SmsSubmit machine ALONE under the minimal handler (P1 §H item 2): submit → receipt through a fake client door and SMPP bind. */
class SmsSubmitTest {

    static final SmsTimings LONG = new SmsTimings(5_000, 5_000, 60_000);
    static final SmsTimings SHORT = new SmsTimings(5_000, 300, 300);
    static final Smsc SMSC_1 = new Smsc("smsc-1", 5, "10.10.199.30:2775"), SMSC_2 = new Smsc("smsc-2", 6, "10.10.199.31:2775");

    private final List<Bed<?, ?>> open = new ArrayList<>();
    private final List<SmsSubmitContext> machines = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() { open.forEach(Bed::close); }

    private Bed<SmsOut, SmsFrame> bed(String name, SmsTimings t, Script script) {
        Bed<SmsOut, SmsFrame> b = new Bed<>(name, script, SmsSubmit.spec(t, this::newMachineContext), new SmsChannel(), SmsFrames::decode);
        open.add(b);
        return b;
    }

    private SmsSubmitContext newMachineContext() { SmsSubmitContext c = new SmsSubmitContext(); machines.add(c); return c; }

    private static SmsFrame request(String text) { return SmsFrame.http("10.10.188.7:7480", "unilever", "unilever-pw", "BRAND", "8801711000002", text); }

    @Test
    void a_message_submitted_accepted_and_delivered_then_the_pooled_machines_are_clean() throws Exception {
        Bed<SmsOut, SmsFrame> b = bed("sms-1", LONG, new Script().plan(SMSC_1));
        b.frame("m-1", request("x".repeat(200)));                              // two segments
        assertEquals("CONNECTING/SUBMITTING", b.states("m-1"));
        assertEquals(List.of("smsc:submit_sm smsc-1 BRAND>8801711000002 segments=2"), b.wire("m-1"));
        SmsFacts facts = (SmsFacts) b.script.last().facts;
        assertEquals("listen", facts.ingressKind());
        assertEquals("10.10.188.7:7480", facts.ingressMatch(), "the ingress identity of the HTTP door");
        assertEquals(2, facts.segments());

        b.frame("m-1", SmsFrame.submitOk("msg-9"));
        assertEquals("ACTIVE/SUBMITTED", b.states("m-1"), "the SMSC's acceptance is the answer; ACTIVE waits for the receipt");
        assertEquals("smsc-1/msg-9", b.script.last().answer.remoteParty());
        b.frame("m-1", SmsFrame.dlr("DELIVRD"));
        assertEquals("-/-", b.states("m-1"));
        assertEquals(List.of("smsc:submit_sm smsc-1 BRAND>8801711000002 segments=2", "client:ACCEPTED msg-9", "smsc:deliver_sm_resp", "client:DLR DELIVRD"),
            b.wire("m-1"));
        List<String> tail = b.script.from("onEnd:");
        assertEquals("onEnd:DELIVERED:NETWORK", tail.get(0));
        assertTrue(tail.get(1).startsWith("settle:SUCCEEDED:"), tail.toString());
        assertEquals("record:SUCCEEDED:DELIVERED", tail.get(2));
        assertEquals(List.of("IDLE", "INIT", "SUBMITTING", "SUBMITTED", "TERMINATED"), machines.get(0).road);
        b.assertBackInThePools("m-1");

        b.frame("m-2", SmsFrame.kafka("sms_in_btcl", 701, "BRAND", "8801711000003", "hi"));   // the Kafka door, one segment
        assertEquals("topic", ((SmsFacts) b.script.last().facts).ingressKind());
        assertEquals(List.of("IDLE", "INIT", "SUBMITTING"), machines.get(1).road);
        assertNull(machines.get(1).messageId, "nothing of m-1 on m-2's machine");
    }

    @Test
    void a_receipt_that_is_not_a_delivery_still_ends_the_service_settled_with_its_cause() throws Exception {
        Bed<SmsOut, SmsFrame> b = bed("sms-2", LONG, new Script().plan(SMSC_1));
        b.frame("m-u", request("hello"));
        b.frame("m-u", SmsFrame.submitOk("msg-1"));
        b.frame("m-u", SmsFrame.dlr("UNDELIV"));
        assertEquals("record:SUCCEEDED:UNDELIVERED", last(b), "answered → settled: the flow's refund rule decides the charge");
        assertTrue(b.script.calls.contains("onEnd:UNDELIVERED:NETWORK"));
        assertFalse(b.script.calls.contains("release"));
    }

    @Test
    void a_refused_submit_fails_over_to_the_next_smsc_on_the_same_machine() throws Exception {
        Bed<SmsOut, SmsFrame> b = bed("sms-3", LONG, new Script().plan(SMSC_1, SMSC_2).on("THROTTLED", Script.OnFailure.REROUTE));
        b.frame("m-f", request("hello"));
        b.frame("m-f", SmsFrame.submitError(0x58));                             // smsc-1 throttles
        assertEquals("CONNECTING/SUBMITTING", b.states("m-f"));
        assertEquals(List.of("smsc:submit_sm smsc-1 BRAND>8801711000002 segments=1", "smsc:submit_sm smsc-2 BRAND>8801711000002 segments=1"), b.wire("m-f"));
        assertEquals(2, b.script.last().attempts);
        b.frame("m-f", SmsFrame.submitOk("msg-2"));
        b.frame("m-f", SmsFrame.dlr("DELIVRD"));
        assertEquals("record:SUCCEEDED:DELIVERED", last(b));
        assertEquals(List.of("IDLE", "INIT", "SUBMITTING", "INIT", "SUBMITTING", "SUBMITTED", "TERMINATED"), machines.get(0).road);
    }

    @Test
    void a_terminal_refusal_rejects_the_client_with_the_smscs_cause_and_releases() throws Exception {
        Bed<SmsOut, SmsFrame> b = bed("sms-4", LONG, new Script().plan(SMSC_1, SMSC_2));
        b.frame("m-t", request("hello"));
        b.frame("m-t", SmsFrame.submitError(0x0B));
        assertEquals("-/-", b.states("m-t"));
        assertEquals("client:REJECTED INVALID_DESTINATION", b.wire("m-t").get(1));
        assertEquals(List.of("onFailed:INVALID_DESTINATION:11", "release", "record:FAILED:INVALID_DESTINATION"), b.script.from("onFailed:"));
    }

    @Test
    void a_refused_admission_answers_the_client_and_releases() throws Exception {
        Script s = new Script().plan(SMSC_1);
        s.verdict = Verdict.reject("PARTNER_NOT_FOUND");
        Bed<SmsOut, SmsFrame> b = bed("sms-5", LONG, s);
        b.frame("m-x", request("hello"));
        assertEquals(List.of("client:REJECTED PARTNER_NOT_FOUND"), b.wire("m-x"));
        assertEquals(List.of("onStart:m-x", "admit", "release", "record:FAILED:PARTNER_NOT_FOUND"), b.script.calls);
        b.assertBackInThePools("m-x");
    }

    @Test
    void no_submit_answer_fails_and_no_receipt_ends_the_wait_settled() throws Exception {
        Bed<SmsOut, SmsFrame> b = bed("sms-6", SHORT, new Script().plan(SMSC_1));
        b.frame("m-s", request("hello"));                                       // the SMSC never answers the submit
        b.awaitGone("m-s", 3_000);
        assertEquals("record:FAILED:SUBMIT_TIMEOUT", last(b));
        assertEquals("client:FAILED SUBMIT_TIMEOUT", b.wire("m-s").get(1));

        b.frame("m-d", request("hello"));                                       // accepted, never a receipt
        b.frame("m-d", SmsFrame.submitOk("msg-3"));
        b.awaitGone("m-d", 3_000);
        assertTrue(b.script.calls.contains("onEnd:DLR_TIMEOUT:TIMER"), b.script.calls.toString());
        assertEquals("record:SUCCEEDED:DLR_TIMEOUT", last(b));
    }

    @Test
    void a_stop_while_waiting_for_the_receipt_cancels_the_message_at_the_smsc() throws Exception {
        Bed<SmsOut, SmsFrame> b = bed("sms-7", LONG, new Script().plan(SMSC_1));
        b.frame("m-c", request("hello"));
        b.frame("m-c", SmsFrame.submitOk("msg-4"));
        b.reg.onInboundEvent("m-c", new com.telcobright.statewalk.session.events.BudgetExhausted(System.currentTimeMillis(), "sms"));
        b.quiet("m-c");
        assertEquals("smsc:cancel_sm msg-4", b.wire("m-c").get(b.wire("m-c").size() - 1));
        assertEquals("record:SUCCEEDED:BALANCE_EXHAUSTED", last(b));
    }

    private static String last(Bed<?, ?> b) { return b.script.calls.get(b.script.calls.size() - 1); }
}
