package com.telcobright.seed.sessionflow.base;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.api.SettleOutcome;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.dependencies.CdrSinks;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy.Rule;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.internal.BudgetChild;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.statewalk.persistence.InMemoryPersistenceProvider;
import com.telcobright.statewalk.persistence.PersistenceMode;
import com.telcobright.statewalk.persistence.SnapshotSerializer;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.events.Established;
import com.telcobright.statewalk.session.events.SessionStart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §E · (e) performCreditControl through ONE ledger port: reserveBalance per tier at admission; the budget child renews per tier while
 * ACTIVE and starts only on statewalk's SessionActive (§L.2, G-1); finalizeTier per tier in the final state — delivered: rate × billed
 * units, the rest back (negative = an extra debit); not delivered: the refund rule; never answered: every reserve back unless the refund
 * rule charges a session offered and never consumed. Money calls are idempotent per (session, tier) (§L.1): a crash between the settle and
 * the final row ends the session again from its older row, and nothing moves twice.
 */
class CreditControlTest {

    private Lab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    // ── finalizeTier ────────────────────────────────────────────────────────

    @Test
    void delivered_isChargedRateTimesBilledUnits_theShortfallDebited() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected BigDecimal billedUnits(LabContext ctx) { return new BigDecimal("3"); }
        });
        lab.dial("d-1", Lab.BTCL_DOOR, 5);
        lab.answer("d-1");
        lab.hangup("d-1");

        assertThat(lab.ctx("d-1").settlements).extracting(s -> s.charged().toPlainString() + "/" + s.returned().toPlainString())
            .containsExactly("3.00/-2.00", "3.00/-2.00");
        assertThat(lab.ledger.balanceOf("res_44", 5)).isEqualByComparingTo("97.00");
        assertThat(lab.ledger.balanceOf("btcl", 44)).isEqualByComparingTo("97.00");
    }

    @Test
    void notDelivered_isPricedByTheRefundRule_refundByDefault_chargeWhenTheLaneSaysSo() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected boolean delivered(LabContext ctx, Outcome outcome) { return !ctx.sessionId().startsWith("undelivered"); }
            @Override protected RefundPolicy refundPolicy(LabContext ctx) {
                return ctx.sessionId().endsWith("charged") ? RefundPolicy.of(Rule.CHARGE, Rule.CHARGE) : RefundPolicy.callsRule();
            }
            @Override protected SettleOutcome settleOutcome(LabContext ctx) { return SettleOutcome.FAILED; }
        });
        lab.dial("undelivered-refunded", Lab.BTCL_DOOR, 1);
        lab.answer("undelivered-refunded");
        lab.hangup("undelivered-refunded");
        lab.dial("undelivered-charged", Lab.TELE2_DOOR, 1);
        lab.answer("undelivered-charged");
        lab.hangup("undelivered-charged");

        assertThat(lab.ctx("undelivered-refunded").settlements).extracting(s -> s.charged().toPlainString()).containsExactly("0");
        assertThat(lab.ledger.balanceOf("btcl", 1)).isEqualByComparingTo("100.00");
        assertThat(lab.ctx("undelivered-charged").settlements).extracting(s -> s.charged().toPlainString()).containsExactly("1.00");
        assertThat(lab.ledger.balanceOf("tele2", 1)).isEqualByComparingTo("99.00");
        assertThat(lab.ledger.openReserves()).isZero();
    }

    @Test
    void neverAnswered_everyReserveBack_unlessTheRuleChargesASessionOfferedAndNeverConsumed() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected SettleOutcome settleOutcome(LabContext ctx) {
                return ctx.sessionId().startsWith("offered") ? SettleOutcome.OFFERED_NOT_CONSUMED : SettleOutcome.FAILED;
            }
        });
        lab.dial("failed-1", Lab.BTCL_DOOR, 3);
        lab.fail("failed-1", "CALL_REJECTED_HARD", 403);
        lab.dial("offered-1", Lab.TELE2_DOOR, 3);
        lab.fail("offered-1", "ABANDONED", 487);

        assertThat(lab.ctx("failed-1").outcome).isEqualTo(Outcome.FAILED);
        assertThat(lab.ledger.balanceOf("res_44_7", 3)).as("released").isEqualByComparingTo("100.00");
        assertThat(lab.ledger.count("release")).isEqualTo(3);
        assertThat(lab.ctx("offered-1").settlements).extracting(s -> s.charged().toPlainString()).as("the call's rule: unshown charges")
            .containsExactly("1.00", "1.00");
        assertThat(lab.ledger.balanceOf("res_9", 3)).isEqualByComparingTo("99.00");
        assertThat(lab.ledger.openReserves()).isZero();
    }

    @Test
    void aLaneAnsweringNull_settlesByTheDefaults_andNeverGoesOwed() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected SettleOutcome settleOutcome(LabContext ctx) { return null; }
            @Override protected RefundPolicy refundPolicy(LabContext ctx) { return null; }
            @Override protected BigDecimal billedUnits(LabContext ctx) { return null; }
        });
        lab.dial("n-1", Lab.BTCL_DOOR, 1);
        lab.answer("n-1");
        lab.hangup("n-1");
        lab.dial("n-2", Lab.BTCL_DOOR, 1);
        lab.fail("n-2", "X", 500);

        assertThat(lab.ctx("n-1").settlements).allMatch(s -> s.closed()).extracting(s -> s.charged().toPlainString()).containsExactly("1.00");
        assertThat(lab.ctx("n-2").settlements).allMatch(s -> s.closed()).extracting(s -> s.charged().toPlainString()).containsExactly("0");
        assertThat(lab.kit.counters().owed.get()).isZero();
        assertThat(lab.ledger.balanceOf("btcl", 1)).isEqualByComparingTo("99.00");
    }

    // ── §L.1 · idempotent per (session, tier) ──────────────────────────────

    @Test
    void aCrashBetweenTheSettleAndTheFinalRow_endsTheSessionAgainFromItsOlderRow_andNothingMovesTwice() {
        RecordingCdrSink published = new RecordingCdrSink();
        lab = new Lab();
        RoutesphereKit kit = RoutesphereKit.builder().tenants(com.telcobright.seed.sessionflow.spi.TenantLookup.of(lab.btcl, lab.tele2))
            .identification(Lab.RULES).ledger(lab.ledger).cdr(CdrSinks.oncePerSession(published, 1000))
            .zone(ZoneId.of("Asia/Dhaka")).settings(Lab.settings(0)).build();
        LabFlow flow = new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of());
        LabContext live = new LabContext();
        live.facts = new LabFacts("c-1", 1L, Lab.BTCL_DOOR, 3, "8801");
        flow.onStart(live, new SessionStart(live.facts));
        live.candidates = List.of(Candidate.SELF);
        assertThat(flow.admit(live).accepted()).isTrue();
        live.answer = new Established(System.currentTimeMillis(), "carrier-a", null, null);
        String olderRow = SnapshotSerializer.contextToBase64Json(live);            // the last row that landed: ACTIVE

        flow.settle(live, Outcome.SUCCEEDED);                                         // the final state's synchronous money and record …
        flow.record(live, Outcome.SUCCEEDED);                                         // … then the process dies before the final row lands
        BigDecimal leaf = lab.ledger.balanceOf("res_44_7", 3), root = lab.ledger.balanceOf("btcl", 44);

        LabContext restored = (LabContext) SnapshotSerializer.contextFromBase64Json(olderRow, LabContext.class.getName());
        flow.settle(restored, Outcome.SUCCEEDED);                                     // the restart ends it again
        flow.record(restored, Outcome.SUCCEEDED);

        assertThat(lab.ledger.balanceOf("res_44_7", 3)).isEqualByComparingTo(leaf);
        assertThat(lab.ledger.balanceOf("btcl", 44)).isEqualByComparingTo(root);
        assertThat(lab.ledger.count("settle")).as("money moved once per (session, tier)").isEqualTo(3);
        assertThat(lab.ledger.timesAsked("settle")).as("the second end asked again, by the same references").isEqualTo(6);
        assertThat(restored.tiers).extracting(t -> t.debitReference).containsExactly("c-1#L0", "c-1#L1", "c-1#L2");
        assertThat(published.of("c-1")).as("the CDR sink took the session once").hasSize(1);
    }

    @Test
    void theBaseSettlesOncePerContext_aSecondAskNeverReachesTheLedger() {
        lab = new Lab();
        LabContext ctx = new LabContext();
        ctx.facts = new LabFacts("o-1", 1L, Lab.BTCL_DOOR, 3, "8801");
        lab.flow.onStart(ctx, new SessionStart(ctx.facts));
        ctx.candidates = List.of(Candidate.SELF);
        assertThat(lab.flow.admit(ctx).accepted()).isTrue();
        ctx.answer = new Established(System.currentTimeMillis(), "carrier-a", null, null);

        lab.flow.settle(ctx, Outcome.SUCCEEDED);
        lab.flow.settle(ctx, Outcome.SUCCEEDED);
        lab.flow.release(ctx);

        assertThat(lab.ledger.timesAsked("settle")).as("three tiers, asked once each").isEqualTo(3);
        assertThat(lab.ledger.timesAsked("release")).isZero();
        assertThat(ctx.reservesClosed).isTrue();
    }

    // ── the budget child ────────────────────────────────────────────────────

    @Test
    void theBudgetChild_restsUntilSessionActive_thenRenewsEveryTierEveryPeriod() throws Exception {
        lab = new Lab(Lab.settings(1), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected TierRate rateNextWindow(LabContext ctx, LevelAdmission tier) { return TierRate.of(BigDecimal.ONE, BigDecimal.ONE, "BDT", "880"); }
        });
        lab.dial("b-1", Lab.BTCL_DOOR, 5);
        assertThat(lab.engine.registry().childStateOf("b-1", BudgetChild.TYPE)).isEqualTo(BudgetChild.HOLDING);
        Thread.sleep(1500);
        assertThat(lab.ctx("b-1").tiers).extracting(t -> t.reservationCount).as("no renewal before the answer").containsOnly(1);

        lab.answer("b-1");
        awaitRenewals(lab.ctx("b-1"), 3, 6000);
        lab.hangup("b-1");

        LabContext ctx = lab.ctx("b-1");
        assertThat(ctx.tiers).extracting(t -> t.reservationCount).allMatch(n -> n >= 3);
        assertThat(lab.ledger.journal()).anyMatch(e -> e.verb().equals("reserve") && e.reference().equals("b-1#L0#W2"));
        assertThat(lab.ledger.openReserves()).isZero();
        assertThat(lab.engine.registry().childStateOf("b-1", BudgetChild.TYPE)).as("the child ended with the session").isNull();
    }

    @Test
    void whenTheMoneyEnds_BudgetExhausted_theSupervisorStopsTheWire_andTheSessionIsSettledOnItsRealTime() throws Exception {
        lab = new Lab(Lab.settings(1), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected TierRate rateNextWindow(LabContext ctx, LevelAdmission tier) { return TierRate.of(new BigDecimal("40"), BigDecimal.ONE, "BDT", "880"); }
        });
        lab.dial("x-1", Lab.BTCL_DOOR, 1);
        lab.answer("x-1");
        long until = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < until && lab.engine.isLive("x-1")) Thread.sleep(50);

        LabContext ctx = lab.ctx("x-1");
        assertThat(lab.engine.isLive("x-1")).isFalse();
        assertThat(ctx.outcome).isEqualTo(Outcome.SUCCEEDED);
        assertThat(ctx.endCause).isEqualTo("BALANCE_EXHAUSTED");
        assertThat(ctx.tiers.get(0).reservationCount).as("1 + two windows of 40 out of 100").isEqualTo(3);
        assertThat(lab.ledger.openReserves()).isZero();
        assertThat(lab.engine.registry().poolStatistics(BudgetChild.TYPE).available())
            .isEqualTo(lab.engine.registry().poolStatistics(BudgetChild.TYPE).totalCreated());
    }

    @Test
    void aPartialWindow_armsTheFinalCut_andTheSessionEndsWhenThatCreditEnds_notAtTheNextTick() throws Exception {
        lab = new Lab(Lab.settings(1), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected double renewWindowSeconds(LabContext ctx, LevelAdmission tier) { return 0.3; }    // the remainder: 0.3 s of credit
        });
        lab.dial("p-1", Lab.BTCL_DOOR, 1);
        lab.answer("p-1");
        long answeredAt = System.currentTimeMillis();
        long until = answeredAt + 6000;
        while (System.currentTimeMillis() < until && lab.engine.isLive("p-1")) Thread.sleep(20);
        long endedAfterMs = System.currentTimeMillis() - answeredAt;

        LabContext ctx = lab.ctx("p-1");
        assertThat(ctx.endCause).isEqualTo("BALANCE_EXHAUSTED");
        assertThat(ctx.outcome).isEqualTo(Outcome.SUCCEEDED);
        assertThat(endedAfterMs).as("the first tick at 1 s, the cut 0.3 s later — before the second tick at 2 s").isBetween(1200L, 1950L);
    }

    @Test
    void theFirstRenewalAtTheInitialDelay_thenOnePerPeriod_B10() throws Exception {
        lab = new Lab(Lab.settings(2).withReserveInitialDelaySec(1), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected TierRate rateNextWindow(LabContext ctx, LevelAdmission tier) { return TierRate.of(BigDecimal.ONE, BigDecimal.ONE, "BDT", "880"); }
        });
        lab.dial("i-1", Lab.BTCL_DOOR, 1);
        lab.answer("i-1");
        assertThat(lab.engine.registry().childStateOf("i-1", BudgetChild.TYPE)).isEqualTo(BudgetChild.FIRST_RENEWAL);
        awaitRenewals(lab.ctx("i-1"), 2, 3000);
        assertThat(lab.engine.registry().childStateOf("i-1", BudgetChild.TYPE)).as("then one per period").isEqualTo(BudgetChild.RENEWING);
        lab.hangup("i-1");
        assertThat(lab.ctx("i-1").tiers.get(0).reservationCount).isEqualTo(2);
        assertThat(lab.ledger.openReserves()).isZero();
    }

    @Test
    void aPersistingFlowThatRenewsFasterThanAMinute_isRefusedAtStart() {
        lab = new Lab();
        RoutesphereKit kit = RoutesphereKit.builder().tenants(com.telcobright.seed.sessionflow.spi.TenantLookup.of(lab.btcl)).ledger(lab.ledger)
            .cdr((id, t) -> { }).zone(ZoneId.of("Asia/Dhaka")).settings(Lab.settings(30)).build();
        LabFlow flow = new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of());
        assertThatThrownBy(() -> RoutesphereEngine.of(new LabSupervisor(flow)).signaling(LabCall.spec()).channel(lab.wire, Lab::decode)
                .persistence(PersistenceMode.SAVE, InMemoryPersistenceProvider::new).start())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("every 30 s").hasMessageContaining("§L.2");
    }

    private static void awaitRenewals(LabContext ctx, int count, long withinMs) throws InterruptedException {
        long until = System.currentTimeMillis() + withinMs;
        while (System.currentTimeMillis() < until && ctx.tiers.stream().anyMatch(t -> t.reservationCount < count)) Thread.sleep(50);
    }
}
