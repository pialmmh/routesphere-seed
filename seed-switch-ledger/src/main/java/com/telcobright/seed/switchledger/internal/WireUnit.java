package com.telcobright.seed.switchledger.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.telcobright.seed.sessionflow.api.CachedLedger;
import com.telcobright.seed.sessionflow.api.CachedLedger.Check;
import com.telcobright.seed.switchledger.internal.LedgerEntities.Known;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * C2 · A unit's JSON named on a cached-ledger unit, move by move, in order — what the road may do checked first ({@link RoadAllowance}):
 *
 * <pre>
 *   {"reference": "topup:pay-1",
 *    "moves": [
 *      {"op": "insert",      "tier": "btcl", "table": "packagepurchase", "row": {"idPackage": 1, "idPartner": 4, …}},
 *      {"op": "update",      "tier": "btcl", "table": "packageaccount", "id": 30, "set": {"idpackagePurchase": {"$key": 0}}},
 *      {"op": "add",         "tier": "btcl", "table": "packageaccount", "id": 30, "field": "balanceAfter", "delta": 500},
 *      {"op": "delete",      "tier": "btcl", "table": "packageaccount", "id": 31},
 *      {"op": "require",     "tier": "btcl", "table": "packageaccount", "id": 30, "check": "equalTo", "field": "balanceAfter", "value": 50},
 *      {"op": "requireNone", "tier": "btcl", "table": "packageaccount", "equals": {"idpackagePurchase": 9, "uom": "TF_min"}}]}
 * </pre>
 * A new row never carries its key: the ledger's application mints it, and {@code {"$key": n}} names the n-th insert's key (from 0).
 */
public final class WireUnit {

    private WireUnit() {}

    /** Every move named on the unit, in order. A caller's mistake is an {@link IllegalArgumentException}; a move not allowed, NotAllowed. */
    public static void name(JsonNode moves, CachedLedger.Unit unit, RoadAllowance allowed) {
        if (moves == null || !moves.isArray() || moves.isEmpty()) throw new IllegalArgumentException("a unit names its moves: \"moves\": [ … ]");
        List<Object> keys = new ArrayList<>();
        int n = 0;
        for (JsonNode move : moves) nameOne(move, "move " + n++, unit, allowed, keys);
    }

    private static void nameOne(JsonNode move, String what, CachedLedger.Unit unit, RoadAllowance allowed, List<Object> keys) {
        String op = text(move, "op", what), tier = text(move, "tier", what);
        Known known = RoadAllowance.known(text(move, "table", what));
        switch (op) {
            case "insert" -> keys.add(unit.insert(tier, newRow(move, what, known, allowed, keys)));
            case "update" -> update(move, what, known, allowed, keys, unit, tier);
            case "add" -> add(move, what, known, allowed, keys, unit, tier);
            case "delete" -> { allowed.mayDelete(known); unit.delete(tier, known.type(), idOf(move, what, keys)); }
            case "require" -> { allowed.mayRead(known); unit.require(tier, known.type(), idOf(move, what, keys), checkOf(move, what, keys)); }
            case "requireNone" -> { allowed.mayRead(known); unit.requireNone(tier, known.type(), WireJson.fieldsOf(move.get("equals"), keys, what + " equals")); }
            default -> throw new IllegalArgumentException(what + ": op is insert, update, add, delete, require or requireNone — not '" + op + "'");
        }
    }

    private static Object newRow(JsonNode move, String what, Known known, RoadAllowance allowed, List<Object> keys) {
        Map<String, Object> fields = WireJson.fieldsOf(move.get("row"), keys, what + " row");
        String key = RowFields.keyField(known.type());
        if (fields.containsKey(key)) throw new IllegalArgumentException(what + ": a new " + known.table() + " row has no " + key + " — the ledger's application mints it (one creator per table)");
        allowed.mayWrite(known, fields.keySet());
        return RowFields.newRow(known.type(), fields);
    }

    private static void update(JsonNode move, String what, Known known, RoadAllowance allowed, List<Object> keys, CachedLedger.Unit unit, String tier) {
        Map<String, Object> set = WireJson.fieldsOf(move.get("set"), keys, what + " set");
        allowed.mayWrite(known, set.keySet());
        unit.update(tier, known.type(), idOf(move, what, keys), set);
    }

    private static void add(JsonNode move, String what, Known known, RoadAllowance allowed, List<Object> keys, CachedLedger.Unit unit, String tier) {
        String field = text(move, "field", what);
        allowed.mayWrite(known, List.of(field));
        Object delta = WireJson.valueOf(move.get("delta"), keys, what + " delta");
        if (!(delta instanceof BigDecimal amount)) throw new IllegalArgumentException(what + ": delta is a number");
        unit.add(tier, known.type(), idOf(move, what, keys), field, amount);
    }

    private static Check checkOf(JsonNode move, String what, List<Object> keys) {
        String check = text(move, "check", what);
        return switch (check.toLowerCase(Locale.ROOT)) {
            case "exists" -> Check.exists();
            case "absent" -> Check.absent();
            case "atleast" -> {
                Object least = WireJson.valueOf(move.get("value"), keys, what + " value");
                if (!(least instanceof BigDecimal n)) throw new IllegalArgumentException(what + ": atLeast's value is a number");
                yield Check.atLeast(text(move, "field", what), n);
            }
            case "equalto" -> Check.equalTo(text(move, "field", what), WireJson.valueOf(move.get("value"), keys, what + " value"));
            default -> throw new IllegalArgumentException(what + ": check is exists, absent, atLeast or equalTo — not '" + check + "'");
        };
    }

    private static Object idOf(JsonNode move, String what, List<Object> keys) {
        Object id = WireJson.valueOf(move.get("id"), keys, what + " id");
        if (id == null) throw new IllegalArgumentException(what + " names its row: \"id\"");
        return id;
    }

    private static String text(JsonNode move, String field, String what) {
        JsonNode value = move == null ? null : move.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException(what + " names its " + field);
        return value.textValue().trim();
    }
}
