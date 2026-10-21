package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.TierLine;
import com.telcobright.statewalk.persistence.SnapshotSerializer;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.SessionSupervisor;
import com.telcobright.statewalk.session.events.Established;
import com.telcobright.statewalk.session.events.SessionStart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §B · The base on statewalk 4.0's final supervisor: the library keeps the states, RoutesphereFlow implements every handler call and asks
 * the application through the owner's methods. One session through the whole graph; one refused; the context through statewalk's own
 * mapper and back (the store's road).
 */
class RoutesphereFlowLifecycleTest {

    private final Lab lab = new Lab();

    @AfterEach
    void close() { lab.close(); }

    @Test
    void aSessionRunsTheWholeGraph_threeTiersReservedThenSettled_oneCdrLeafFirst_theSdr_everyPoolBack() {
        lab.dial("s-1", Lab.BTCL_DOOR, 3);
        assertThat(lab.engine.stateOf("s-1")).isEqualTo(SessionSupervisor.CONNECTING);
        assertThat(lab.sent("s-1")).containsExactly("INVITE carrier-a");
        LabContext ctx = lab.ctx("s-1");
        assertThat(ctx.root).isEqualTo("btcl");
        assertThat(ctx.tiers).extracting(t -> t.dbName + "/" + t.partnerId + "/" + t.debitReference)
            .containsExactly("res_44_7/3/s-1#L0", "res_44/7/s-1#L1", "btcl/44/s-1#L2");
        assertThat(lab.ledger.openReserves()).isEqualTo(3);

        lab.answer("s-1");
        assertThat(lab.engine.stateOf("s-1")).isEqualTo(SessionSupervisor.ACTIVE);
        lab.hangup("s-1");

        assertThat(lab.engine.isLive("s-1")).isFalse();
        assertThat(ctx.outcome).isEqualTo(Outcome.SUCCEEDED);
        assertThat(ctx.settlements).extracting(s -> s.charged().toPlainString()).containsExactly("1.00", "1.00", "1.00");
        assertThat(lab.ledger.openReserves()).isZero();
        assertThat(lab.ledger.balanceOf("res_44_7", 3)).isEqualByComparingTo("99.00");
        List<CdrEvent> records = lab.cdr.of("s-1").get(0).tiers();
        assertThat(lab.cdr.of("s-1")).hasSize(1);
        assertThat(records).extracting(r -> r.tenant).containsExactly("res_44_7", "res_44", "btcl");
        assertThat(records).extracting(r -> r.hangupCause).containsOnly("NORMAL_CLEARING");
        assertThat(lab.sdrs).containsExactly("sdr:s-1:SUCCEEDED:NORMAL_CLEARING");
        assertThat(lab.engine.registry().activeCellCount()).isZero();
    }

    @Test
    void aSessionWhosePartnerIsNotInItsTree_isRefused_releasedOfNothing_andRecordedOnItsRoot() {
        lab.dial("s-2", Lab.BTCL_DOOR, 999);

        LabContext ctx = lab.ctx("s-2");
        assertThat(lab.engine.isLive("s-2")).isFalse();
        assertThat(ctx.outcome).isEqualTo(Outcome.FAILED);
        assertThat(ctx.endCause).isEqualTo(SessionCause.PARTNER_NOT_FOUND);
        assertThat(lab.ledger.openReserves()).isZero();
        assertThat(lab.cdr.of("s-2").get(0).tiers()).extracting(r -> r.tenant + ":" + r.hangupCause).containsExactly("btcl:PARTNER_NOT_FOUND");
        assertThat(lab.engine.registry().activeCellCount()).isZero();
    }

    @Test
    void theContext_goesThroughStatewalksOwnMapper_andBack_idsAndAmountsOnly() {
        LabContext ctx = new LabContext();
        ctx.facts = new LabFacts("s-3", 1L, Lab.BTCL_DOOR, 3, "88017");
        ctx.root = "btcl";
        ctx.treeVersion = 7;
        ctx.candidates = List.of(Candidate.SELF);
        ctx.chosen = Candidate.SELF;
        ctx.routePlan = RoutePlan.of(Lab.hops("carrier-a", "carrier-b"));
        ctx.routePlan.advance();
        ctx.routePlan.record("USER_BUSY", 5L);
        ctx.answer = new Established(9L, "carrier-b", "PCMA", null);
        TierLine line = new TierLine();
        line.levelIndex = 0; line.dbName = "res_44_7"; line.partnerId = 3; line.debitReference = "s-3#L0";
        line.totalReserved = new BigDecimal("1.00"); line.reservedAmount = new BigDecimal("1.00"); line.reservationCount = 1; line.rate = new BigDecimal("1.00");
        ctx.tiers = List.of(line);
        ctx.task = com.telcobright.seed.campaign.api.CampaignTask.minted("s-3", "btcl", com.telcobright.seed.campaign.api.CampaignKind.AD, "8801711000000",
            "dhaka-zone", "site-7", "app-1", java.time.Instant.ofEpochMilli(1_760_000_000_123L), java.util.Map.of("rule", "R100", "slot", 2)).processing();

        String row = SnapshotSerializer.contextToBase64Json(ctx);
        LabContext back = (LabContext) SnapshotSerializer.contextFromBase64Json(row, LabContext.class.getName());

        assertThat(back.sessionId()).isEqualTo("s-3");
        assertThat(back.root).isEqualTo("btcl");
        assertThat(back.treeVersion).isEqualTo(7);
        assertThat(back.chosen).isEqualTo(Candidate.SELF);
        assertThat(back.routePlan.current()).isEqualTo(new LabHop("carrier-b"));
        assertThat(back.routePlan.attempts()).hasSize(1);
        assertThat(back.answer).isEqualTo(ctx.answer);
        assertThat(back.tiers).singleElement().satisfies(t -> {
            assertThat(t.debitReference).isEqualTo("s-3#L0");
            assertThat(t.totalReserved).isEqualByComparingTo("1.00");
        });
        assertThat(back.task).as("the task row travels as its picture (the Instants as millis) and comes back the same").isEqualTo(ctx.task);
        assertThat(back.tree).as("the root's snapshot is never persisted").isNull();
        assertThat(back.levels).as("the live tiers are never persisted").isEmpty();
        assertThat(java.util.Base64.getDecoder().decode(row).length).as("a small row (§L.6)").isLessThan(4096);
    }

    @Test
    void theHandler_isCallableAlone_onStartFindsTheRoot_admitWalksTheTiers() {
        LabContext ctx = new LabContext();
        ctx.facts = new LabFacts("s-4", 1L, Lab.TELE2_DOOR, 3, "88017");
        RoutesphereFlow<LabContext> flow = lab.flow;

        flow.onStart(ctx, new SessionStart(ctx.facts));
        ctx.candidates = List.of(Candidate.SELF);
        assertThat(flow.admit(ctx).accepted()).isTrue();

        assertThat(ctx.root).isEqualTo("tele2");
        assertThat(ctx.tiers).extracting(t -> t.dbName + "/" + t.partnerId).containsExactly("res_9/3", "tele2/9");
        flow.release(ctx);
        assertThat(lab.ledger.openReserves()).isZero();
    }
}
