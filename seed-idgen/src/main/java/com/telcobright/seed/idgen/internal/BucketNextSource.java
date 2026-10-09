package com.telcobright.seed.idgen.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.telcobright.seed.idgen.api.IdKind;
import com.telcobright.seed.idgen.api.IdServiceUnavailable;
import com.telcobright.seed.idgen.spi.IdSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The ids of a bucket-next cluster. Every shard mints unique ids for an entity on its own (shard k of N owns its residue class), so a
 * call goes to the next shard in the rotation and, when that shard cannot serve it, to the one after — each shard once. A request the
 * service refuses is refused everywhere and goes nowhere else.
 */
public final class BucketNextSource implements IdSource {

    private static final Logger log = LoggerFactory.getLogger(BucketNextSource.class);
    private static final Pattern ENTITY_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final int MOST_PER_BATCH = 10_000;

    private final List<Shard> shards;
    private final AtomicInteger rotation = new AtomicInteger();

    public BucketNextSource(List<Shard> shards) {
        if (shards == null || shards.isEmpty()) throw new IllegalArgumentException("bucket-next: at least one shard is required");
        this.shards = List.copyOf(shards);
    }

    @Override
    public List<String> mint(String entity, IdKind kind, int count) {
        check(entity, count);
        return onTheFirstShardThatServes(entity, shard -> mintOn(shard, entity, kind, count));
    }

    @Override
    public List<String> mintAbove(String entity, IdKind kind, int count, long highestInUse) {
        check(entity, count);
        if (!kind.counter()) throw new IllegalArgumentException(kind.wire() + " is not a counter: only int and long can be started above a value");
        return onTheFirstShardThatServes(entity, shard -> {
            ShardCounter.raiseAbove(shard, entity, kind, highestInUse);
            return mintOn(shard, entity, kind, count);
        });
    }

    /** The shards in turn from the next in the rotation, each once: the first that serves the call answers it. */
    private List<String> onTheFirstShardThatServes(String entity, Function<Shard, List<String>> call) {
        int first = Math.floorMod(rotation.getAndIncrement(), shards.size());
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < shards.size(); i++) {
            Shard shard = shards.get((first + i) % shards.size());
            try {
                return call.apply(shard);
            } catch (ShardDown down) {
                failures.add(down.getMessage());
                log.warn("bucket-next: {} — the next shard is asked", down.getMessage());
            }
        }
        throw new IdServiceUnavailable(entity, failures);
    }

    private static List<String> mintOn(Shard shard, String entity, IdKind kind, int count) {
        String type = "?dataType=" + kind.wire();
        if (count == 1) {
            JsonNode body = shard.get("/api/next-id/" + entity + type).bodyOrThrow(shard.base() + " next-id " + entity);
            return List.of(valueOf(shard, body.path("value")));
        }
        JsonNode body = shard.get("/api/next-batch/" + entity + type + "&batchSize=" + count).bodyOrThrow(shard.base() + " next-batch " + entity);
        return valuesOf(shard, body.path("values"), count);
    }

    private static String valueOf(Shard shard, JsonNode value) {
        if (value.isMissingNode() || value.isNull() || value.asText().isEmpty()) throw new ShardDown(shard.base() + " answered without a value");
        return value.asText();
    }

    private static List<String> valuesOf(Shard shard, JsonNode values, int count) {
        if (!values.isArray() || values.size() != count) throw new ShardDown(shard.base() + " answered " + values.size() + " values for a batch of " + count);
        List<String> out = new ArrayList<>(count);
        values.forEach(v -> out.add(valueOf(shard, v)));
        return out;
    }

    private static void check(String entity, int count) {
        if (entity == null || !ENTITY_NAME.matcher(entity).matches()) throw new IllegalArgumentException("an entity name is [A-Za-z0-9._-]+, not '" + entity + "'");
        if (count < 1 || count > MOST_PER_BATCH) throw new IllegalArgumentException("a batch is 1.." + MOST_PER_BATCH + " ids, not " + count);
    }
}
