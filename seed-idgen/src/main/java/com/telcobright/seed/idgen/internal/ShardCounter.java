package com.telcobright.seed.idgen.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.telcobright.seed.idgen.api.IdKind;
import com.telcobright.seed.idgen.api.IdRefused;

/**
 * One shard's counter for an entity, raised above the ids in use before the shard mints: registered with a start above them when the
 * shard does not know the entity, moved forward past them when it does and would hand out one of them. Each shard keeps its own
 * counter, so the shard that mints is the one raised. Safe against a racing process: a move the service calls backward means the
 * counter is past the value already.
 */
final class ShardCounter {

    private ShardCounter() {}

    static void raiseAbove(Shard shard, String entity, IdKind kind, long highestInUse) {
        if (highestInUse < 1) return;
        long start = firstAbove(kind, highestInUse);
        Answer status = shard.get("/api/status/" + entity);
        if (status.status() == 404) {
            if (registered(shard, entity, kind, start)) return;
            status = shard.get("/api/status/" + entity);                  // another process registered it first: read its counter
        }
        if (nextValueOf(shard, entity, status) <= highestInUse) moveForward(shard, entity, start);
    }

    private static long firstAbove(IdKind kind, long highestInUse) {
        if (highestInUse >= kind.max()) throw new IdRefused(400, "Invalid startValue", "no " + kind.wire() + " is above " + highestInUse);
        return highestInUse + 1;
    }

    /** True = this call registered the entity with its start; false = another process registered it first (its counter is read next). */
    private static boolean registered(Shard shard, String entity, IdKind kind, long start) {
        Answer init = shard.post("/api/init/" + entity, "{\"dataType\":\"" + kind.wire() + "\",\"startValue\":" + start + "}");
        if (init.status() == 409 && "Entity already exists".equals(init.error())) return false;
        init.bodyOrThrow(shard.base() + " init " + entity);
        return true;
    }

    private static long nextValueOf(Shard shard, String entity, Answer status) {
        JsonNode next = status.bodyOrThrow(shard.base() + " status " + entity).path("nextValue");
        if (next.isMissingNode() || next.isNull()) throw new IdRefused(400, "Not a counter", entity + " has no next value on " + shard.base());
        return Long.parseLong(next.asText());
    }

    private static void moveForward(Shard shard, String entity, long start) {
        Answer reset = shard.put("/api/reset/" + entity, "{\"value\":" + start + "}");
        if (reset.status() == 409 && "Backward reset refused".equals(reset.error())) return;
        reset.bodyOrThrow(shard.base() + " reset " + entity);
    }
}
