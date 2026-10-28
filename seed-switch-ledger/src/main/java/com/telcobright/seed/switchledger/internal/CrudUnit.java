package com.telcobright.seed.switchledger.internal;

import com.telcobright.seed.sessionflow.api.CachedLedger.Check;
import com.telcobright.seed.sessionflow.api.CachedLedger.Committed;
import com.telcobright.seed.sessionflow.api.CachedLedger.Outcome;
import com.telcobright.seed.sessionflow.api.CachedLedger.Refused;
import com.telcobright.seed.sessionflow.api.CachedLedger.Unit;
import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.switchledger.api.ChargeReference;
import com.telcobright.seed.switchledger.internal.CrudMoves.Move;
import com.telcobright.seed.switchledger.internal.LedgerEntities.Known;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C1 · ONE unit of the cached ledger (design §14.4). The moves are named first and run at the commit, in this order: the gate (shared) →
 * every row's lock and the reference's, ONCE, in the one global order — the locks the sessions take → the reference: committed before →
 * its first answer, nothing written → each move staged on the rows as the moves before it left them → (C2) every row checked against its
 * table ({@link RowRules}: a row the database would refuse stops the WAL consumer) → the reference row (where the ledger keeps
 * {@code packageaccountcharge}) → ONE record → the locks freed. A refusal writes nothing. A row inserted with no key gets its key
 * at once (the caller writes it into the rows that point at it), from the application's ids above the highest the ledger holds.
 *
 * <p>Its log lines are DEBUG (the house rule: nothing per event unless debug is on): the caller says its business event — the credit's
 * {@code CREDIT}, an application's road — and the unit's answer tells it what happened.
 */
final class CrudUnit implements Unit {

    private static final Logger log = LoggerFactory.getLogger(CrudUnit.class);

    /** The reference row's unit (its {@code uom}) and key prefix: a unit's row never meets a purchase's ({@code purchase:…}). */
    static final String REFERENCE_UOM = "UNIT";
    static final String REFERENCE_PREFIX = "unit:";

    private final LedgerCrud crud;
    private final String reference;
    private final List<Move> moves = new ArrayList<>();
    private final List<Object> inserted = new ArrayList<>();
    private final Map<String, Long> highestOf = new HashMap<>();
    private boolean committed;

    CrudUnit(LedgerCrud crud, String reference) {
        this.crud = crud;
        this.reference = reference;
    }

    // ── naming the moves ────────────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public <K> K insert(String tier, Object row) {
        if (row == null) throw new IllegalArgumentException("an insert names its row");
        Known known = crud.writable(row.getClass());
        crud.requireTier(tier);
        Object copy = RowFields.copyAs(known.type(), row);                               // C2b: the class the ledger caches (all its columns)
        if (RowFields.keyOf(copy) == null) RowFields.set(copy, RowFields.keyField(copy.getClass()), mint(known));
        Object key = RowFields.keyOf(copy);
        name(new CrudMoves.Insert(tier, known.name(), key, copy));
        inserted.add(key);
        return (K) key;
    }

    @Override
    public Unit update(String tier, Class<?> type, Object id, Map<String, ?> set) {
        Known known = crud.writable(type);
        crud.requireTier(tier);
        Map<String, Object> fields = typedFields(type, set);
        if (fields.isEmpty()) throw new IllegalArgumentException("an update of " + known.name() + " " + id + " names a field to set");
        if (fields.containsKey(RowFields.keyField(type))) throw new IllegalArgumentException("a row's key is not updated: delete the row and insert another");
        Object key = RowFields.keyAs(type, id);
        if (setsABalance(known, fields) && !checksTheBalance(tier, known, key)) {
            throw new IllegalArgumentException("a balance moves by add; to set " + known.name() + " " + key + "'s " + CrudMoves.BALANCE
                + ", first require it equal to the value it replaces (a session may have moved it since it was read)");
        }
        return name(new CrudMoves.Update(tier, known.name(), key, fields));
    }

    @Override
    public Unit add(String tier, Class<?> type, Object id, String field, BigDecimal delta) {
        Known known = crud.writable(type);
        crud.requireTier(tier);
        if (!RowFields.isNumber(type, field)) throw new IllegalArgumentException(known.name() + "." + field + " is not a number: only a number is added to");
        if (delta == null) throw new IllegalArgumentException("an add names its amount (either sign)");
        RowFields.as(RowFields.field(type, field).getType(), delta, known.name() + "." + field);
        return name(new CrudMoves.Add(tier, known.name(), RowFields.keyAs(type, id), field, delta));
    }

    @Override
    public Unit delete(String tier, Class<?> type, Object id) {
        Known known = crud.writable(type);
        crud.requireTier(tier);
        return name(new CrudMoves.Delete(tier, known.name(), RowFields.keyAs(type, id)));
    }

    @Override
    public Unit require(String tier, Class<?> type, Object id, Check check) {
        Known known = crud.readable(type);
        crud.requireTier(tier);
        return name(new CrudMoves.Require(tier, known.name(), RowFields.keyAs(type, id), typedCheck(type, check)));
    }

    @Override
    public Unit requireNone(String tier, Class<?> type, Map<String, ?> equals) {
        Known known = crud.readable(type);
        crud.requireTier(tier);
        Map<String, Object> fields = typedFields(type, equals);
        if (fields.isEmpty()) throw new IllegalArgumentException("a requireNone of " + known.name() + " names the fields a row must not match");
        return name(new CrudMoves.RequireNone(tier, known.name(), fields));
    }

    // ── the commit ──────────────────────────────────────────────────────────

    @Override
    public Outcome commit() {
        if (committed) throw new IllegalStateException("this unit was committed: a new write opens a new unit");
        committed = true;
        return crud.gated(this::underTheLocks);
    }

    private Outcome underTheLocks() {
        List<AccountLocks.Key> keys = lockKeys();
        refuseInsideAnotherUnit(keys);
        try (AccountLocks.Held held = crud.locks().holdAll(keys)) {
            Committed first = firstAnswer();
            if (first != null) return repeated(first);
            UnitOfWork work = crud.books().unit();
            String refusal = CrudMoves.stageAll(moves, work);
            if (refusal == null) refusal = RowRules.refusalOf(work, crud.books());
            if (refusal != null) return refused(refusal);
            RowRules.scaleToColumns(work);
            List<Object> written = work.images().stream().map(RowFields::copyOf).toList();
            stageTheReferenceRow(work);
            work.commit("write the unit " + said() + " (" + moves.size() + " move(s))");
            return remembered(new Committed(inserted, written, false));
        }
    }

    /**
     * C2 · A unit committed by a thread that already holds one of its rows' locks — inside a session's unit, or another unit — would not
     * wait (the locks are re-entrant) and its write would be undone by the outer unit's: refused in words.
     */
    private void refuseInsideAnotherUnit(List<AccountLocks.Key> keys) {
        for (AccountLocks.Key key : keys) {
            if (crud.locks().heldByThisThread(key)) {
                throw new IllegalStateException("a cached-ledger unit is committed inside another unit that holds " + key.entity() + " " + key.id() + " of " + key.tier()
                    + ": its write would be undone by the outer one — commit it after the outer unit");
            }
        }
    }

    private List<AccountLocks.Key> lockKeys() {
        List<AccountLocks.Key> keys = new ArrayList<>();
        for (Move move : moves) keys.add(move.lockKey());
        if (reference != null) keys.add(new AccountLocks.Key(referenceTier(), "~unit", reference));
        return keys;
    }

    /** The first commit of this reference: remembered, or — after a restart — its reference row. Null = none (or no reference). */
    private Committed firstAnswer() {
        if (reference == null) return null;
        Committed seen = crud.answers().first(reference);
        if (seen != null || !crud.books().keepsCharges() || moves.isEmpty()) return seen;
        ChargeReference row = crud.books().ledger().getEntity(referenceTier(), Books.CHARGE, REFERENCE_PREFIX + reference);
        if (row == null) return null;
        long firstKey = row.getIdPackageAccount() == null ? 0 : row.getIdPackageAccount();
        return new Committed(firstKey == 0 ? List.of() : List.of(firstKey), List.of(), true);
    }

    /** Where the ledger keeps references: the unit's row in the tier it names first; its account column = the first key inserted (0 = none). */
    private void stageTheReferenceRow(UnitOfWork work) {
        if (reference == null || !crud.books().keepsCharges() || moves.isEmpty()) return;
        long firstKey = inserted.isEmpty() || !(inserted.get(0) instanceof Number n) ? 0 : n.longValue();
        work.insert(referenceTier(), Books.CHARGE, REFERENCE_PREFIX + reference, new ChargeReference(REFERENCE_PREFIX + reference, firstKey, REFERENCE_UOM,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, crud.books().now()));
    }

    private Committed remembered(Committed done) {
        if (reference != null) crud.answers().remember(reference, done);
        if (changesMembers()) crud.newMembers();
        log.debug("UNIT {} | {} move(s) in {}: written as one record, keys {}", said(), moves.size(), tiers(), done.keys());
        return done;
    }

    private Committed repeated(Committed first) {
        log.debug("UNIT {} | committed before: the first answer, nothing written", said());
        return new Committed(first.keys().isEmpty() ? List.of() : List.of(first.keys().get(0)), List.of(), true);
    }

    private Refused refused(String why) {
        log.debug("UNIT {} | refused, nothing written: {}", said(), why);
        return new Refused(why);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Unit name(Move move) {
        if (committed) throw new IllegalStateException("this unit was committed: a new write opens a new unit");
        moves.add(move);
        return this;
    }

    /** A new key above the highest the ledger holds and the unit minted (one scan per entity per unit). */
    private long mint(Known known) {
        if (crud.ids() == null) {
            throw new IllegalArgumentException("a " + known.name() + " with no key needs ids: the application passes its id source (cachedLedger.withIds(…)) — the seed names no id service (R0)");
        }
        long highest = highestOf.computeIfAbsent(known.name(), name -> crud.books().highestId(name));
        long id = crud.ids().next(known.table(), highest);
        highestOf.put(known.name(), Math.max(highest, id));
        return id;
    }

    private static boolean setsABalance(Known known, Map<String, Object> fields) { return Books.ACCOUNT.equals(known.name()) && fields.containsKey(CrudMoves.BALANCE); }

    /** Did the unit already require the account's balance equal to a value? */
    private boolean checksTheBalance(String tier, Known known, Object id) {
        for (Move move : moves) {
            if (move instanceof CrudMoves.Require r && r.tier().equals(tier) && r.entity().equals(known.name()) && String.valueOf(r.id()).equals(String.valueOf(id))
                && r.check().kind() == Check.Kind.EQUAL_TO && CrudMoves.BALANCE.equals(r.check().field())) return true;
        }
        return false;
    }

    /** An account made, deleted or moved, or a purchase made, changed or deleted: which accounts are whose may have changed. */
    private boolean changesMembers() {
        for (Move move : moves) {
            boolean moneyRow = Books.PURCHASE.equals(move.entity()) || Books.ACCOUNT.equals(move.entity());
            boolean madeOrDeleted = move instanceof CrudMoves.Insert || move instanceof CrudMoves.Delete;
            boolean moved = move instanceof CrudMoves.Update u && (Books.PURCHASE.equals(u.entity()) || u.set().containsKey("idpackagePurchase"));
            if ((moneyRow && madeOrDeleted) || moved) return true;
        }
        return false;
    }

    /** The values as their fields' types, the names checked (asked before anything runs). */
    static Map<String, Object> typedFields(Class<?> type, Map<String, ?> values) {
        Map<String, Object> typed = new LinkedHashMap<>();
        if (values == null) return typed;
        values.forEach((field, value) -> typed.put(field, RowFields.as(RowFields.field(type, field).getType(), value, type.getSimpleName() + "." + field)));
        return typed;
    }

    private static Check typedCheck(Class<?> type, Check check) {
        if (check == null || check.kind() == null) throw new IllegalArgumentException("a require names its check");
        return switch (check.kind()) {
            case EXISTS, ABSENT -> check;
            case AT_LEAST -> {
                if (!RowFields.isNumber(type, check.field()) || check.value() == null) throw new IllegalArgumentException("require at least names a number field and its least value");
                yield new Check(check.kind(), check.field(), CrudMoves.decimalOf(check.value()));
            }
            case EQUAL_TO -> new Check(check.kind(), check.field(), RowFields.as(RowFields.field(type, check.field()).getType(), check.value(), type.getSimpleName() + "." + check.field()));
        };
    }

    private String referenceTier() { return moves.isEmpty() ? "" : moves.get(0).tier(); }

    private List<String> tiers() { return moves.stream().map(Move::tier).distinct().toList(); }

    private String said() { return reference == null ? "(no reference)" : LogText.clean(reference); }
}
