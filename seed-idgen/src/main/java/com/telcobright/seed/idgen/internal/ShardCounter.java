package com.telcobright.seed.idgen.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.telcobright.seed.idgen.api.IdKind;
import com.telcobright.seed.idgen.api.IdRefused;

/**
 * One shard's counter for an entity of a tenant, raised above the ids in use before the shard mints: registered with a start above them
 * when the shard does not know the entity, moved forward past them when it does and would hand out one of them. Each shard keeps its own
 * counter, so the shard that mints is the one raised. Safe against a racing process: a move the service calls backward means the
 * counter is past the value already.
 *
 * <p>"Does not know the entity" is the service's 404 {@code Entity not found} — that word alone (tenant-first docs/tenancy.md: every
 * tenant refusal is a 400 on purpose, so an unknown tenant never looks like an unregistered entity; any other 404 is a refusal).
 */
final class ShardCounter {

    private static final String ENTITY_NOT_FOUND = "Entity not found";

    private ShardCounter() {}

    static void raiseAbove(Shard shard, String tenant, String entity, IdKind kind, long highestInUse) {
        if (highestInUse < 1) return;
        long start = firstAbove(kind, highestInUse);
        String what = shard.base() + " " + tenant + "/" + entity;
        Answer status = shard.get(Roads.status(tenant, entity));
        if (unregistered(status)) {
            if (registered(shard, tenant, entity, kind, start, what)) return;
            status = shard.get(Roads.status(tenant, entity));                    // another process registered it first: read its counter
        }
        if (nextValueOf(shard, status, what) <= highestInUse) moveForward(shard, tenant, entity, start, what);
    }

    private static boolean unregistered(Answer status) { return status.status() == 404 && ENTITY_NOT_FOUND.equals(status.error()); }

    private static long firstAbove(IdKind kind, long highestInUse) {
        if (highestInUse >= kind.max()) throw new IdRefused(400, "Invalid startValue", "no " + kind.wire() + " is above " + highestInUse);
        return highestInUse + 1;
    }

    /** True = this call registered the entity with its start; false = another process registered it first (its counter is read next). */
    private static boolean registered(Shard shard, String tenant, String entity, IdKind kind, long start, String what) {
        Answer init = shard.post(Roads.init(tenant, entity), "{\"dataType\":\"" + kind.wire() + "\",\"startValue\":" + start + "}");
        if (init.status() == 409 && "Entity already exists".equals(init.error())) return false;
        init.bodyOrThrow(what + " init");
        return true;
    }

    private static long nextValueOf(Shard shard, Answer status, String what) {
        JsonNode next = status.bodyOrThrow(what + " status").path("nextValue");
        if (next.isMissingNode() || next.isNull()) throw new IdRefused(400, "Not a counter", what + " has no next value on " + shard.base());
        return Long.parseLong(next.asText());
    }

    private static void moveForward(Shard shard, String tenant, String entity, long start, String what) {
        Answer reset = shard.put(Roads.reset(tenant, entity), "{\"value\":" + start + "}");
        if (reset.status() == 409 && "Backward reset refused".equals(reset.error())) return;
        reset.bodyOrThrow(what + " reset");
    }
}
