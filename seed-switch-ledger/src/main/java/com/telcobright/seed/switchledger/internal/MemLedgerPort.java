package com.telcobright.seed.switchledger.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.api.LiveBalance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The base's {@link LedgerPort} over the switch's own MemLedger (ARCH-0077-A item 3) — the call switch's money, in the base's three verbs,
 * exactly as routesphere-core {@code PrepaidServiceWithCompensation} moves it:
 *
 * <ul>
 *   <li><b>reserve</b> ({@code reserveBalance}, 78–184): under the account's lock, the LIVE row is read; a balance short of the amount is a
 *       refusal (empty, 94–102 — the reserve IS the affordability test, no separate balance read); else ONE reserve row keyed by the tier's
 *       reference is opened (133–157) or, for a later window of the same tier, grown (107–131) and the account is debited (159–174);</li>
 *   <li><b>settle</b> ({@code returnBalance}, 211–265, as {@code BalanceBillingService.settleAgainstReserve} applies it, 160–199): what was
 *       reserved less what the session finally costs goes back — in EITHER direction, a negative return is the extra debit of an overrun —
 *       and the row is deleted (254–255);</li>
 *   <li><b>release</b>: the whole of the row back, the row deleted — the same {@code returnBalance}.</li>
 * </ul>
 *
 * <p>Every verb is idempotent by its reference: a repeated reserve answers its first result ({@code repeated = true}) and debits nothing; a
 * repeated settle or release answers the first and moves nothing. The account is the tier's own ({@code level.getPackageAccountId()}: the
 * tree's {@code PackageAccount} the rating step chose through {@code TierRate.account}, else the account a rotation named — none is the
 * refusal {@code NO_ACCOUNT}), in the TIER's own schema ({@code level.getDbName()}). A MemLedger that does not answer is a
 * {@link LedgerFault}, never a refusal. In the process: {@link #slowestAnswerMs()} is 0.
 */
public final class MemLedgerPort implements LedgerPort, LiveBalance {

    private static final Logger log = LoggerFactory.getLogger(MemLedgerPort.class);

    /** The ledger refused: the tier names no account. */
    public static final String NO_ACCOUNT = "NO_ACCOUNT";
    /** The ledger refused: the account the tier names is not held in its schema. */
    public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";

    private final Books books;
    private final AccountLocks locks;
    /** The first answer of every reference held, until its tier closes. */
    private final Map<String, Reservation> answered = new ConcurrentHashMap<>();
    /** The references of every open tier (its row key), to forget at the close. */
    private final Map<String, Set<String>> referencesOf = new ConcurrentHashMap<>();
    private final ClosedTiers closed = new ClosedTiers();

    public MemLedgerPort(Books books, AccountLocks locks) {
        this.books = books;
        this.locks = locks;
    }

    // ── reserve ─────────────────────────────────────────────────────────────

    @Override
    public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) {
        long accountId = accountOf(level);
        Reservation seen = answered.get(reference);
        if (seen != null) return Optional.of(repeatOf(seen));
        String rowKey = rowKeyOf(level, reference);
        try (AccountLocks.Held held = locks.hold(level.getDbName(), accountId)) {
            return holdUnderTheLock(level, accountId, amount, reference, rowKey);
        }
    }

    private Optional<Reservation> holdUnderTheLock(LevelAdmission level, long accountId, BigDecimal amount, String reference, String rowKey) {
        String db = level.getDbName();
        PackageAccount live = heldAccount(db, accountId, level);
        BigDecimal before = Books.balanceOf(live);
        if (before.compareTo(amount) < 0) return refused(level, accountId, before, amount, reference);
        BigDecimal after = before.subtract(amount);
        PackageAccountReserve row = books.reserveRow(db, rowKey);
        if (row == null) books.openReserveRow(db, rowKey, live, amount); else books.growReserveRow(db, row, amount);
        books.moveBalance(db, live, after, amount.negate());
        return Optional.of(remember(rowKey, reference, new Reservation(live.getId(), live.getUom(), amount, before, after, false)));
    }

    private static Optional<Reservation> refused(LevelAdmission level, long accountId, BigDecimal balance, BigDecimal amount, String reference) {
        log.debug("{} | account {} of {} cannot fund {} {}: balance {}", reference, accountId, level.getDbName(), amount, level.getUom(), balance);
        return Optional.empty();
    }

    private Reservation remember(String rowKey, String reference, Reservation held) {
        answered.put(reference, held);
        referencesOf.computeIfAbsent(rowKey, k -> ConcurrentHashMap.newKeySet()).add(reference);
        return held;
    }

    private static Reservation repeatOf(Reservation first) {
        return new Reservation(first.account(), first.uom(), first.reserved(), first.balanceBefore(), first.balanceAfter(), true);
    }

    // ── settle ──────────────────────────────────────────────────────────────

    @Override
    public TierSettlement settle(LevelAdmission level, BigDecimal charged) {
        String tier = level.getDebitReference();
        if (tier == null) return TierSettlement.nothing(level.getLevelIndex());
        TierSettlement done = closed.get(tier);
        if (done != null) return done;
        long accountId = accountOf(level);
        try (AccountLocks.Held held = locks.hold(level.getDbName(), accountId)) {
            return settleUnderTheLock(level, tier, accountId, charged);
        }
    }

    private TierSettlement settleUnderTheLock(LevelAdmission level, String tier, long accountId, BigDecimal charged) {
        String db = level.getDbName();
        PackageAccountReserve row = books.reserveRow(db, tier);
        if (row == null) return nothingOpen(level, tier, charged);
        BigDecimal toReturn = TierSettlement.reservedOf(level).subtract(charged);
        noteADisagreement(level, row, tier);
        PackageAccount live = heldAccount(db, accountId, level);
        BigDecimal after = Books.balanceOf(live).add(toReturn);
        books.moveBalance(db, live, after, toReturn);
        books.deleteReserveRow(db, tier);
        forget(tier);
        return closed.remember(tier, TierSettlement.of(level, charged, after));
    }

    /** The base's facts and the row's should agree; when they do not, the base's rule the money and the difference is said once. */
    private static void noteADisagreement(LevelAdmission level, PackageAccountReserve row, String tier) {
        BigDecimal held = row.getReserveUnit() == null ? BigDecimal.ZERO : row.getReserveUnit();
        if (held.compareTo(TierSettlement.reservedOf(level)) != 0) {
            log.warn("{} | the reserve row holds {} but the tier reserved {}: the tier's total settles", tier, held, TierSettlement.reservedOf(level));
        }
    }

    /** No row is open under the tier: it was settled, released or reaped before. Nothing moves; the base is told it is owed, in words. */
    private static TierSettlement nothingOpen(LevelAdmission level, String tier, BigDecimal charged) {
        log.error("{} | no reserve row is open in {} for account {}: settled, released or reaped before — nothing moved", tier, level.getDbName(), level.getPackageAccountId());
        return TierSettlement.owed(level, charged, "no reserve row open under " + tier + " (settled, released or reaped before): nothing moved");
    }

    // ── release ─────────────────────────────────────────────────────────────

    @Override
    public void release(LevelAdmission level, String why) {
        String tier = level.getDebitReference();
        if (tier == null || closed.get(tier) != null) return;
        long accountId = accountOf(level);
        try (AccountLocks.Held held = locks.hold(level.getDbName(), accountId)) {
            releaseUnderTheLock(level, tier, why);
        }
    }

    private void releaseUnderTheLock(LevelAdmission level, String tier, String why) {
        String db = level.getDbName();
        PackageAccountReserve row = books.reserveRow(db, tier);
        if (row == null) { forget(tier); return; }
        BigDecimal back = row.getReserveUnit() == null ? BigDecimal.ZERO : row.getReserveUnit();
        BigDecimal after = books.returnRow(db, row);
        forget(tier);
        closed.remember(tier, new TierSettlement(level.getLevelIndex(), back, BigDecimal.ZERO, back, after, true, why));
    }

    // ── the peek (3b) ───────────────────────────────────────────────────────

    @Override
    public Optional<BigDecimal> balanceOf(LevelAdmission level) {
        Long accountId = level.getPackageAccountId();
        if (accountId == null || level.getDbName() == null) return Optional.empty();
        PackageAccount live = books.liveAccount(level.getDbName(), accountId);
        return live == null ? Optional.empty() : Optional.of(Books.balanceOf(live));
    }

    // ── the small helpers ───────────────────────────────────────────────────

    private static long accountOf(LevelAdmission level) {
        Long id = level.getPackageAccountId();
        if (id == null) {
            throw new LedgerRefusal(NO_ACCOUNT, "tier " + level.getLevelIndex() + " (" + level.getDbName() + ") partner " + level.getPartnerId()
                + " names no package account: the rating step chose none");
        }
        return id;
    }

    /** The live row; a schema the ledger does not serve is the HOST's fault (every tier schema of the tree is registered), a missing row the tier's refusal. */
    private PackageAccount heldAccount(String db, long accountId, LevelAdmission level) {
        PackageAccount live = books.liveAccount(db, accountId);
        if (live != null) return live;
        if (!books.serves(db)) throw new LedgerFault("schema " + db + " is not registered in the MemLedger: the host must register every tier schema of the tree");
        throw new LedgerRefusal(ACCOUNT_NOT_FOUND, "account " + accountId + " of tier " + level.getLevelIndex() + " is not held in schema " + db);
    }

    /** The row is the TIER's: its reference once held ({@code <sid>#L<n>}); a window ({@code …#W<n>}) grows it. */
    private static String rowKeyOf(LevelAdmission level, String reference) {
        if (level.getDebitReference() != null) return level.getDebitReference();
        int window = reference.indexOf("#W");
        return window < 0 ? reference : reference.substring(0, window);
    }

    private void forget(String tier) {
        Set<String> references = referencesOf.remove(tier);
        if (references != null) references.forEach(answered::remove);
    }
}
