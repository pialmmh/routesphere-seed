package com.telcobright.seed.idgen.testkit;

import com.telcobright.seed.idgen.api.IdKind;
import com.telcobright.seed.idgen.api.IdRefused;
import com.telcobright.seed.idgen.api.IdServiceUnavailable;
import com.telcobright.seed.idgen.spi.IdSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A bucket-next cluster in memory, for products' tests: {@code shards} shards, each with its own counter per entity, shard k of N
 * handing out k, k+N, k+2N, … (the service's shard math); the shards are asked in turn; an entity keeps the kind of its first call; a
 * shard can be taken down. The non-counter kinds answer the counter's value as a decimal string — unique, not the service's look.
 */
public final class InMemoryIdSource implements IdSource {

    private final int shards;
    private final Map<String, long[]> nextIterationByEntity = new HashMap<>();
    private final Map<String, IdKind> kindByEntity = new HashMap<>();
    private final Set<Integer> down = new HashSet<>();
    private int rotation;
    private int calls;

    public InMemoryIdSource(int shards) {
        if (shards < 1) throw new IllegalArgumentException("at least one shard");
        this.shards = shards;
    }

    /** A cluster of one shard: 1, 2, 3, … */
    public InMemoryIdSource() { this(1); }

    /** Shard {@code k} (1..N) stops answering; the others carry on. */
    public synchronized InMemoryIdSource down(int k) { down.add(k); return this; }

    public synchronized InMemoryIdSource up(int k) { down.remove(k); return this; }

    /** How often a verb was called. */
    public synchronized int calls() { return calls; }

    @Override
    public synchronized List<String> mint(String entity, IdKind kind, int count) {
        return mintOn(nextShard(entity), entity, kind, count, 0);
    }

    @Override
    public synchronized List<String> mintAbove(String entity, IdKind kind, int count, long highestInUse) {
        if (!kind.counter()) throw new IllegalArgumentException(kind.wire() + " is not a counter");
        return mintOn(nextShard(entity), entity, kind, count, highestInUse);
    }

    private List<String> mintOn(int shard, String entity, IdKind kind, int count, long highestInUse) {
        calls++;
        bind(entity, kind);
        long[] next = nextIterationByEntity.computeIfAbsent(entity, e -> new long[shards]);
        while (valueOf(shard, next[shard - 1]) <= highestInUse) next[shard - 1]++;
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(Long.toString(checked(kind, entity, valueOf(shard, next[shard - 1]++))));
        return out;
    }

    private void bind(String entity, IdKind kind) {
        IdKind bound = kindByEntity.putIfAbsent(entity, kind);
        if (bound != null && bound != kind) throw new IdRefused(400, "Type mismatch", "Entity '" + entity + "' is registered as '" + bound.wire() + "'");
    }

    private long checked(IdKind kind, String entity, long value) {
        if (kind.counter() && value > kind.max()) throw new IdRefused(409, "Range exhausted", entity + " has no " + kind.wire() + " values left");
        return value;
    }

    private long valueOf(int shard, long iteration) { return shard + iteration * shards; }

    private int nextShard(String entity) {
        for (int i = 0; i < shards; i++) {
            int shard = (rotation++ % shards) + 1;
            if (!down.contains(shard)) return shard;
        }
        throw new IdServiceUnavailable(entity, List.of("every in-memory shard is down"));
    }
}
