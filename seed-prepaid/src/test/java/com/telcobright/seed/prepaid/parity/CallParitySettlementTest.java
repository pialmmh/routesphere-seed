package com.telcobright.seed.prepaid.parity;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.switchledger.api.Credit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.telcobright.seed.prepaid.parity.ParityScene.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity with the live statewalk-v2 call, the settlement and the roads (design §10.3 P13, P14, P16–P20): the re-rate at the end, the cost
 * per unit, the reconciliation both ways, the unanswered call, the CDR's figures, the reaper, the recharge and read roads.
 */
class CallParitySettlementTest {

    private ParityScene scene;

    @AfterEach
    void close() { if (scene != null) scene.close(); }

    /**
     * P13 · {@code BalanceBillingService.finalizeBalance} 127–143: the settlement RE-LOOKS-UP the rate on the terminating number (after
     * MNP: {@code lookupRateForSettlement} 242–269) and sets it on the level; international = the root's 117 rate at every tier
     * ({@code BalanceTracker.settleRealBilling} 182–188: "resolved once and applied identically at every level").
     */
    @Test
    @Disabled("gap: RoutesphereApp has no cost-per-tier hook (chargeAtSettle is a subclass's); the base charges the ADMISSION rate "
        + "(RoutesphereFlow.chargeAtSettle 491–496 reads tier.getRate()) — the live re-rates on the terminating number at the end")
    void P13_theSettlementReRatesOnTheTerminatingNumber_intlIsTheRootsRateAtEveryTier() {
        scene = ParityScene.open();

        CallContext ported = scene.admitted("p13-a", DIRECT, DOMESTIC);                  // held at 0.75 (8801); ported at the answer to 88018 → 0.50
        scene.answer(ported);
        scene.talk(60);
        scene.hangUp(ported);
        assertThat(ported.billingCalledNumber()).isEqualTo(PORTED);
        assertThat(ported.settlements.get(0).charged()).as("one minute at the ported number's rate").isEqualByComparingTo("0.500000");
        assertThat(ported.settlements.get(0).returned()).isEqualByComparingTo("0.25");
        assertThat(scene.balance("btcl", 91)).isEqualByComparingTo("99.50");

        CallContext abroad = scene.admitted("p13-b", UNILEVER, INTL_UK);                 // cash at both tiers, the root's 1.50 at each
        assertThat(abroad.levels).extracting(LevelAdmission::getRate).allMatch(r -> r.compareTo(new BigDecimal("1.50")) == 0);
        assertThat(abroad.levels).extracting(LevelAdmission::getPackageAccountId).containsExactly(12L, 22L);
        scene.answer(abroad);
        scene.talk(60);
        scene.hangUp(abroad);
        assertThat(abroad.settlements).extracting(s -> s.charged().toPlainString()).containsExactly("1.500000", "1.500000");
    }

    /**
     * P14 · {@code BalanceBillingService.calculateCharged} 225–240: a package minute pays seconds / 60 (six decimals); cash pays rate / 60
     * × seconds (six decimals); international first takes the matched 117 rate's minimum time and pulse ({@code IntlOutRating
     * .chargedDurationSec} 80–84: 37 s under a 30 s minimum with a 10 s pulse = 40 s).
     */
    @Test
    @Disabled("gap: the base's chargeAtSettle (RoutesphereFlow 491–496) rounds to max(2, rate.scale()) decimals — 0.62, 0.46, 1.33 — where the "
        + "live keeps six (0.616667, 0.462500, 1.333333); no cost hook in RoutesphereApp to say otherwise")
    void P14_packageMinutesPerSecond_cashRateOverSixty_intlAddsMinimumTimeAndPulse() {
        scene = ParityScene.open();

        CallContext minutes = scene.admitted("p14-a", UNILEVER, DOMESTIC);               // TF_min at both tiers
        scene.answer(minutes);
        scene.talk(37);
        scene.hangUp(minutes);
        assertThat(minutes.settlements).extracting(s -> s.charged().toPlainString()).containsExactly("0.616667", "0.616667");
        assertThat(scene.balance("res_2", 11)).isEqualByComparingTo("1.883333");

        CallContext cash = scene.admitted("p14-b", DIRECT, DOMESTIC);                    // 0.75 / 60 × 37
        scene.answer(cash);
        scene.talk(37);
        scene.hangUp(cash);
        assertThat(cash.settlements.get(0).charged()).isEqualByComparingTo("0.462500");

        CallContext abroad = scene.admitted("p14-c", DIRECT, INTL_US);                   // 1 → 2.00/min, 30 s minimum, 10 s pulse: 37 s → 40 s
        scene.answer(abroad);
        scene.talk(37);
        scene.hangUp(abroad);
        assertThat(abroad.settlements.get(0).charged()).isEqualByComparingTo("1.333333");
    }

    /**
     * P16 · {@code BalanceBillingService.settleAgainstReserve} 159–197: {@code excess = reserved − charged} is reconciled in EITHER
     * direction — positive, the rest back; negative, the shortfall debited ("the previous if (excess > 0) guard silently under-charged every
     * call that ran longer than its reserve"); it runs UNCONDITIONALLY, and the reserve row's DELETE lives inside it (the switch's
     * {@code returnBalance} 216–217).
     */
    @Test
    void P16_reconcileBothWays_theRestBack_anOverrunTaken_alwaysRuns_theRowDies() {
        scene = ParityScene.open();

        CallContext under = scene.admitted("p16-under", PACKAGES_ONLY, DOMESTIC);       // 1 held
        scene.answer(under);
        scene.talk(30);
        scene.hangUp(under);
        assertThat(under.settlements.get(0).charged()).isEqualByComparingTo("0.5");
        assertThat(under.settlements.get(0).returned()).isEqualByComparingTo("0.5");
        assertThat(scene.balance("res_2", 41)).isEqualByComparingTo("99.5");
        assertThat(scene.row("res_2", "p16-under#L0")).isNull();

        CallContext over = scene.admitted("p16-over", PACKAGES_ONLY, DOMESTIC);         // 1 held, talks 150 s with no renewal
        scene.answer(over);
        scene.talk(150);
        scene.hangUp(over);
        assertThat(over.settlements.get(0).charged()).isEqualByComparingTo("2.5");
        assertThat(over.settlements.get(0).returned()).as("the shortfall debited").isEqualByComparingTo("-1.5");
        assertThat(scene.balance("res_2", 41)).isEqualByComparingTo("97.0");
        assertThat(scene.row("res_2", "p16-over#L0")).isNull();

        CallContext exact = scene.admitted("p16-exact", PACKAGES_ONLY, DOMESTIC);       // excess exactly zero: still runs, the row still dies
        scene.answer(exact);
        scene.talk(60);
        scene.hangUp(exact);
        assertThat(exact.settlements.get(0).returned()).isEqualByComparingTo("0");
        assertThat(scene.rows("res_2")).isZero();
        assertThat(scene.recorder.seen).filteredOn(s -> s.startsWith("settle p16-exact#L0")).hasSize(1);
    }

    /**
     * P17 · {@code BalanceBillingService.refundBalance} 203–219 ({@code finalizeBalance} 145–148: not answered → the refund): an unanswered
     * call gets everything it held back, at every tier, and the rows die.
     */
    @Test
    void P17_unansweredEverythingReturned() {
        scene = ParityScene.open();
        CallContext call = scene.admitted("p17", UNILEVER, DOMESTIC);                    // 1 TF_min at the leaf, 1 at the root

        scene.giveUp(call);

        assertThat(call.settlements).extracting(s -> s.charged().toPlainString()).containsExactly("0", "0");
        assertThat(call.settlements).allSatisfy(s -> assertThat(s.returned()).isEqualByComparingTo("1.0"));
        assertThat(scene.balance("res_2", 11)).isEqualByComparingTo("2.5");
        assertThat(scene.balance("btcl", 21)).isEqualByComparingTo("10");
        assertThat(scene.rows("res_2")).isZero();
        assertThat(scene.rows("btcl")).isZero();
    }

    /**
     * P18 · {@code BalanceTracker.settleRealBilling} 190–209: one settlement per level — charged and returned — "parallel with ctx.levels,
     * carried to CDR". The seed's CDR: one record per tier, the charge as {@code packageAmount} (a package unit) or {@code inPartnerCost}
     * (cash), the account, the rate, the balance after in the record's meta; what went back is on the session's settlements.
     */
    @Test
    void P18_chargedReturnedAndBalancePerTier_reachTheCdr() {
        scene = ParityScene.open();
        CallContext call = scene.admitted("p18", UNILEVER, DOMESTIC);
        scene.answer(call);
        scene.talk(60);
        scene.hangUp(call);

        List<CdrEvent> tiers = scene.cdrs.of("p18").get(0).tiers();
        assertThat(tiers).hasSize(2);
        assertThat(tiers.get(0).packageAmount).isEqualByComparingTo("1.0");
        assertThat(tiers.get(0).inPartnerCost).isEqualByComparingTo("0");
        assertThat(tiers.get(0).inPartnerUom).isEqualTo("TF_min");
        assertThat(tiers.get(0).idPackageAccount).isEqualTo(11L);
        assertThat(tiers.get(0).callRatePerMinBDT).isEqualByComparingTo("1.0");
        assertThat((BigDecimal) tiers.get(0).meta.get("balanceAfter")).isEqualByComparingTo("1.5");
        assertThat(tiers.get(1).idPackageAccount).isEqualTo(21L);
        assertThat((BigDecimal) tiers.get(1).meta.get("balanceAfter")).isEqualByComparingTo("9");
        assertThat(call.settlements).allSatisfy(s -> assertThat(s.returned()).isEqualByComparingTo("0"));
    }

    /**
     * P19 · routesphere-core {@code pipeline/call/multilevel/ReserveReaperJob} 24–28, 40–42 and {@code PrepaidServiceWithCompensation
     * .reapOrphanReserves}: a hold row not refreshed for 30 minutes is a dead call's and is given back; a live call refreshes its row at
     * every renewal, so its row is never eligible; at most {@code BATCH_LIMIT} per pass.
     */
    @Test
    void P19_theReaperReturnsRowsNotRefreshedFor30Minutes_aLiveCallsRowIsLeft() {
        scene = ParityScene.open();
        CallContext dead = scene.admitted("p19-dead", PACKAGES_ONLY, DOMESTIC);          // its process will die: the row at T0
        CallContext live = scene.admitted("p19-live", UNILEVER, DOMESTIC);               // talks on: its row is refreshed at T0 + 20 min
        scene.answer(dead);
        scene.answer(live);
        scene.talk(20 * 60);
        assertThat(scene.renew(live)).isEqualTo(60.0);
        scene.talk(11 * 60);                                                               // T0 + 31 min

        assertThat(scene.ledger.reaper().reap("res_2")).as("the dead call's row").isEqualTo(1);

        assertThat(scene.row("res_2", "p19-dead#L0")).isNull();
        assertThat(scene.balance("res_2", 41)).isEqualByComparingTo("100");
        assertThat(scene.row("res_2", "p19-live#L0").getReserveUnit()).as("the live call's row, refreshed 11 min ago, is left").isEqualByComparingTo("2.0");
        assertThat(scene.balance("res_2", 11)).isEqualByComparingTo("0.5");
        assertThat(scene.ledger.reaper().reap("res_2")).as("a second pass finds nothing").isZero();
        assertThat(scene.ledger.reaper().reap("btcl")).as("the dead call's root row too; the live call's root row, 11 min young, is left").isEqualTo(1);
        assertThat(scene.row("btcl", "p19-live#L1")).isNotNull();
        assertThat(scene.balance("btcl", 21)).isEqualByComparingTo("8");
    }

    /**
     * P20 · routesphere-core {@code api/BillingAdminResource} 55–100 ({@code POST /api/v1/billing/recharge} →
     * {@code PrepaidServiceWithCompensation.recharge} 294–383: a DELTA through the ledger under the account's lock, an optional move onto
     * a new purchase) and the read ({@code GET /billing/balance} 106–120; {@code api/PackageAccountResource}): the next hold sees the credit.
     */
    @Test
    void P20_theRechargeAndReadRoads_theNextHoldSeesTheCredit() {
        scene = ParityScene.open();

        Credit.Credited credited = scene.ledger.credit().credit("res_2", 11, new BigDecimal("10"), "topup-1");
        assertThat(credited.before()).isEqualByComparingTo("2.5");
        assertThat(credited.after()).isEqualByComparingTo("12.5");

        CallContext call = scene.admitted("p20", UNILEVER, DOMESTIC);
        assertThat(call.levels.get(0).getBalanceBefore()).isEqualByComparingTo("12.5");
        assertThat(scene.ledger.balances().balanceOf(call.levels.get(0)).orElseThrow()).as("the read road").isEqualByComparingTo("11.5");

        Credit.Credited moved = scene.ledger.credit().reparent("res_2", 11, 99);
        assertThat(moved.purchaseId()).isEqualTo(99L);
        PackageAccount onTheLedger = scene.lab.ledger().getEntity("res_2", "PackageAccount", 11L);
        assertThat(onTheLedger.getIdpackagePurchase()).isEqualTo(99L);
        assertThat(scene.balance("res_2", 11)).isEqualByComparingTo("11.5");
    }
}
