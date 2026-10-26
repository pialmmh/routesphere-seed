package com.telcobright.seed.prepaid.parity;

import org.junit.jupiter.api.Test;

/**
 * L2a 3 · The settlement rows on the call in BUILDER syntax with its money as the call's Prepaid ({@code RoutesphereApp…prepaid(CallPrepaid)},
 * design §10.1, {@link CallLane#onPrepaid}): the same rows, the same assertions — the base's gaps of these rows run and pass here.
 */
class CallParitySettlementOnPrepaidTest extends CallParitySettlementTest {

    @Override protected ParityScene.Lane lane() { return ParityScene.Lane.ON_PREPAID; }

    @Test @Override
    void P13_theSettlementReRatesOnTheTerminatingNumber_intlIsTheRootsRateAtEveryTier() { super.P13_theSettlementReRatesOnTheTerminatingNumber_intlIsTheRootsRateAtEveryTier(); }

    @Test @Override
    void P14_packageMinutesPerSecond_cashRateOverSixty_intlAddsMinimumTimeAndPulse() { super.P14_packageMinutesPerSecond_cashRateOverSixty_intlAddsMinimumTimeAndPulse(); }
}
