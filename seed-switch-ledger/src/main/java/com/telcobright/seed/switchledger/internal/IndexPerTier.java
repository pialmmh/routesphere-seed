package com.telcobright.seed.switchledger.internal;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * One index per tier, made from the ledger's cache — on the WIRE when the ledger is a server (ARCH-0117) — and kept until it is forgotten
 * (new rows merged). Made OUTSIDE the map's lock: a {@code ConcurrentHashMap.computeIfAbsent} that waits on the wire holds its bin for a
 * forget and for every other asker, and pins a virtual thread's carrier (JDK 21). As before, the askers of one tier share ONE making (they
 * wait on its future, which parks a virtual thread); a making that fails is not kept — the next ask makes it again — and its waiters get the
 * same fault; a forget meanwhile wins: the index answers the asks that waited for it and is not kept.
 */
final class IndexPerTier<V> {

    private final Map<String, CompletableFuture<V>> kept = new ConcurrentHashMap<>();

    /** The tier's index: kept, being made by another asker (waited for), or made now by {@code make}. */
    V of(String tier, Function<String, V> make) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> first = kept.putIfAbsent(tier, mine);
        return first != null ? awaited(first) : made(tier, mine, make);
    }

    /**
     * The tier's index when one is made and kept; null while none is — then a write {@link #forget(String) forgets the tier}: a making in
     * flight may have read the ledger before the write, and must not be kept without its rows.
     */
    V keptOrNull(String tier) {
        CompletableFuture<V> index = kept.get(tier);
        return index == null || !index.isDone() || index.isCompletedExceptionally() ? null : index.join();
    }

    /** Every tier's index made again on its next ask. */
    void forget() { kept.clear(); }

    /** The tier's index made again on its next ask — a making in flight answers its own askers and is not kept. */
    void forget(String tier) { kept.remove(tier); }

    private V made(String tier, CompletableFuture<V> mine, Function<String, V> make) {
        try {
            V index = make.apply(tier);
            mine.complete(index);
            return index;
        } catch (Throwable failed) {
            kept.remove(tier, mine);
            mine.completeExceptionally(failed);
            throw failed;
        }
    }

    private static <V> V awaited(CompletableFuture<V> making) {
        try {
            return making.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException fault) throw fault;
            if (e.getCause() instanceof Error error) throw error;
            throw e;
        }
    }
}
