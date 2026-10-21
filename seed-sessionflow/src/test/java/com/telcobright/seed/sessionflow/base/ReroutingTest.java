package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.events.SignalingFailed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §F · (f) handleRerouting: by cause — REROUTE advances the plan while the attempts are under its cap, RETRY_SAME keeps the hop,
 * FAIL_TERMINAL ends the session; the library sends the next Connect; nothing is re-admitted or re-reserved; a retry is asked only while
 * the signaling machine lives (statewalk's CN-2). The lab's table is the call's v1 table (C12).
 */
class ReroutingTest {

    private Lab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    @Test
    void reroute_byCause_aSecondHop_onTheSameAdmission() {
        lab = new Lab();
        lab.dial("r-1", Lab.BTCL_DOOR, 5);
        lab.fail("r-1", "USER_BUSY", 486);
        lab.answer("r-1");
        lab.hangup("r-1");

        LabContext ctx = lab.ctx("r-1");
        assertThat(lab.sent("r-1")).containsExactly("INVITE carrier-a", "INVITE carrier-b");
        assertThat(ctx.outcome).isEqualTo(Outcome.SUCCEEDED);
        assertThat(ctx.attempts).isEqualTo(2);
        assertThat(ctx.routePlan.attempts()).extracting(a -> a.hopIndex() + ":" + a.cause()).containsExactly("0:USER_BUSY");
        assertThat(lab.ledger.count("reserve")).as("never re-reserved: two tiers, once each").isEqualTo(2);
        assertThat(lab.ledger.openReserves()).isZero();
    }

    @Test
    void retrySame_theSameHopAgain() {
        lab = new Lab();
        lab.dial("r-2", Lab.BTCL_DOOR, 1);
        lab.fail("r-2", "NORMAL_TEMPORARY_FAILURE", 503);
        lab.fail("r-2", "SWITCH_CONGESTION", 503);
        lab.answer("r-2");
        lab.hangup("r-2");

        assertThat(lab.sent("r-2")).containsExactly("INVITE carrier-a", "INVITE carrier-a", "INVITE carrier-a");
        assertThat(lab.ctx("r-2").outcome).isEqualTo(Outcome.SUCCEEDED);
        assertThat(lab.ledger.count("reserve")).isEqualTo(1);
    }

    @Test
    void failTerminal_endsTheSessionWithTheCause_everyReserveBack() {
        lab = new Lab();
        lab.dial("r-3", Lab.BTCL_DOOR, 3);
        lab.fail("r-3", "UNALLOCATED_NUMBER", 404);

        LabContext ctx = lab.ctx("r-3");
        assertThat(lab.sent("r-3")).containsExactly("INVITE carrier-a");
        assertThat(ctx.outcome).isEqualTo(Outcome.FAILED);
        assertThat(ctx.endCause).isEqualTo("UNALLOCATED_NUMBER");
        assertThat(ctx.failure.code()).isEqualTo(404);
        assertThat(lab.ledger.count("release")).isEqualTo(3);
        assertThat(lab.ledger.openReserves()).isZero();
    }

    @Test
    void reroute_stopsAtThePlansCap_v1sThreeAttempts() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("a", "b", "c", "d", "e"), Set.of()));
        lab.dial("r-4", Lab.BTCL_DOOR, 1);
        lab.fail("r-4", "USER_BUSY", 486);
        lab.fail("r-4", "NO_ANSWER", 480);
        lab.fail("r-4", "CALL_REJECTED", 403);

        LabContext ctx = lab.ctx("r-4");
        assertThat(lab.sent("r-4")).containsExactly("INVITE a", "INVITE b", "INVITE c");
        assertThat(ctx.outcome).isEqualTo(Outcome.FAILED);
        assertThat(ctx.endCause).isEqualTo("CALL_REJECTED");
        assertThat(ctx.attempts).isEqualTo(RoutePlan.DEFAULT_MAX_ATTEMPTS);
    }

    @Test
    void aFailureThatEndedTheSignalingMachine_failsAtOnce_theRuleIsNotAsked_CN2() {
        AtomicInteger asked = new AtomicInteger();
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a", "carrier-b"), Set.of()) {
            @Override protected boolean handleRerouting(LabContext ctx, SignalingFailed failed) { asked.incrementAndGet(); return super.handleRerouting(ctx, failed); }
        });
        lab.dial("r-5", Lab.BTCL_DOOR, 1);
        lab.cancel("r-5", "USER_BUSY");                                    // a cause the table would re-route — but the machine ended

        assertThat(lab.ctx("r-5").outcome).isEqualTo(Outcome.FAILED);
        assertThat(lab.ctx("r-5").endCause).isEqualTo("USER_BUSY");
        assertThat(asked.get()).isZero();
        assertThat(lab.sent("r-5")).containsExactly("INVITE carrier-a");
        assertThat(lab.ledger.openReserves()).isZero();
    }

    @Test
    void noRoute_refusesTheCandidate_everyReserveBack_NO_ROUTE() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), List.of(), Set.of()));
        lab.dial("r-6", Lab.BTCL_DOOR, 3);

        assertThat(lab.ctx("r-6").endCause).isEqualTo(SessionCause.NO_ROUTE);
        assertThat(lab.ledger.count("release")).isEqualTo(3);
        assertThat(lab.ledger.openReserves()).isZero();
        assertThat(lab.sent("r-6")).isEmpty();
    }
}
