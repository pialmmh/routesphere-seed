package com.telcobright.seed.prepaid.parity;

import org.junit.jupiter.api.Test;

/**
 * L2a 2b · The admission rows (P1–P6, P15, P21) on the call as a BIG lane with the call's Prepaid ({@link CallFlowOnPrepaid}): the same
 * rows, the same assertions as on the base — and the base's gaps P2 (each account tried until one pays) and P3 (cash only abroad,
 * {@code NO_BALANCE_INT_OUT}) run here, closed by the Prepaid's account walk, its {@code units} (none abroad: cash only) and {@code noBalanceCause}.
 */
class CallParityAdmissionOnSubclassPrepaidTest extends CallParityAdmissionTest {

    @Override protected ParityScene.Lane lane() { return ParityScene.Lane.ON_A_SUBCLASS_PREPAID; }

    @Test @Override
    void P2_unexpiredOnly_packagesBeforeCash_eachAccountTriedUntilOnePays() { super.P2_unexpiredOnly_packagesBeforeCash_eachAccountTriedUntilOnePays(); }

    @Test @Override
    void P3_intlOutgoingIsCashOnly_causeNO_BALANCE_INT_OUT() { super.P3_intlOutgoingIsCashOnly_causeNO_BALANCE_INT_OUT(); }
}
