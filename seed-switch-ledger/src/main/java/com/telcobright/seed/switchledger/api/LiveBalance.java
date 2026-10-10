package com.telcobright.seed.switchledger.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * A read of a tier's account as the ledger holds it right now — no write, no lock (ARCH-0077-A item 3b): the call's C14 remainder peeks
 * at the balance after a whole window was refused ({@code BalanceBillingService.reserveNextWindowSeconds}: "spend what is left, if it is
 * worth spending"). Empty = the tier names no account, or the ledger does not hold it.
 */
public interface LiveBalance {

    Optional<BigDecimal> balanceOf(LevelAdmission level);
}
