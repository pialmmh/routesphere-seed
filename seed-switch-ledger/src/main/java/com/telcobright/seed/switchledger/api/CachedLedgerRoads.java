package com.telcobright.seed.switchledger.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telcobright.seed.sessionflow.api.CachedLedger;
import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import com.telcobright.seed.switchledger.internal.LedgerEntities;
import com.telcobright.seed.switchledger.internal.RoadAllowance;
import com.telcobright.seed.switchledger.internal.RoadAllowance.NotAllowed;
import com.telcobright.seed.switchledger.internal.WireJson;
import com.telcobright.seed.switchledger.internal.WireUnit;
import com.telcobright.seed.switchledger.spi.LedgerGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * C2 · The cached ledger's roads (design §14, C2), for the application that owns the cache to mount — the seed has no server (R0).
 * Framework-free: the application's resource hands each request in and sends the answer back as it is.
 *
 * <pre>
 *   CachedLedgerRoads roads = CachedLedgerRoads.over(kit.ledger())
 *       .guard(LedgerGuard.ofTokenHash(salt, tokenSha256))                 // the application's own secret, from its secret store
 *       .ids(ids)                                                          // keys of new rows: bucket-next, every shard
 *       .writes("packagepurchase", List.of("idPackage", "idPartner", …))   // what callers may write — nothing by default
 *       .writes("packageaccount", List.of("idpackagePurchase", "balanceAfter", …))
 *       .build();
 *
 *   &#64;POST &#64;Path("/ledger/{root}/units")
 *   public Response unit(&#64;PathParam("root") String root, &#64;HeaderParam("Authorization") String auth, String json) {
 *       CachedLedgerRoads.Answer a = roads.unit(root, auth, json);
 *       return Response.status(a.status()).entity(a.json()).type("application/json").build();
 *   }
 * </pre>
 *
 * The wire is {@code WireUnit}'s (a unit's moves as JSON, {@code {"$key": n}} for the n-th insert's minted key). Statuses: 200 written,
 * or a repeat answered · 409 refused by the rows, nothing written · 400 a caller's mistake · 403 not allowed · 404 no such root here, or
 * no such row · 503 the ledger did not answer — send it again under the same reference: a unit that was written answers its first.
 */
public final class CachedLedgerRoads {

    private static final Logger log = LoggerFactory.getLogger(CachedLedgerRoads.class);

    /** What a road answers: the HTTP status and its JSON. */
    public record Answer(int status, String json) {}

    private final LedgerPort ledgers;
    private final LedgerGuard guard;
    private final LedgerPort.Ids ids;
    private final RoadAllowance allowed;

    private CachedLedgerRoads(Builder b) {
        this.ledgers = b.ledgers;
        this.guard = b.guard;
        this.ids = b.ids;
        this.allowed = b.allowed;
    }

    public static Builder over(LedgerPort ledgers) { return new Builder(Objects.requireNonNull(ledgers, "ledgers")); }

    public static final class Builder {
        private final LedgerPort ledgers;
        private final RoadAllowance allowed = new RoadAllowance();
        private LedgerGuard guard;
        private LedgerPort.Ids ids;

        private Builder(LedgerPort ledgers) { this.ledgers = ledgers; }

        /** Who may use the roads: the application's own check of the caller's token. Required. */
        public Builder guard(LedgerGuard guard) { this.guard = Objects.requireNonNull(guard, "guard"); return this; }

        /** Keys of new rows from the application's id source (bucket-next); without it a new row is refused (400). */
        public Builder ids(LedgerPort.Ids ids) { this.ids = Objects.requireNonNull(ids, "ids"); return this; }

        /** Callers may write these fields of {@code table} (insert, update, add) — and read the table. */
        public Builder writes(String table, Collection<String> fields) { allowed.writes(table, fields); return this; }

        /** Callers may read {@code table}. */
        public Builder reads(String table) { allowed.reads(table); return this; }

        /** Callers may delete rows of {@code table}. */
        public Builder deletes(String table) { allowed.deletes(table); return this; }

        public CachedLedgerRoads build() {
            if (guard == null) throw new IllegalStateException("the cached ledger's roads need the application's guard: no road is open to everyone");
            return new CachedLedgerRoads(this);
        }
    }

    // ── the roads ───────────────────────────────────────────────────────────

    /** {@code POST …/{root}/units} — ONE unit, written as ONE record, or refused with nothing written. */
    public Answer unit(String root, String token, String json) {
        return answered(root, token, "unit", ledger -> committed(root, ledger, parsed(json)));
    }

    /** {@code GET …/{root}/rows/{tier}/{table}/{id}} — one row, or 404. */
    public Answer row(String root, String token, String tier, String table, String id) {
        return answered(root, token, "row", ledger -> {
            Optional<?> row = ledger.get(tier, allowed.readableType(table), id);
            return row.isEmpty() ? error(404, "no " + table + " " + id + " in " + tier) : ok(WireJson.JSON.createObjectNode().set("row", WireJson.rowOf(row.get())));
        });
    }

    /** {@code GET …/{root}/rows/{tier}/{table}?field=value…} — the rows whose fields equal those given, by key. */
    public Answer rows(String root, String token, String tier, String table, Map<String, String> equals) {
        return answered(root, token, "rows", ledger -> {
            ArrayNode rows = WireJson.JSON.createArrayNode();
            ledger.find(tier, allowed.readableType(table), equals == null ? Map.of() : equals).forEach(row -> rows.add(WireJson.rowOf(row)));
            return ok(WireJson.JSON.createObjectNode().set("rows", rows));
        });
    }

    // ── the steps ───────────────────────────────────────────────────────────

    /** The door (the guard, then the root), the road, and every failure as its status. */
    private Answer answered(String root, String token, String road, Function<CachedLedger, Answer> work) {
        if (!guard.allows(bare(token), root)) return refusedAtTheDoor(road, root, error(403, "not allowed"));
        Optional<CachedLedger> ledger = ledgers.cachedLedgerOf(root);
        if (ledger.isEmpty()) return refusedAtTheDoor(road, root, error(404, "this application keeps no cached ledger for the root '" + root + "'"));
        try {
            return work.apply(ids == null ? ledger.get() : ledger.get().withIds(ids));
        } catch (NotAllowed e) {
            return refusedAtTheDoor(road, root, error(403, e.getMessage()));
        } catch (IllegalArgumentException e) {
            return refusedAtTheDoor(road, root, error(400, e.getMessage()));
        } catch (LedgerFault e) {
            return refusedAtTheDoor(road, root, error(503, e.getMessage()));
        }
    }

    private Answer committed(String root, CachedLedger ledger, JsonNode request) {
        String reference = request.hasNonNull("reference") ? request.get("reference").asText() : null;
        CachedLedger.Unit unit = reference == null ? ledger.unit() : ledger.unit(reference);
        WireUnit.name(request.get("moves"), unit, allowed);
        CachedLedger.Outcome outcome = unit.commit();
        if (outcome instanceof CachedLedger.Refused refused) {
            log.info("LEDGER ROAD unit {} {} | refused, nothing written: {}", LogText.clean(root), LogText.clean(String.valueOf(reference)), refused.reason());
            return answer(409, WireJson.JSON.createObjectNode().put("committed", false).put("refused", refused.reason()));
        }
        CachedLedger.Committed done = (CachedLedger.Committed) outcome;
        log.info("LEDGER ROAD unit {} {} | {}, keys {}", LogText.clean(root), LogText.clean(String.valueOf(reference)), done.repeated() ? "a repeat: the first answer" : "written", done.keys());
        ObjectNode body = WireJson.JSON.createObjectNode().put("committed", true).put("repeated", done.repeated());
        ArrayNode keys = body.putArray("keys");
        done.keys().forEach(key -> WireJson.add(keys, key));
        ArrayNode rows = body.putArray("rows");
        done.rows().forEach(row -> rows.addObject().put("table", tableOf(row)).set("row", WireJson.rowOf(row)));
        return ok(body);
    }

    private static JsonNode parsed(String json) {
        try {
            JsonNode node = WireJson.JSON.readTree(json == null ? "" : json);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("a unit is a JSON object: {\"reference\": …, \"moves\": [ … ]}");
            return node;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("a unit is JSON: " + e.getOriginalMessage());
        }
    }

    private static String tableOf(Object row) { return LedgerEntities.ofType(row.getClass()).map(LedgerEntities.Known::table).orElse(row.getClass().getSimpleName()); }

    /** The token, without the scheme a header carries ({@code Bearer …}). */
    private static String bare(String token) {
        if (token == null) return null;
        String t = token.trim();
        return t.regionMatches(true, 0, "Bearer ", 0, 7) ? t.substring(7).trim() : t;
    }

    /** A road refused before or around its work: said once (a unit's at INFO — a write was asked; a read's at DEBUG). */
    private static Answer refusedAtTheDoor(String road, String root, Answer answer) {
        if ("unit".equals(road)) log.info("LEDGER ROAD unit {} | {} {}", LogText.clean(root), answer.status(), answer.json());
        else log.debug("LEDGER ROAD {} {} | {} {}", road, LogText.clean(root), answer.status(), answer.json());
        return answer;
    }

    private static Answer ok(ObjectNode body) { return answer(200, body); }

    private static Answer error(int status, String message) { return answer(status, WireJson.JSON.createObjectNode().put("error", message)); }

    private static Answer answer(int status, ObjectNode body) { return new Answer(status, body.toString()); }
}
