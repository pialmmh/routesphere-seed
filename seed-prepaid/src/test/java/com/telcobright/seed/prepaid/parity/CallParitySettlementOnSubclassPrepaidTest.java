package com.telcobright.seed.prepaid.parity;

import org.junit.jupiter.api.Test;

/**
 * L2a 2b · The settlement and road rows (P13, P14, P16–P20) on the call as a BIG lane with the call's Prepaid ({@link CallFlowOnPrepaid}):
 * the same rows, the same assertions — and the base's gaps P13 (re-rated on the terminating number) and P14 (the live's six decimals) run
 * here, closed by the Prepaid's {@code Final.charge(tier -> cost)} with the call's own cost.
 */
class CallParitySettlementOnSubclassPrepaidTest extends CallParitySettlementTest {

    @Override protected ParityScene.Lane lane() { return ParityScene.Lane.ON_A_SUBCLASS_PREPAID; }

    @Test @Override
    void P13_theSettlementReRatesOnTheTerminatingNumber_intlIsTheRootsRateAtEveryTier() {
        super.P13_theSettlementReRatesOnTheTerminatingNumber_intlIsTheRootsRateAtEveryTier();
    }

    @Test @Override
    void P14_packageMinutesPerSecond_cashRateOverSixty_intlAddsMinimumTimeAndPulse() {
        super.P14_packageMinutesPerSecond_cashRateOverSixty_intlAddsMinimumTimeAndPulse();
    }
}
