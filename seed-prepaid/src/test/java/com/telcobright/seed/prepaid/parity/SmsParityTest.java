package com.telcobright.seed.prepaid.parity;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.telcobright.seed.prepaid.parity.ParityScene.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2a item 4 · CHARGE_NOW — the SMS's final debit (design §10.1: "charge at once, every tier, no hold row; end, success: nothing — the
 * charge stands; end, failure: refund or nothing, by the lane's config"), on the REAL switch ledger, the SMS-shaped lane ({@link SmsLane}).
 * The live it ports: routesphere-core {@code PrepaidServiceWithCompensation.debit} 441–516 (no reserve row: "a reserve exists to be settled
 * later, and nothing settles an SMS"; short → nothing taken; zero → taken without a write) and {@code BalanceCheckRule.refund} 236–255 (a
 * failed batch's charge given back as a recharge). The live charges ONLY the last tier of the chain ({@code chargeTenantChain} 187–213,
 * the others checked); the seed charges every tier as the chain walks it (the design's table) — named in the report.
 */
class SmsParityTest {

    private ParityScene scene;

    @AfterEach
    void close() { if (scene != null) scene.close(); }

    /** S1 · every tier charged at admission in the same walk, no hold row anywhere; delivered → nothing more moves, the charge on the CDR. */
    @Test
    void S1_everyTierChargedAtOnce_noHoldRow_delivered_theChargeStands() {
        scene = sms(Map.of(), true);
        CallContext sms = scene.admitted("s1", UNILEVER, DOMESTIC);

        assertThat(scene.balance("res_2", 12)).as("the leaf's cash (talk minutes never pay a message)").isEqualByComparingTo("99.75");
        assertThat(scene.balance("btcl", 22)).as("the reseller's cash at the root").isEqualByComparingTo("49.75");
        assertThat(scene.rows("res_2")).as("no hold row").isZero();
        assertThat(scene.rows("btcl")).isZero();
        assertThat(scene.recorder.seen).anyMatch(s -> s.startsWith("charge s1#L0 ")).anyMatch(s -> s.startsWith("charge s1#L1 ")).noneMatch(s -> s.startsWith("reserve"));

        deliver(sms);

        assertThat(sms.settlements).extracting(TierSettlement::charged).allMatch(c -> c.compareTo(new BigDecimal("0.25")) == 0);
        assertThat(sms.settlements).extracting(TierSettlement::returned).allMatch(r -> r.signum() == 0);
        assertThat(scene.balance("res_2", 12)).isEqualByComparingTo("99.75");
        assertThat(scene.balance("btcl", 22)).isEqualByComparingTo("49.75");
        assertThat(scene.recorder.seen).noneMatch(s -> s.startsWith("refund"));
        List<CdrEvent> tiers = scene.cdrs.of("s1").get(0).tiers();
        assertThat(tiers).extracting(t -> t.inPartnerCost).allMatch(c -> c.compareTo(new BigDecimal("0.25")) == 0);
    }

    /** S2 · failed, and the lane's rule refunds → every tier's charge comes back (BalanceCheckRule.refund 236–255). */
    @Test
    void S2_failed_refundRule_everyTierGetsItsChargeBack() {
        scene = sms(Map.of(), true);
        CallContext sms = scene.admitted("s2", UNILEVER, DOMESTIC);

        fail(sms);

        assertThat(scene.balance("res_2", 12)).isEqualByComparingTo("100");
        assertThat(scene.balance("btcl", 22)).isEqualByComparingTo("50");
        assertThat(sms.settlements).extracting(TierSettlement::charged).allMatch(c -> c.signum() == 0);
        assertThat(sms.settlements).extracting(TierSettlement::returned).allMatch(r -> r.compareTo(new BigDecimal("0.25")) == 0);
        assertThat(scene.recorder.seen).anyMatch(s -> s.startsWith("refund s2#L0 ")).anyMatch(s -> s.startsWith("refund s2#L1 "));
    }

    /** S3 · failed, and the lane's rule keeps the charge → nothing moves. */
    @Test
    void S3_failed_chargeRule_nothingMoves() {
        scene = sms(Map.of(), false);
        CallContext sms = scene.admitted("s3", UNILEVER, DOMESTIC);

        fail(sms);

        assertThat(scene.balance("res_2", 12)).isEqualByComparingTo("99.75");
        assertThat(scene.balance("btcl", 22)).isEqualByComparingTo("49.75");
        assertThat(sms.settlements).extracting(TierSettlement::charged).allMatch(c -> c.compareTo(new BigDecimal("0.25")) == 0);
        assertThat(scene.recorder.seen).noneMatch(s -> s.startsWith("refund"));
    }

    /** S4 · short → nothing taken (debit 482–483: INSUFFICIENT), the session refused in the lane's cause; the root never asked. */
    @Test
    void S4_anAccountShortOfTheMessage_isRefused_nothingTaken() {
        scene = sms(Map.of("res_2#31", "0.10"), true);

        Verdict verdict = scene.admit(scene.call("s4", CASH_ONLY, DOMESTIC));

        assertThat(verdict.cause()).isEqualTo(SessionCause.INSUFFICIENT_BALANCE);
        assertThat(scene.balance("res_2", 31)).isEqualByComparingTo("0.10");
        assertThat(scene.recorder.seen).noneMatch(s -> s.startsWith("charge s4#L1"));
    }

    /** S5 · a later tier refuses (the root's digit filter) → the earlier tier's charge is given back (the compensate rule, charged-now). */
    @Test
    void S5_aLaterTierRefuses_theEarlierChargeIsGivenBack() {
        scene = sms(Map.of(), true);

        Verdict verdict = scene.admit(scene.call("s5", UNILEVER, DENIED));

        assertThat(verdict.cause()).isEqualTo(CallLane.DIGIT_FILTER_DENIED);
        assertThat(scene.balance("res_2", 12)).isEqualByComparingTo("100");
        assertThat(scene.recorder.seen).anyMatch(s -> s.startsWith("charge s5#L0 ")).anyMatch(s -> s.startsWith("refund s5#L0 "));
    }

    /** S6 · every verb is idempotent by its reference: the same charge twice takes once; the same refund twice gives back once. */
    @Test
    void S6_theSameChargeTwiceTakesOnce_theSameRefundTwiceGivesBackOnce() {
        scene = sms(Map.of(), true);
        LevelAdmission leaf = scene.admitted("s6", UNILEVER, DOMESTIC).levels.get(0);
        LedgerPort port = scene.ledger.port();

        LedgerPort.Reservation again = port.charge(leaf, new BigDecimal("0.25"), leaf.getDebitReference()).orElseThrow();
        assertThat(again.repeated()).isTrue();
        assertThat(scene.balance("res_2", 12)).isEqualByComparingTo("99.75");

        port.refund(leaf, new BigDecimal("0.25"), "first");
        port.refund(leaf, new BigDecimal("0.25"), "second");
        assertThat(scene.balance("res_2", 12)).isEqualByComparingTo("100");
    }

    // ── the scene ───────────────────────────────────────────────────────────

    private static ParityScene sms(Map<String, String> balances, boolean refundOnFailure) {
        return ParityScene.openWith(ParityScene.perEventSettings(), balances, kit -> SmsLane.onPrepaid(kit, refundOnFailure));
    }

    private void deliver(CallContext sms) {
        sms.endCause = SmsLane.DELIVERED;
        scene.answer(sms);
        scene.flow.settle(sms, Outcome.SUCCEEDED);
        scene.flow.record(sms, Outcome.SUCCEEDED);
    }

    private void fail(CallContext sms) {
        sms.endCause = "SUBMIT_FAILED";
        scene.flow.release(sms);
        scene.flow.record(sms, Outcome.FAILED);
    }
}
