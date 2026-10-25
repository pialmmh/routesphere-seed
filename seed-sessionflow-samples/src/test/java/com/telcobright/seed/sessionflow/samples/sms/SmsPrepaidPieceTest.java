package com.telcobright.seed.sessionflow.samples.sms;

import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.samples.scene.FlowBed;
import com.telcobright.seed.sessionflow.samples.scene.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1-E item 9 · the prepaid piece (R12), the SMS's case: "for some applications, e.g. SMS, finalize just returns the balance for a failed
 * message" — the base's one finalize with the lane's refund rule: a message no SMSC took, and an accepted one not delivered (refund by
 * config), give their whole reserve back at every tier; nothing in the lane is money code.
 */
class SmsPrepaidPieceTest {

    private final List<AutoCloseable> open = new ArrayList<>();
    private final World w = new World();

    @AfterEach
    void tearDown() throws Exception { for (AutoCloseable c : open) c.close(); }

    private FlowBed<SmsContext, SmsOut, SmsFrame> bed() {
        RoutesphereFlow<SmsContext> flow = SmsFlow.of(w.kit(World.settings()), World.RATES, SmsFlowTest.SMSCS, true);
        FlowBed<SmsContext, SmsOut, SmsFrame> b = new FlowBed<>(c -> new SmsSupervisor(flow, c), SmsContext::new,
            SmsSubmit.spec(SmsFlowTest.SMPP, SmsSubmitContext::new), new SmsChannel(), SmsFrames::decode);
        open.add(b);
        return b;
    }

    @Test
    void aFailedMessage_returnsItsWholeReserve_atEveryTier() throws Exception {
        FlowBed<SmsContext, SmsOut, SmsFrame> b = bed();
        b.frame("m-f", SmsFlowTest.http("walton", "hi"));                      // three tiers: res_44_7/801, res_44/7, btcl/44
        b.frame("m-f", SmsFrame.submitError(0x0B));                              // no SMSC takes it
        assertEquals(new BigDecimal("100.00"), w.balance("res_44_7", 801));
        assertEquals(new BigDecimal("100.00"), w.balance("res_44", 7));
        assertEquals(new BigDecimal("100.00"), w.balance("btcl", 44));
        assertEquals(0, w.ledger.openReserves());
    }

    @Test
    void anAcceptedMessageNotDelivered_returnsItsWholeReserve_byTheLanesRefundRule() throws Exception {
        FlowBed<SmsContext, SmsOut, SmsFrame> b = bed();
        b.frame("m-u", SmsFlowTest.http("walton", "hi"));
        b.frame("m-u", SmsFrame.submitOk("msg-u"));
        b.frame("m-u", SmsFrame.dlr("UNDELIV"));
        assertEquals(new BigDecimal("100.00"), w.balance("res_44_7", 801));
        assertEquals(new BigDecimal("100.00"), w.balance("btcl", 44));
        assertEquals(0, w.ledger.openReserves());
    }
}
