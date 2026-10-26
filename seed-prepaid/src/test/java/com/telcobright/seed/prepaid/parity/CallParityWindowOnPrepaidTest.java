package com.telcobright.seed.prepaid.parity;

import org.junit.jupiter.api.Test;

/**
 * L2a 3 · The window rows on the call in BUILDER syntax with its money as the call's Prepaid ({@code RoutesphereApp…prepaid(CallPrepaid)},
 * design §10.1, {@link CallLane#onPrepaid}): the same rows, the same assertions — the base's gaps of these rows run and pass here.
 */
class CallParityWindowOnPrepaidTest extends CallParityWindowTest {

    @Override protected ParityScene.Lane lane() { return ParityScene.Lane.ON_PREPAID; }

    @Test @Override
    void P9_aWholeUnit_elseTheRemainderInSeconds_aZeroRateKeepsTheCadence() { super.P9_aWholeUnit_elseTheRemainderInSeconds_aZeroRateKeepsTheCadence(); }

    @Test @Override
    void P10_theNarrowestTierDecides_aPartialWindowCutsAtItsEnd_nothingLeftCutsNow() throws Exception { super.P10_theNarrowestTierDecides_aPartialWindowCutsAtItsEnd_nothingLeftCutsNow(); }
}
