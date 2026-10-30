package com.telcobright.seed.idgen.testkit;

import com.telcobright.seed.idgen.api.IdKind;
import com.telcobright.seed.idgen.api.IdRefused;
import com.telcobright.seed.idgen.api.IdServiceUnavailable;
import com.telcobright.seed.idgen.api.TenantId;
import com.telcobright.seed.idgen.spi.IdSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A bucket-next cluster in memory, for products' tests: {@code shards} shards, each with its own counter per (tenant, entity), shard k
 * of N handing out k, k+N, k+2N, … (the service's shard math); the shards are asked in turn; an entity keeps the kind of its first call
 * in its tenant; a shard can be taken down. Tenant-first like the service: the same entity name in two tenants is two counters, the
 * tenant is checked by the rule before anything is minted, and there is no default tenant. The non-counter kinds answer the counter's
 * value as a decimal string — unique, not the service's look.
 */
public final class InMemoryIdSource implements IdSource {

    private final int shards;
    private final Map<Key, long[]> nextIterationByEntity = new HashMap<>();
    private final Map<Key, IdKind> kindByEntity = new HashMap<>();
    private final Set<Integer> down = new HashSet<>();
    private int rotation;
    private int calls;

    /** One counter of the cluster: an entity IN its tenant. */
    private record Key(String tenant, String entity) {}

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

    /** The entities minted for {@code tenant} so far, sorted — what a caller's tenant word reached (empty for a tenant never asked). */
    public synchronized Set<String> entitiesOf(String tenant) {
        Set<String> out = new TreeSet<>();
        for (Key key : nextIterationByEntity.keySet()) if (key.tenant().equals(tenant)) out.add(key.entity());
        return out;
    }

    /** Every tenant minted for so far, sorted. */
    public synchronized Set<String> tenants() {
        Set<String> out = new TreeSet<>();
        for (Key key : nextIterationByEntity.keySet()) out.add(key.tenant());
        return out;
    }

    @Override
    public synchronized List<String> mint(String tenant, String entity, IdKind kind, int count) {
        Key key = new Key(TenantId.checked(tenant), entity);
        return mintOn(nextShard(key), key, kind, count, 0);
    }

    @Override
    public synchronized List<String> mintAbove(String tenant, String entity, IdKind kind, int count, long highestInUse) {
        Key key = new Key(TenantId.checked(tenant), entity);
        if (!kind.counter()) throw new IllegalArgumentException(kind.wire() + " is not a counter");
        return mintOn(nextShard(key), key, kind, count, highestInUse);
    }

    private List<String> mintOn(int shard, Key key, IdKind kind, int count, long highestInUse) {
        calls++;
        bind(key, kind);
        long[] next = nextIterationByEntity.computeIfAbsent(key, e -> new long[shards]);
        while (valueOf(shard, next[shard - 1]) <= highestInUse) next[shard - 1]++;
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(Long.toString(checked(kind, key, valueOf(shard, next[shard - 1]++))));
        return out;
    }

    private void bind(Key key, IdKind kind) {
        IdKind bound = kindByEntity.putIfAbsent(key, kind);
        if (bound != null && bound != kind) throw new IdRefused(400, "Type mismatch", "Entity '" + key.entity() + "' is registered as '" + bound.wire() + "' in tenant '" + key.tenant() + "'");
    }

    private long checked(IdKind kind, Key key, long value) {
        if (kind.counter() && value > kind.max()) throw new IdRefused(409, "Range exhausted", key.tenant() + "/" + key.entity() + " has no " + kind.wire() + " values left");
        return value;
    }

    private long valueOf(int shard, long iteration) { return shard + iteration * shards; }

    private int nextShard(Key key) {
        for (int i = 0; i < shards; i++) {
            int shard = (rotation++ % shards) + 1;
            if (!down.contains(shard)) return shard;
        }
        throw new IdServiceUnavailable(key.tenant() + "/" + key.entity(), List.of("every in-memory shard is down"));
    }
}
