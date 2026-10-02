package com.telcobright.seed.callflow.spi;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.callflow.api.TierSettlement;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The money of ONE tier, in the call's three verbs: reserve at admission, settle at the end, release when the chain was
 * refused. The call switch implements it on its own ledger ({@code PrepaidServiceWithCompensation}); the ad switch on
 * orchestrix's prepaid roads. The pipeline never knows which.
 *
 * <p>Every verb is idempotent by its reference: the same reference twice moves money once.
 *
 * <ul>
 *   <li>{@link #reserve}: empty = this partner cannot pay it. A present answer = the ledger's own facts.</li>
 *   <li>{@link LedgerFault} (thrown): the ledger did not answer, or answered that IT is broken. The pipeline reports
 *       {@code BILLING_SYSTEM_ERROR}, never a balance cause.</li>
 *   <li>{@link LedgerRefusal} (thrown): the ledger refused for a reason of its own that is not a balance. Its code is
 *       the call's cause.</li>
 * </ul>
 */
public interface LedgerPort {

    /**
     * Hold {@code amount} of the tier's balance under {@code reference}. Called once per tier at admission, and once
     * more per window of a long call ({@code level.getReservationCount()} tells which).
     */
    Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference);

    /**
     * The call ended: it finally costs {@code charged} at this tier. The ledger reconciles against everything the tier
     * reserved ({@code level.getTotalReserved()}) in EITHER direction: the rest goes back, a shortfall is debited.
     * Called exactly once per tier that reserved; the reference is {@code level.getDebitReference()}.
     */
    TierSettlement settle(LevelAdmission level, BigDecimal charged);

    /** Give back everything the tier reserved: a later tier refused, or the candidate was dropped. */
    void release(LevelAdmission level, String why);

    /**
     * What a reserve came to.
     *
     * @param account  the ledger's account that holds the reserve (null when the switch named the account itself)
     * @param uom      the unit of that account (null = as rated)
     * @param repeated the ledger had seen this reference before and answered its first result
     */
    record Reservation(Long account, String uom, BigDecimal reserved, BigDecimal balanceBefore, BigDecimal balanceAfter, boolean repeated) {}

    /** The ledger refused with a code of its own: that code is the cause. */
    final class LedgerRefusal extends RuntimeException {
        private final String code;
        public LedgerRefusal(String code, String message) { super(code + ": " + message); this.code = code; }
        public String code() { return code; }
    }

    /** The ledger did not answer, or is broken. */
    final class LedgerFault extends RuntimeException {
        public LedgerFault(String message) { super(message); }
        public LedgerFault(String message, Throwable cause) { super(message, cause); }
    }
}
