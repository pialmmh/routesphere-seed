package com.telcobright.seed.switchledger.internal;

import com.telcobright.seed.sessionflow.api.CachedLedger;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.internal.LedgerEntities.Known;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/**
 * C1 · The cached ledger of ONE root's MemLedger (design §14.4) — reads of copies, and units ({@link CrudUnit}) written as ONE record
 * through the switch ledger's one write path ({@link UnitOfWork}) under the SAME row locks the sessions take ({@link AccountLocks}). One
 * memory of the references per MemLedger ({@link UnitAnswers}).
 *
 * <p>The entities it writes are the money's own rows — accounts and purchases. The sessions' rows (reserve rows, charge references) are
 * read here, never written: the sessions and the reaper own them. A type the ledger does not cache (its ledger DB does not name it) is
 * refused in words.
 */
public final class LedgerCrud implements CachedLedger {

    /** The entities a unit may write. */
    static final Set<String> WRITABLE = Set.of(Books.ACCOUNT, Books.PURCHASE, Books.PACKAGE, Books.PACKAGE_ITEM);   // C4: the catalog too

    private static final Comparator<Object> BY_KEY = (a, b) -> compareKeys(RowFields.keyOf(a), RowFields.keyOf(b));

    private final Books books;
    private final AccountLocks locks;
    private final UnitAnswers answers;
    private final LedgerPort.Ids ids;
    private final Lock gate;
    private final Runnable onNewMembers;

    /** The cached ledger over these books, on the ledger's one lock table. */
    public LedgerCrud(Books books, AccountLocks locks) { this(books, locks, () -> { }); }

    /**
     * @param onNewMembers told after a commit that changed which accounts belong to which purchase (an account or a purchase made or
     *                     deleted, an account moved): the port's partner → accounts index is made again on its next ask
     */
    public LedgerCrud(Books books, AccountLocks locks, Runnable onNewMembers) {
        this(books, locks, UnitAnswers.of(books.ledger()), null, null, onNewMembers);
    }

    private LedgerCrud(Books books, AccountLocks locks, UnitAnswers answers, LedgerPort.Ids ids, Lock gate, Runnable onNewMembers) {
        this.books = Objects.requireNonNull(books, "books");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.answers = answers;
        this.ids = ids;
        this.gate = gate;
        this.onNewMembers = onNewMembers;
    }

    /** The same ledger behind a gate held around every read and every commit (the owned port's: a new tier never loads under one). */
    public LedgerCrud within(Lock shared) { return new LedgerCrud(books, locks, answers, ids, Objects.requireNonNull(shared, "shared"), onNewMembers); }

    @Override
    public CachedLedger withIds(LedgerPort.Ids ids) { return new LedgerCrud(books, locks, answers, Objects.requireNonNull(ids, "ids"), gate, onNewMembers); }

    // ── reads ───────────────────────────────────────────────────────────────

    @Override
    public <T> Optional<T> get(String tier, Class<T> type, Object id) {
        Known known = readable(type);
        Object key = RowFields.keyAs(type, id);
        requireTier(tier);
        return gated(() -> {
            Object row = books.ledger().getEntity(tier, known.name(), key);
            return row == null ? Optional.<T>empty() : Optional.of(type.cast(RowFields.copyOf(row)));
        });
    }

    @Override
    public <T> List<T> find(String tier, Class<T> type, Map<String, ?> equals) {
        Known known = readable(type);
        Map<String, Object> wanted = CrudUnit.typedFields(type, equals == null ? Map.of() : equals);
        requireTier(tier);
        return gated(() -> {
            List<Object> rows = new ArrayList<>();
            for (Object row : books.unit().rowsAsLeft(tier, known.name())) if (RowFields.matches(row, wanted)) rows.add(RowFields.copyOf(row));
            rows.sort(BY_KEY);
            return rows.stream().map(type::cast).toList();
        });
    }

    // ── units ───────────────────────────────────────────────────────────────

    @Override
    public Unit unit() { return new CrudUnit(this, null); }

    @Override
    public Unit unit(String reference) {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("a unit's reference is a word: a repeat of it answers the first commit");
        return new CrudUnit(this, reference.trim());
    }

    // ── what the unit uses ──────────────────────────────────────────────────

    Books books() { return books; }

    AccountLocks locks() { return locks; }

    UnitAnswers answers() { return answers; }

    LedgerPort.Ids ids() { return ids; }

    void newMembers() { onNewMembers.run(); }

    /** The type as a cached entity this ledger holds, or refused in words. */
    Known readable(Class<?> type) {
        Known known = LedgerEntities.ofType(Objects.requireNonNull(type, "type")).orElseThrow(() -> new IllegalArgumentException(
            "the cached ledger knows no " + type.getSimpleName() + " (it knows " + LedgerEntities.known().stream().map(k -> k.type().getSimpleName()).toList() + ")"));
        if (!books.holds(known.name())) throw new IllegalArgumentException("this ledger does not cache " + known.table() + ": its ledger DB does not name it");
        return known;
    }

    /** The type as an entity a unit may write, or refused in words. */
    Known writable(Class<?> type) {
        Known known = readable(type);
        if (!WRITABLE.contains(known.name())) throw new IllegalArgumentException(known.table() + " rows belong to the sessions: the cached ledger reads them, never writes them");
        return known;
    }

    /** Does this ledger hold the tier (its schema)? */
    public boolean servesTier(String tier) { return tier != null && books.serves(tier); }

    /** The tier must be one this ledger holds, or refused in words. */
    void requireTier(String tier) {
        if (tier == null || tier.isBlank()) throw new IllegalArgumentException("a row's tier is named (its schema)");
        if (!books.serves(tier)) throw new IllegalArgumentException("this ledger holds no tier '" + tier + "' (it holds " + books.ledger().getRegisteredDatabases() + ")");
    }

    <T> T gated(Supplier<T> work) {
        if (gate == null) return work.get();
        gate.lock();
        try {
            return work.get();
        } finally {
            gate.unlock();
        }
    }

    private static int compareKeys(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return Long.compare(x.longValue(), y.longValue());
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
}
