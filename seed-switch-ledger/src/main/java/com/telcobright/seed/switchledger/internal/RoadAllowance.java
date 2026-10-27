package com.telcobright.seed.switchledger.internal;

import com.telcobright.seed.switchledger.internal.LedgerEntities.Known;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C2 · What the callers of a road may do, table by table — named by the application, nothing by default: the fields they may write (an
 * insert's, an update's, an add's), the tables they may read (the written ones are read too), the tables they may delete from. The
 * sessions' rows are never written here. A move outside it is {@link NotAllowed} (403), before anything runs.
 */
public final class RoadAllowance {

    /** A move the application did not allow: refused before anything runs (403). */
    public static final class NotAllowed extends RuntimeException {
        NotAllowed(String message) { super(message); }
    }

    private final Map<String, Set<String>> writable = new LinkedHashMap<>();
    private final Set<String> readable = new LinkedHashSet<>();
    private final Set<String> deletable = new LinkedHashSet<>();

    /** Callers may write these fields of {@code table} (the key is never theirs: it is minted). */
    public void writes(String table, Collection<String> fields) {
        Known known = known(table);
        if (!LedgerCrud.WRITABLE.contains(known.name())) throw new IllegalArgumentException(known.table() + " rows belong to the sessions: no road writes them");
        String key = RowFields.keyField(known.type());
        for (String field : fields) {
            RowFields.field(known.type(), field);
            if (field.equals(key)) throw new IllegalArgumentException(known.table() + "." + field + " is the key: it is minted by the ledger's application, never written by a caller");
        }
        writable.computeIfAbsent(known.table(), t -> new LinkedHashSet<>()).addAll(fields);
        readable.add(known.table());
    }

    public void reads(String table) { readable.add(known(table).table()); }

    public void deletes(String table) {
        Known known = known(table);
        if (!LedgerCrud.WRITABLE.contains(known.name())) throw new IllegalArgumentException(known.table() + " rows belong to the sessions: no road deletes them");
        deletable.add(known.table());
        readable.add(known.table());
    }

    /** The known table, or refused in words (400). */
    static Known known(String table) {
        return LedgerEntities.ofTable(table).orElseThrow(() -> new IllegalArgumentException(
            "no cached table '" + table + "' (the ledger knows " + LedgerEntities.known().stream().map(Known::table).toList() + ")"));
    }

    /** The table's type, if callers may read it; else NotAllowed (403), or an unknown table (400). */
    public Class<?> readableType(String table) {
        Known known = known(table);
        mayRead(known);
        return known.type();
    }

    void mayRead(Known known) {
        if (!readable.contains(known.table())) throw new NotAllowed("this road does not read " + known.table());
    }

    void mayWrite(Known known, Collection<String> fields) {
        Set<String> allowed = writable.getOrDefault(known.table(), Set.of());
        List<String> not = fields.stream().filter(f -> !allowed.contains(f)).toList();
        if (allowed.isEmpty()) throw new NotAllowed("this road does not write " + known.table());
        if (!not.isEmpty()) throw new NotAllowed("this road does not write " + known.table() + "." + String.join(", ", not));
    }

    void mayDelete(Known known) {
        if (!deletable.contains(known.table())) throw new NotAllowed("this road does not delete from " + known.table());
    }
}
