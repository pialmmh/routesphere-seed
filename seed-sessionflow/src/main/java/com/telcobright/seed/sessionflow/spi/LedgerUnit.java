package com.telcobright.seed.sessionflow.spi;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;

/**
 * L2b item 2 · ONE business operation over EVERY tier it touches, on one ledger (design {@code prepaid-engine-design.md} §5.3: "each verb =
 * ONE unit of work over EVERY tier it touches"). The engine opens a unit ({@link LedgerPort#unit()}), takes the accounts' locks in ONE
 * global order ({@link #lock}), asks the moves — each answered as it WILL be once committed, reading what the unit already holds — and
 * {@link #commit}s: on a ledger that writes a batch (the switch ledger: ONE {@code execCrud(WALEntryBatch)}, one WAL record) every tier
 * lands or none does. {@link #close} releases the locks; a unit closed without its commit wrote nothing.
 *
 * <p>A ledger that cannot write a batch (orchestrix's roads, a lane's own port, the testkit's) gets {@code LedgerPort}'s default: each move
 * applied at once through the port's own verb, and a unit closed without its commit gives back, in reverse order, the holds and charges
 * it took — the old saga, kept only where no batch exists.
 *
 * <p>The answers keep the port's meaning: an empty hold or charge = this account cannot pay it (nothing staged); a {@link LedgerPort.LedgerFault}
 * = the ledger is broken (the unit is then closed without commit); a {@link LedgerPort.LedgerRefusal} = a refusal of the ledger's own.
 */
public interface LedgerUnit extends AutoCloseable {

    /** The accounts every later move of this unit touches: their locks taken once, in ONE global order, before any move. */
    void lock(Collection<LevelAdmission> accounts);

    /** The account's balance as this unit will leave it (a read; empty = this ledger cannot say). */
    Optional<BigDecimal> balanceOf(LevelAdmission level);

    /** Hold {@code amount} under {@code reference} — the port's {@code reserve}, bounded by {@code withinMs} where the ledger is remote. */
    Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs);

    /** Take {@code amount} at once under {@code reference}, no hold row — the port's {@code charge} (CHARGE_NOW). */
    Optional<Reservation> charge(LevelAdmission level, BigDecimal amount, String reference);

    /** The tier's reconciliation at {@code charged} — the port's {@code settle}. */
    TierSettlement settle(LevelAdmission level, BigDecimal charged);

    /** Everything the tier holds, back — the port's {@code release}. */
    void release(LevelAdmission level, String why);

    /** {@code amount} of the tier's charge given back — the port's {@code refund}. */
    TierSettlement refund(LevelAdmission level, BigDecimal amount, String why);

    /**
     * L2b gate · The business operation was refused with {@code why} (a tier that cannot pay, a fault): the unit will not be committed. A
     * ledger that applied its moves at once gives them back at its close with THIS cause (the release's note, as before L2b). Default: nothing
     * to say (a unit that writes nothing until its commit).
     */
    default void abandon(String why) { }

    /** Every move asked, as ONE write — all tiers or none; a refusal is a {@link LedgerPort.LedgerFault} and nothing was written. */
    void commit(String what);

    /** The locks released; a unit not committed wrote nothing (a ledger without a batch gives back what it took). */
    @Override
    void close();
}
