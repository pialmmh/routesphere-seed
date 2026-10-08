package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;

import java.math.BigDecimal;

/**
 * What ONE tier's settlement came to at the end of the call: what was reserved, what the call finally costs, and what
 * went back. The CDR's amounts are {@code charged}. A call writes one settlement per tier, in the tiers' order.
 *
 * @param closed false = the ledger did not take the settlement. The reserve is still open there and the difference is
 *               OWED: one ERROR is logged with the reference, and the CDR says so
 * @param note   why it is not closed, else null
 */
public record TierSettlement(int levelIndex, BigDecimal reserved, BigDecimal charged, BigDecimal returned, BigDecimal balanceAfter,
                             boolean closed, String note) {

    /** A tier that reserved nothing has nothing to settle. */
    public static TierSettlement nothing(int levelIndex) {
        return new TierSettlement(levelIndex, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, true, null);
    }

    /** The ledger took it: {@code charged} stays, the rest of the reserve went back (negative = the call outran its reserve). */
    public static TierSettlement of(LevelAdmission level, BigDecimal charged, BigDecimal balanceAfter) {
        BigDecimal reserved = reservedOf(level);
        return new TierSettlement(level.getLevelIndex(), reserved, charged, reserved.subtract(charged), balanceAfter, true, null);
    }

    /** The ledger did not take it: nothing moved, the difference is owed. */
    public static TierSettlement owed(LevelAdmission level, BigDecimal charged, String why) {
        return new TierSettlement(level.getLevelIndex(), reservedOf(level), charged, BigDecimal.ZERO, null, false, why);
    }

    public static BigDecimal reservedOf(LevelAdmission level) {
        return level.getTotalReserved() == null ? BigDecimal.ZERO : level.getTotalReserved();
    }
}
