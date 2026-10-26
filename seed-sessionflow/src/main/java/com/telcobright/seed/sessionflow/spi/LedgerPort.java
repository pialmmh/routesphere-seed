package com.telcobright.seed.sessionflow.spi;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.TierSettlement;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The money of ONE tier, in the call's three verbs: reserve at admission, settle at the end, release when the chain was
 * refused. The call switch implements it on its own ledger ({@code PrepaidServiceWithCompensation}); the ad switch on
 * orchestrix's prepaid roads. The pipeline never knows which.
 *
 * <p><b>Every verb is idempotent per (session id, tier)</b> (P1 §L.1): the base names every reserve {@code <session>#L<tier>} (a later
 * candidate {@code <session>#<try>#L<tier>}, a renewal window {@code …#W<n>}) and settles or releases the tier by that first reference — the
 * same reference twice moves money once. Persistence is async, so a process that dies after the final state's synchronous settle and
 * before the final row lands restores the session from an older row and ends it again: the second {@link #settle} or {@link #release} of
 * the same (session, tier) must move nothing (the in-memory testkit ledger answers its first result; the switch ledger finds the reserve
 * row gone and moves nothing).
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
     * The same reserve, bounded: the answer must come within {@code withinMs} — what is left of the call's admission
     * budget. A ledger that calls a remote road uses the smaller of its own timeout and this, and repeats a timed-out
     * call only when the time left allows it. A ledger that cannot bound its call takes the plain reserve.
     */
    default Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) {
        return reserve(level, amount, reference);
    }

    /**
     * The call ended: it finally costs {@code charged} at this tier. The ledger reconciles against everything the tier
     * reserved ({@code level.getTotalReserved()}) in EITHER direction: the rest goes back, a shortfall is debited.
     * Called exactly once per tier that reserved; the reference is {@code level.getDebitReference()}.
     */
    TierSettlement settle(LevelAdmission level, BigDecimal charged);

    /** Give back everything the tier reserved: a later tier refused, or the candidate was dropped. */
    void release(LevelAdmission level, String why);

    /**
     * A reseller tier was created live under this ledger's root (R10: {@code tenant_created}, kind reseller — prime-context sends it only after
     * the tier's tables are committed): a ledger that keeps books per tier loads the new tier's here — with no money move of its own running
     * meanwhile (never under a session's reserve), the same tier twice one load — BEFORE the root's tree shows the tier to a session. A
     * failure is the ledger's own to say; the tier's money is then refused until it loads. Default: nothing (a ledger that keeps no books per
     * tier — orchestrix's roads, the testkit's).
     */
    default void tierAdded(String tierDb) { }

    /**
     * P1-J · The root's tree was reloaded (a doorbell's ring — a purchase wrote accounts —, a {@code tenant_created}, a root served, the
     * backstop): a ledger that keeps books per tier merges the rows written since its last look — an account made AFTER its tier existed
     * (ad-sphere P2c's G1) —, coalesced and never on a session's thread. Called before the reloaded tree is shown. Default: nothing.
     */
    default void treeReloaded() { }

    /**
     * After {@link #tierAdded}: are the tier's books loaded? A ledger that keeps books per tier and SAID its load failed (never thrown)
     * answers false, so the kit's gate hands the tier again on the next tree (the next doorbell ring, reload or event) instead of leaving
     * its money refused until a restart. Default: true (a ledger that keeps no books per tier has nothing to load).
     */
    default boolean tierLoaded(String tierDb) { return true; }

    /**
     * L2a · A tier's account as the ledger holds it right now — a read, no write, no lock (the C14 remainder's peek: the whole window was
     * refused, what is left is spent, routesphere-core {@code BalanceBillingService.reserveNextWindowSeconds} 86–120). Empty = this ledger
     * cannot say (orchestrix's roads, a ledger of a lane's own): the renewal then holds whole windows only.
     */
    default Optional<BigDecimal> balanceOf(LevelAdmission level) { return Optional.empty(); }

    /**
     * L2a · CHARGE_NOW — the SMS's final debit (routesphere-core {@code PrepaidServiceWithCompensation.debit} 441–516): {@code amount} taken
     * from the tier's account at once, under its lock, with NO reserve row ("a reserve exists to be settled later, and nothing settles an
     * SMS, so reserving one per message would leave rows behind forever"). Empty = the balance is short, nothing taken. Idempotent per
     * reference: the same reference twice takes once and answers the first. Default: this ledger takes no final charge — refused in words.
     */
    default Optional<Reservation> charge(LevelAdmission level, BigDecimal amount, String reference) {
        throw new LedgerFault(getClass().getSimpleName() + " takes no final charge (CHARGE_NOW): the switch ledger does");
    }

    /**
     * L2a · Give back {@code amount} of what {@link #charge} took under the tier's reference ({@code level.getDebitReference()}), once — the
     * SMS's refund (routesphere-core {@code BalanceCheckRule.refund} 236–255: a recharge of the charge). Answers the tier's settlement:
     * charged = what stays taken, returned = {@code amount}. A second refund of the same tier answers the first and moves nothing.
     */
    default TierSettlement refund(LevelAdmission level, BigDecimal amount, String why) {
        throw new LedgerFault(getClass().getSimpleName() + " gives back no final charge (CHARGE_NOW): the switch ledger does");
    }

    /**
     * L2a · The recharge road's credit — {@code amount} (positive) added to the account the level names (its tier's schema, its account id),
     * under the account's lock, a DELTA (routesphere-core {@code PrepaidServiceWithCompensation.recharge} 294–383: "a recharge written
     * DIRECTLY to MySQL is invisible to billing and gets overwritten by the next write-behind"). Default: refused in words.
     */
    default Credited credit(LevelAdmission account, BigDecimal amount, String reference) {
        throw new LedgerFault(getClass().getSimpleName() + " has no credit road: the switch ledger has");
    }

    /** L2a · The account the level names moved onto purchase {@code purchaseId} (its expiry follows the purchase), its balance kept. */
    default Credited reparent(LevelAdmission account, long purchaseId) {
        throw new LedgerFault(getClass().getSimpleName() + " has no reparent road: the switch ledger has");
    }

    /** What a credit or a reparent came to: the balance before and after, the purchase the account is on now. */
    record Credited(long accountId, BigDecimal before, BigDecimal after, long purchaseId) {}

    /**
     * The longest one reserve may take before this ledger gives up by itself, in milliseconds. 0 = it answers at once
     * (a ledger in the process). The engine says at start how many such answers fit one admission.
     */
    default long slowestAnswerMs() { return 0; }

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
