package com.telcobright.seed.ledgerclient.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telcobright.seed.ledgerclient.internal.Wire;
import com.telcobright.seed.ledgerclient.spi.Transport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * C2 · A cached ledger kept by another application, over its roads (design §14): the same verbs as the seed's {@code CachedLedger}, rows
 * as maps of fields (the caller keeps its own entities). ONE unit = ONE request = ONE record on the ledger's side, all tiers or none.
 *
 * <pre>
 *   RemoteLedger ledger = RemoteLedgers.builder().mount("http://10.10.199.30:7150/ledger/").root("btcl").token(secretStore::ledgerToken).build();
 *   RemoteLedger.Unit unit = ledger.unit("topup:" + paymentRef);
 *   RemoteLedger.Key purchase = unit.insert("btcl", "packagepurchase", purchaseRow);          // its key is minted by the application
 *   unit.update("btcl", "packageaccount", topUpAccount, Map.of("idpackagePurchase", purchase))
 *       .add("btcl", "packageaccount", topUpAccount, "balanceAfter", amount);
 *   RemoteLedger.Outcome outcome = unit.commit();                                             // Committed | Refused; no answer → RemoteLedgerFault
 * </pre>
 *
 * A {@link RemoteLedgerFault} leaves the unit's fate unknown: send it again with the SAME reference — a unit that was written answers its
 * first commit ({@code repeated}), so a payment is credited once. A caller's mistake (400) is an {@link IllegalArgumentException}; a road
 * the application does not allow (403), an {@link IllegalStateException}.
 */
public final class RemoteLedger {

    private final Transport transport;
    private final String root;
    private final Supplier<String> token;

    public RemoteLedger(Transport transport, String root, Supplier<String> token) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.root = Objects.requireNonNull(root, "root");
        this.token = token == null ? () -> null : token;
    }

    // ── reads ───────────────────────────────────────────────────────────────

    /** The row {@code tier} holds under {@code id}; empty = none. */
    public Optional<Map<String, Object>> row(String tier, String table, Object id) {
        Transport.Response answer = transport.send("GET", Wire.path(root, "rows", tier, table, String.valueOf(id)), null, token.get());
        if (answer.status() == 404 && Wire.isAbsentRow(answer.body())) return Optional.empty();
        return Optional.of(Wire.fieldsOf(Wire.body(answered(answer)).get("row")));
    }

    /** The rows whose fields equal those given (none named = every row of the table), by key. */
    public List<Map<String, Object>> rows(String tier, String table, Map<String, ?> equals) {
        Transport.Response answer = transport.send("GET", Wire.path(root, "rows", tier, table) + Wire.query(equals), null, token.get());
        List<Map<String, Object>> rows = new ArrayList<>();
        Wire.body(answered(answer)).get("rows").forEach(row -> rows.add(Wire.fieldsOf(row)));
        return rows;
    }

    // ── units ───────────────────────────────────────────────────────────────

    /** A unit with no reference: every commit writes. */
    public Unit unit() { return new Unit(null); }

    /** A unit known by {@code reference}: the first commit writes; the same reference again answers the first, nothing written. */
    public Unit unit(String reference) {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("a unit's reference is a word");
        return new Unit(reference.trim());
    }

    /** The key of the unit's n-th insert (from 0): a value in a later move; after the commit, {@link Committed#keyOf}. */
    public record Key(int index) {}

    /** What a commit came to. */
    public sealed interface Outcome permits Committed, Refused {}

    /**
     * Written as ONE record.
     *
     * @param keys     the keys of the rows inserted, in order; for a repeat, the first commit's first key
     * @param rows     every row inserted or changed, as written, with its table; none for a repeat
     * @param repeated the reference was committed before: nothing was written now
     */
    public record Committed(List<Object> keys, List<Row> rows, boolean repeated) implements Outcome {
        /** The key the unit's insert got. */
        public Object keyOf(Key key) { return keys.get(key.index()); }
    }

    /** One row as written: its table and its fields. */
    public record Row(String table, Map<String, Object> fields) {}

    /** Nothing written: {@code reason} says which row and which check. */
    public record Refused(String reason) implements Outcome {}

    /** ONE request of moves, sent at {@link #commit}. */
    public final class Unit {
        private final String reference;
        private final ArrayNode moves = Wire.JSON.createArrayNode();
        private int inserts;
        private boolean committed;

        private Unit(String reference) { this.reference = reference; }

        /** A new row (without its key: the application mints it). @return its key, for the moves that point at it */
        public Key insert(String tier, String table, Map<String, ?> row) {
            move("insert", tier, table).set("row", Wire.objectOf(row));
            return new Key(inserts++);
        }

        public Unit update(String tier, String table, Object id, Map<String, ?> set) {
            ObjectNode move = move("update", tier, table);
            Wire.put(move, "id", id);
            move.set("set", Wire.objectOf(set));
            return this;
        }

        /** Add {@code delta} (either sign) to a number field; an account's {@code balanceAfter} moves as a credit does. */
        public Unit add(String tier, String table, Object id, String field, BigDecimal delta) {
            ObjectNode move = move("add", tier, table);
            Wire.put(move, "id", id);
            move.put("field", field);
            Wire.put(move, "delta", delta);
            return this;
        }

        public Unit delete(String tier, String table, Object id) {
            Wire.put(move("delete", tier, table), "id", id);
            return this;
        }

        /** The row must be there. */
        public Unit requireExists(String tier, String table, Object id) { return require(tier, table, id, "exists", null, null); }

        /** The row must not be there. */
        public Unit requireAbsent(String tier, String table, Object id) { return require(tier, table, id, "absent", null, null); }

        /** The row is there and its number field is at least {@code least}. */
        public Unit requireAtLeast(String tier, String table, Object id, String field, BigDecimal least) { return require(tier, table, id, "atLeast", field, least); }

        /** The row is there and its field equals {@code value} — the guard of a set that replaces a value read before (a balance). */
        public Unit requireEqual(String tier, String table, Object id, String field, Object value) { return require(tier, table, id, "equalTo", field, value); }

        /** No row of the table may match {@code equals}. */
        public Unit requireNone(String tier, String table, Map<String, ?> equals) {
            move("requireNone", tier, table).set("equals", Wire.objectOf(equals));
            return this;
        }

        /** Send it all as ONE request: {@link Committed} or {@link Refused}; no answer, a {@link RemoteLedgerFault}. A unit commits once. */
        public Outcome commit() {
            if (committed) throw new IllegalStateException("this unit was committed: a new write opens a new unit");
            committed = true;
            ObjectNode request = Wire.JSON.createObjectNode();
            if (reference != null) request.put("reference", reference);
            request.set("moves", moves);
            Transport.Response answer = transport.send("POST", Wire.path(root, "units"), request.toString(), token.get());
            if (answer.status() == 409) return new Refused(Wire.body(answer).path("refused").asText());
            JsonNode body = Wire.body(answered(answer));
            return new Committed(Wire.keysOf(body.get("keys")), Wire.rowsOf(body.get("rows")), body.path("repeated").asBoolean());
        }

        private Unit require(String tier, String table, Object id, String check, String field, Object value) {
            ObjectNode move = move("require", tier, table);
            Wire.put(move, "id", id);
            move.put("check", check);
            if (field != null) move.put("field", field);
            if (value != null) Wire.put(move, "value", value);
            return this;
        }

        private ObjectNode move(String op, String tier, String table) {
            if (committed) throw new IllegalStateException("this unit was committed: a new write opens a new unit");
            return moves.addObject().put("op", op).put("tier", tier).put("table", table);
        }
    }

    /** 200 → the answer; 400 → a caller's mistake; 403 → not allowed; 404 → no such root; 5xx → a fault (send again). */
    private static Transport.Response answered(Transport.Response answer) {
        int status = answer.status();
        if (status == 200) return answer;
        String error = Wire.body(answer).path("error").asText(answer.body());
        if (status == 400 || status == 404) throw new IllegalArgumentException("the ledger's application: " + error);
        if (status == 403) throw new IllegalStateException("the ledger's application does not allow it: " + error);
        throw new RemoteLedgerFault("the ledger's application answered " + status + ": " + error);
    }
}
