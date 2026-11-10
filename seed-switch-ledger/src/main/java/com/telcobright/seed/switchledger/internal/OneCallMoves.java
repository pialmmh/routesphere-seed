package com.telcobright.seed.switchledger.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerRefusal;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;
import com.telcobright.seed.switchledger.spi.PatchingLedger;
import com.telcobright.seed.switchledger.spi.PatchingLedger.Answer;
import com.telcobright.seed.switchledger.spi.PatchingLedger.Change;
import com.telcobright.seed.switchledger.spi.PatchingLedger.Entry;
import com.telcobright.seed.switchledger.spi.PatchingLedger.Guard;
import com.telcobright.seed.switchledger.spi.PatchingLedger.Patch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ARCH-0119 road 1 in the engine · A hold and a settle as ONE call each: the ledger reads the account, checks it and writes — the engine
 * reads nothing first, holds no lock of its own and none across the wire. What {@link SwitchUnit#reserve} and {@link SwitchUnit#settle}
 * do with a read, a comparison and staged rows is SAID here as changes and guards, and the answer's rows give the same result:
 *
 * <ul>
 *   <li><b>hold</b> — the account: balanceBefore takes the balance, the balance loses the amount, lastAmount is minus the amount, and
 *       the balance must still be 0 or more; the hold's row grows by the amount, or is opened with it. Short of balance is the
 *       ledger's guard: refused, nothing written — the same {@code Optional.empty()}.</li>
 *   <li><b>settle</b> — the hold's row is deleted and the account takes back (the tier's reserved − the charged). No row open = the
 *       ledger's guard on the delete: the same "nothing open, owed" answer.</li>
 * </ul>
 *
 * <p>A verb this road cannot say exactly is NOT made here ({@link Made#elsewhere}) and the unit makes it as always: a hold of nothing,
 * a level that carries no planned account (the hold's row needs its purchase and unit), an account that changed its purchase since
 * the plan. Release, refund and a charge that keeps a charge row need a value of ANOTHER row (the hold's amount, the balance for the
 * charge row): they stay the unit's. Not said here: the settle's WARN when the hold's row and the tier disagree (the row is not read).
 *
 * <p>Busy rows are the busy fault ({@link LedgerFault#busy()}): nothing written, the same call may be made again.
 */
final class OneCallMoves {

    private static final Logger log = LoggerFactory.getLogger(MemLedgerPort.class);

    private final Books books;
    private final Answers answers;
    private final PatchingLedger ledger;

    private OneCallMoves(Books books, Answers answers, PatchingLedger ledger) {
        this.books = books;
        this.answers = answers;
        this.ledger = ledger;
    }

    /** The road over this ledger, or null: it is in the process, or its one-call road is off — every verb is then the unit it was. */
    static OneCallMoves over(Books books, Answers answers) {
        return books.ledger() instanceof PatchingLedger road && road.movesInOneCall() ? new OneCallMoves(books, answers, road) : null;
    }

    /** A verb's result: made here with this value, or not made here (the unit makes it). */
    record Made<T>(boolean here, T value) {
        static <T> Made<T> here(T value) { return new Made<>(true, value); }
        static <T> Made<T> elsewhere() { return new Made<>(false, null); }
    }

    // ── the hold ────────────────────────────────────────────────────────────

    Made<Optional<Reservation>> reserve(LevelAdmission level, BigDecimal amount, String reference) {
        long accountId = SwitchUnit.accountOf(level);
        Reservation seen = answers.hold(reference);
        if (seen != null) return Made.here(Optional.of(Answers.repeatOf(seen)));
        PackageAccount planned = level.getAccount();
        if (amount.signum() <= 0 || planned == null || planned.getIdpackagePurchase() == null || planned.getUom() == null) return Made.elsewhere();
        String db = level.getDbName(), rowKey = SwitchUnit.rowKeyOf(level, reference), now = books.now();
        Patch patch = new Patch("SEED_" + UUID.randomUUID(), List.of(
            Entry.change(db, Books.ACCOUNT, accountId,
                List.of(Change.copy("balanceBefore", "balanceAfter"), Change.add("balanceAfter", amount.negate()), Change.set("lastAmount", amount.negate())),
                List.of(Guard.equal("idpackagePurchase", planned.getIdpackagePurchase()), Guard.atLeast("balanceAfter", BigDecimal.ZERO))),
            Entry.changeOrInsert(db, Books.RESERVE, rowKey,
                List.of(Change.add("reserveUnit", amount), Change.set("time", now)),
                new PackageAccountReserve(rowKey, accountId, planned.getIdpackagePurchase(), "SESSION-Reserve-" + rowKey, amount, planned.getUom(), now))));
        String what = "hold " + amount + " under " + reference;
        Answer answer = asked(what, patch);
        switch (answer.outcome()) {
            case APPLIED -> {
                PackageAccount after = answer.rowAfter(1);
                if (after == null) throw new LedgerFault("the ledger applied " + what + " and answered no account row: its result is not known here");
                Reservation held = new Reservation(after.getId(), after.getUom(), amount, after.getBalanceBefore(), after.getBalanceAfter(), false);
                answers.rememberHold(rowKey, reference, held);
                return Made.here(Optional.of(held));
            }
            case GUARD -> {
                if (answer.failedEntry() != 1) throw refusedInWords(what, answer);
                PackageAccount live = (PackageAccount) answer.failedRow();
                if (live == null) throw accountNotHeld(db, accountId, level);
                if (!Objects.equals(live.getIdpackagePurchase(), planned.getIdpackagePurchase())) return Made.elsewhere();   // the account moved to another purchase since the plan: the unit reads it
                if (log.isDebugEnabled()) log.debug("{} | account {} of {} cannot fund {} {}: balance {}", LogText.clean(reference), accountId, db, amount, level.getUom(), Books.balanceOf(live));
                return Made.here(Optional.empty());
            }
            case BUSY -> throw busy(what, answer);
            default -> throw refusedInWords(what, answer);
        }
    }

    // ── the settle ──────────────────────────────────────────────────────────

    Made<TierSettlement> settle(LevelAdmission level, BigDecimal charged) {
        String tier = level.getDebitReference();
        if (tier == null) return Made.here(TierSettlement.nothing(level.getLevelIndex()));
        TierSettlement done = answers.closedTier(tier);
        if (done != null) return Made.here(done);
        long accountId = SwitchUnit.accountOf(level);
        String db = level.getDbName();
        BigDecimal toReturn = TierSettlement.reservedOf(level).subtract(charged);
        Patch patch = new Patch("SEED_" + UUID.randomUUID(), List.of(
            Entry.delete(db, Books.RESERVE, tier),                                // judged first: no hold open = nothing moves
            Entry.change(db, Books.ACCOUNT, accountId,
                List.of(Change.copy("balanceBefore", "balanceAfter"), Change.add("balanceAfter", toReturn), Change.set("lastAmount", toReturn)), List.of())));
        String what = "settle " + tier;
        Answer answer = asked(what, patch);
        switch (answer.outcome()) {
            case APPLIED -> {
                PackageAccount after = answer.rowAfter(2);
                if (after == null) throw new LedgerFault("the ledger applied " + what + " and answered no account row: its result is not known here");
                TierSettlement settled = TierSettlement.of(level, charged, after.getBalanceAfter());
                answers.close(tier, settled);
                return Made.here(settled);
            }
            case GUARD -> {
                if (answer.failedEntry() == 1) return Made.here(SwitchUnit.nothingOpen(level, tier, charged));
                if (answer.failedEntry() == 2 && answer.failedRow() == null) throw accountNotHeld(db, accountId, level);
                throw refusedInWords(what, answer);
            }
            case BUSY -> throw busy(what, answer);
            default -> throw refusedInWords(what, answer);
        }
    }

    // ── the answers that are no result ──────────────────────────────────────

    private Answer asked(String what, Patch patch) { return books.read(what, () -> ledger.patch(patch)); }

    /** As the unit says it: a schema the ledger does not serve is the host's fault, a missing account the tier's refusal. */
    private RuntimeException accountNotHeld(String db, long accountId, LevelAdmission level) {
        if (!books.serves(db)) return new LedgerFault("schema " + db + " is not registered in the MemLedger: the host must register every tier schema of the tree");
        return new LedgerRefusal(MemLedgerPort.ACCOUNT_NOT_FOUND, "account " + accountId + " of tier " + level.getLevelIndex() + " is not held in schema " + db);
    }

    private static LedgerFault busy(String what, Answer answer) {
        return LedgerFault.ofBusyRows("the ledger's rows were busy, nothing was written: could not " + what + " — " + answer.words(), null);
    }

    private static LedgerFault refusedInWords(String what, Answer answer) {
        return LedgerFault.ofALedgerOnTheWire("the ledger refused to " + what + ", nothing changed: " + answer.words(), null);
    }
}
