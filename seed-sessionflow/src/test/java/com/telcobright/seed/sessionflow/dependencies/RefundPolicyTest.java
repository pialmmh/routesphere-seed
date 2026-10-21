package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.seed.sessionflow.api.SettleOutcome;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The refund rule of a lane priced by its reserve: the policy's table and its one parser of the config words. The base's settlement
 * applying it through the two hooks it reads at settle time ({@code settleOutcome}, {@code refundPolicy}) is RoutesphereFlow's (§E tests).
 */
class RefundPolicyTest {

    private static final String KEY = "adsphere.billing.unshown";

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
}
