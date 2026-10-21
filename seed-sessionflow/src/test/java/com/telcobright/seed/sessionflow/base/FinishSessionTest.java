package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.TaskState;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.SessionSupervisor;
import com.telcobright.statewalk.session.SessionTimings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §G · (g) finishSession, from both finals, once: the teardown, the slot, the CDR per tier (one message, leaf first), the task closed,
 * the SDR, the application's close — then statewalk's ritual resets the supervisor, the signaling machine and the budget child to IDLE
 * and returns them to their pools. An answered session that ends with no ServiceEnd (ACTIVE's maximum) is settled, never released (D-A1).
 */
class FinishSessionTest {

    private Lab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    @Test
    void theCdrPerTier_oneMessage_leafFirst_withTheHierarchyAndEachTiersCharge() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of("res_44_7", "3.00", "res_44", "2.00", "btcl", "1.00"), Lab.hops("carrier-a"), Set.of()));
        lab.dial("g-1", Lab.BTCL_DOOR, 3);
        lab.answer("g-1");
        lab.hangup("g-1");

        assertThat(lab.cdr.of("g-1")).hasSize(1);
        List<CdrEvent> records = lab.cdr.of("g-1").get(0).tiers();
        assertThat(records).extracting(r -> r.tenant + "|" + r.inPartnerId + "|" + r.inPartnerCost.toPlainString())
            .containsExactly("res_44_7|3|3.00", "res_44|7|2.00", "btcl|44|1.00");
        assertThat(records.get(0).resellerHierarchy).isEqualTo("btcl > res_44 > res_44_7");
        assertThat(records).extracting(r -> r.callId).containsOnly("g-1");
        assertThat(records).extracting(r -> r.hangupCause).containsOnly("NORMAL_CLEARING");
        assertThat(records.get(0).answerTime).isNotNull();
    }

    @Test
    void theTaskIsClosedOnce_completedOnSuccess_failedOtherwise_andTheSdrWritten() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected String buildTask(LabContext ctx) {
                super.buildTask(ctx);
                ctx.task = CampaignTask.minted(ctx.sessionId(), "btcl", CampaignKind.VOICE, "8801711000000", null, null, null, Instant.now(), Map.of()).processing();
                return null;
            }
        });
        lab.dial("t-ok", Lab.BTCL_DOOR, 1);
        lab.answer("t-ok");
        lab.hangup("t-ok");
        lab.dial("t-ko", Lab.BTCL_DOOR, 1);
        lab.fail("t-ko", "UNALLOCATED_NUMBER", 404);

        assertThat(lab.closedTasks).extracting(t -> t.uniqueId() + ":" + t.state() + ":" + t.endCause())
            .containsExactly("t-ok:" + TaskState.SENT + ":NORMAL_CLEARING", "t-ko:" + TaskState.FAILED + ":UNALLOCATED_NUMBER");
        assertThat(lab.sdrs).containsExactly("sdr:t-ok:SUCCEEDED:NORMAL_CLEARING", "sdr:t-ko:FAILED:UNALLOCATED_NUMBER");
    }

    @Test
    void theTeardownAndTheClose_runOnce_andAFailingStepStopsNoOther() {
        AtomicInteger teardowns = new AtomicInteger(), ended = new AtomicInteger();
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected void onTeardown(LabContext ctx, Outcome outcome) { teardowns.incrementAndGet(); throw new IllegalStateException("scripted"); }
            @Override protected List<CdrEvent> cdrPerTier(LabContext ctx, Outcome outcome) { throw new IllegalStateException("scripted CDR fault"); }
            @Override protected void onEnded(LabContext ctx, Outcome outcome) { ended.incrementAndGet(); }
        });
        lab.dial("f-1", Lab.BTCL_DOOR, 1);
        lab.answer("f-1");
        lab.hangup("f-1");

        assertThat(teardowns.get()).isEqualTo(1);
        assertThat(ended.get()).isEqualTo(1);
        assertThat(lab.sdrs).hasSize(1);
        assertThat(lab.ctx("f-1").history.snapshot()).anyMatch(r -> r.isNote() && r.cause().contains("PUBLISH_CDR threw"));
        assertThat(lab.ledger.openReserves()).isZero();
    }

    @Test
    void everyMachineGoesBackToItsPool_andTheNextSessionReusesIt() {
        lab = new Lab();
        for (String id : List.of("p-1", "p-2", "p-3")) {
            lab.dial(id, Lab.BTCL_DOOR, 1);
            lab.answer(id);
            lab.hangup(id);
        }
        var supervisors = lab.engine.registry().poolStatistics(lab.flow.name());
        var signaling = lab.engine.registry().poolStatistics(LabCall.TYPE);
        assertThat(lab.engine.registry().activeCellCount()).isZero();
        assertThat(supervisors.available()).isEqualTo(supervisors.totalCreated());
        assertThat(signaling.available()).isEqualTo(signaling.totalCreated());
        assertThat(lab.kit.slots().held()).isZero();
        assertThat(lab.kit.counters().ended.get()).isEqualTo(3);
    }

    @Test
    void anAnsweredSessionCutByActivesMaximum_isStoppedOnTheWire_andSettledNeverReleased_DA1() throws Exception {
        RoutesphereSettings shortActive = new RoutesphereSettings(8, 2, 120, new SessionTimings(2, 2, 5, 1), 0, 0, 0, false, 200);
        lab = new Lab(shortActive);
        lab.dial("m-1", Lab.BTCL_DOOR, 5);
        lab.answer("m-1");
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until && lab.engine.isLive("m-1")) Thread.sleep(20);

        LabContext ctx = lab.ctx("m-1");
        assertThat(ctx.outcome).isEqualTo(Outcome.FAILED);
        assertThat(ctx.endCause).isEqualTo(SessionSupervisor.MAX_DURATION_REACHED);
        assertThat(lab.ledger.count("settle")).as("settled on its real time").isEqualTo(2);
        assertThat(lab.ledger.count("release")).isZero();
        assertThat(lab.cdr.of("m-1").get(0).tiers()).extracting(r -> r.hangupCause).containsOnly(SessionSupervisor.MAX_DURATION_REACHED);
    }
}
