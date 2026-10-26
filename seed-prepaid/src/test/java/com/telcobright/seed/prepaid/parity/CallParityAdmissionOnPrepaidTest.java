package com.telcobright.seed.prepaid.parity;

import org.junit.jupiter.api.Test;

/**
 * L2a 3 · The admission rows on the call in BUILDER syntax with its money as the call's Prepaid ({@code RoutesphereApp…prepaid(CallPrepaid)},
 * design §10.1, {@link CallLane#onPrepaid}): the same rows, the same assertions — the base's gaps of these rows run and pass here.
 */
class CallParityAdmissionOnPrepaidTest extends CallParityAdmissionTest {

    @Override protected ParityScene.Lane lane() { return ParityScene.Lane.ON_PREPAID; }

    @Test @Override
    void P2_unexpiredOnly_packagesBeforeCash_eachAccountTriedUntilOnePays() { super.P2_unexpiredOnly_packagesBeforeCash_eachAccountTriedUntilOnePays(); }

    @Test @Override
    void P3_intlOutgoingIsCashOnly_causeNO_BALANCE_INT_OUT() { super.P3_intlOutgoingIsCashOnly_causeNO_BALANCE_INT_OUT(); }
}
