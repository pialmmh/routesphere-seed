package com.telcobright.seed.prepaid.parity;

import org.junit.jupiter.api.Test;

/**
 * L2a 2b · The window rows (P7–P12) on the call as a BIG lane with the call's Prepaid ({@link CallFlowOnPrepaid}): the same rows, the
 * same assertions — and the base's gaps P9 (the whole unit, else the remainder in seconds) and P10 (a partial window cut at its end) run
 * here, closed by the Prepaid's HOLD_WINDOW renewal (the switch ledger's {@code WindowRenewal} over the port's peek).
 */
class CallParityWindowOnSubclassPrepaidTest extends CallParityWindowTest {

    @Override protected ParityScene.Lane lane() { return ParityScene.Lane.ON_A_SUBCLASS_PREPAID; }

    @Test @Override
    void P9_aWholeUnit_elseTheRemainderInSeconds_aZeroRateKeepsTheCadence() { super.P9_aWholeUnit_elseTheRemainderInSeconds_aZeroRateKeepsTheCadence(); }

    @Test @Override
    void P10_theNarrowestTierDecides_aPartialWindowCutsAtItsEnd_nothingLeftCutsNow() throws Exception {
        super.P10_theNarrowestTierDecides_aPartialWindowCutsAtItsEnd_nothingLeftCutsNow();
    }
}
