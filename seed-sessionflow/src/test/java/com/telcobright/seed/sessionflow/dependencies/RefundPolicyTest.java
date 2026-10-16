package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.EntryPartner;
import com.telcobright.seed.sessionflow.api.SessionFlow;
import com.telcobright.seed.sessionflow.api.SessionFlowContext;
import com.telcobright.seed.sessionflow.api.SessionMachine;
import com.telcobright.seed.sessionflow.api.SettleOutcome;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy.Rule;
import com.telcobright.seed.sessionflow.samples.Scene;
import com.telcobright.statewalk.pipeline.StepMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The refund rule of a lane priced by its reserve: the policy's table, its one parser of the config words, and the base's own
 * settlement applying it through the two hooks it reads at settle time ({@code settleOutcome}, {@code refundPolicy}).
 */
class RefundPolicyTest {

    private static final String KEY = "adsphere.billing.unshown";

    private final Scene scene = new Scene();

    // ── the table ───────────────────────────────────────────────────────────

    @ParameterizedTest(name = "onFailure {0}, unshown {1}: consumed {2} · offered, never consumed {3} · failed {4}")
    @CsvSource({
        "CHARGE, CHARGE, true, true,  true",
        "CHARGE, REFUND, true, false, true",
        "REFUND, CHARGE, true, true,  false",
        "REFUND, REFUND, true, false, false"})
    void aConsumedSessionAlwaysPays_theOthersPayByTheirRule(Rule onFailure, Rule unshown, boolean consumed, boolean offered, boolean failed) {
        RefundPolicy policy = RefundPolicy.of(onFailure, unshown);

        assertThat(policy.pays(SettleOutcome.CONSUMED)).isEqualTo(consumed);
        assertThat(policy.pays(SettleOutcome.OFFERED_NOT_CONSUMED)).isEqualTo(offered);
        assertThat(policy.pays(SettleOutcome.FAILED)).isEqualTo(failed);
    }

    @Test
    void theCallsRule_refundsAFailedSession_andChargesOneOfferedAndNeverConsumed() {
        assertThat(RefundPolicy.callsRule()).isEqualTo(RefundPolicy.of(Rule.REFUND, Rule.CHARGE));
    }

    @Test
    void aPolicyNeedsBothRules() {
        assertThatThrownBy(() -> RefundPolicy.of(null, Rule.CHARGE)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a refund policy needs its rule for a failed session (onFailure): none was given");
        assertThatThrownBy(() -> RefundPolicy.of(Rule.REFUND, null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a refund policy needs its rule for a session offered and never consumed (unshown): none was given");
    }

    // ── the words ───────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"charge", "CHARGE", "Charge", "  charge ", "\tCHARGE\n"})
    void charge_isReadInAnyCase_withSpacesAround(String word) {
        assertThat(Rule.parse(KEY, word)).isEqualTo(Rule.CHARGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"refund", "REFUND", "Refund", " refund  "})
    void refund_isReadInAnyCase_withSpacesAround(String word) {
        assertThat(Rule.parse(KEY, word)).isEqualTo(Rule.REFUND);
    }

    @Test
    void anyOtherWord_aBlank_orNone_refusesTheStart_inTheAdLanesOwnSentence() {
        assertRefused("free", "adsphere.billing.unshown must be 'charge' or 'refund', not 'free'");
        assertRefused("", "adsphere.billing.unshown must be 'charge' or 'refund', not ''");
        assertRefused("   ", "adsphere.billing.unshown must be 'charge' or 'refund', not '   '");
        assertRefused(null, "adsphere.billing.unshown must be 'charge' or 'refund', not 'null'");
        assertThatThrownBy(() -> Rule.parse("sms.billing.on-failure", "free")).as("the sentence names the lane's own key")
            .hasMessage("sms.billing.on-failure must be 'charge' or 'refund', not 'free'");
    }

    private static void assertRefused(String word, String sentence) {
        assertThatThrownBy(() -> Rule.parse(KEY, word)).isInstanceOf(IllegalStateException.class).hasMessage(sentence);
    }

    // ── the base's settlement, through the two hooks ────────────────────────

    @Test
    void aLaneOnTheDefaultHooks_paysItsReserveWhenAnswered_andNothingWhenNot() {
        ReservePricedLane lane = new ReservePricedLane(scene);

        assertPaidTheWholeReserve(settled(lane, "d-1", true));
        assertPaidNothing(settled(lane, "d-2", false));

        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("99.50");
        assertThat(scene.ledger.balanceOf("btcl", 44)).isEqualByComparingTo("99.60");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void aSessionOfferedAndNeverConsumed_paysItsReserveWhenUnshownCharges_andNothingWhenItRefunds() {
        assertPaidTheWholeReserve(settled(lane(SettleOutcome.OFFERED_NOT_CONSUMED, RefundPolicy.of(Rule.REFUND, Rule.CHARGE)), "o-1", false));
        assertPaidNothing(settled(lane(SettleOutcome.OFFERED_NOT_CONSUMED, RefundPolicy.of(Rule.REFUND, Rule.REFUND)), "o-2", false));

        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("99.50");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void aFailedSession_paysItsReserveWhenOnFailureCharges() {
        assertPaidTheWholeReserve(settled(lane(SettleOutcome.FAILED, RefundPolicy.of(Rule.CHARGE, Rule.REFUND)), "f-1", false));

        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("99.50");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void aLaneAnsweringNull_settlesByTheDefaults_andNeverGoesOwed() {
        assertPaidNothing(settled(lane(SettleOutcome.FAILED, null), "n-1", false));                                   // the call's rule: a failure pays nothing
        assertPaidTheWholeReserve(settled(lane(SettleOutcome.OFFERED_NOT_CONSUMED, null), "n-2", false));             // ... and the unshown keeps its charge
        assertPaidNothing(settled(lane(null, RefundPolicy.of(Rule.REFUND, Rule.CHARGE)), "n-3", false));             // unanswered = FAILED
        assertPaidTheWholeReserve(settled(lane(null, RefundPolicy.of(Rule.REFUND, Rule.REFUND)), "n-4", true));      // answered = CONSUMED
        assertPaidNothing(settled(lane(null, null), "n-5", false));

        assertThat(scene.ledger.timesAsked("settle")).as("every tier of every session reached the ledger: none was OWED").isEqualTo(10);
        assertThat(scene.ledger.openReserves()).isZero();
    }

    /** 701's session on the lane: admitted (both tiers reserve), answered or not, then settled by the base. */
    private static SessionFlowContext settled(ReservePricedLane lane, String id, boolean answered) {
        SessionFlowContext session = new SessionFlowContext();
        session.sessionKey = id;
        session.tenantName = "btcl";
        assertThat(lane.preprocess(session)).isNull();
        assertThat(lane.admission(session, StepMode.LIVE).accepted()).isTrue();
        if (answered) session.answeredAtMs = lane.kit().clock().millis();
        lane.settle(session);
        return session;
    }

    private AnsweringLane lane(SettleOutcome outcome, RefundPolicy policy) { return new AnsweringLane(scene, outcome, policy); }

    /** Every tier kept its whole reserve — 0.50 at res_44, 0.40 at btcl — and the ledger took the settlement. */
    private static void assertPaidTheWholeReserve(SessionFlowContext session) {
        assertThat(session.settlements).extracting(TierSettlement::charged).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("0.50"), new BigDecimal("0.40"));
        assertThat(session.settlements).allSatisfy(tier -> assertThat(tier.closed()).as("taken, not OWED").isTrue());
    }

    /** Every tier paid nothing — its whole reserve went back — and the ledger took the settlement. */
    private static void assertPaidNothing(SessionFlowContext session) {
        assertThat(session.settlements).extracting(TierSettlement::charged).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(session.settlements).allSatisfy(tier -> assertThat(tier.closed()).as("taken, not OWED").isTrue());
    }

    /**
     * A lane priced by its reserve, on the Scene's story and on the DEFAULT hooks: partner 701's session is rated whole and reserved
     * whole — 0.50 at its own tier (res_44), 0.40 at the reseller's (btcl, partner 44).
     */
    private static class ReservePricedLane extends SessionFlow<SessionFlowContext> {

        private static final Map<String, BigDecimal> PRICE_AT_TIER = Map.of("res_44", new BigDecimal("0.50"), "btcl", new BigDecimal("0.40"));

        ReservePricedLane(Scene scene) { super(scene.kit(Scene.settings(4))); }

        @Override public String name() { return "reserve-priced"; }

        @Override protected int serviceGroup(SessionFlowContext session) { return 0; }

        @Override protected String buildTask(SessionFlowContext session) { return null; }

        @Override protected EntryPartner identifyEntryPartner(SessionFlowContext session) { return entryOfPartner(session, 701); }

        @Override
        protected TierRate rateAtLevel(SessionFlowContext session, Tenant tier, Partner partner, int levelIndex) {
            BigDecimal price = PRICE_AT_TIER.get(tier.getDbName());
            return TierRate.of(price, price, "BDT", "R1");
        }

        @Override protected void startSignaling(SessionFlowContext session, SessionMachine machine) { }
    }

    /** The same lane answering the two hooks with what the test says, a null included. */
    private static final class AnsweringLane extends ReservePricedLane {

        private final SettleOutcome outcome;
        private final RefundPolicy policy;

        AnsweringLane(Scene scene, SettleOutcome outcome, RefundPolicy policy) {
            super(scene);
            this.outcome = outcome;
            this.policy = policy;
        }

        @Override protected SettleOutcome settleOutcome(SessionFlowContext session) { return outcome; }

        @Override protected RefundPolicy refundPolicy(SessionFlowContext session) { return policy; }
    }
}
