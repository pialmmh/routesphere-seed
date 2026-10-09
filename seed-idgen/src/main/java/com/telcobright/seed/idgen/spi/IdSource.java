package com.telcobright.seed.idgen.spi;

import com.telcobright.seed.idgen.api.IdKind;

import java.util.List;

/**
 * Where a product's ids come from. The real source is a bucket-next cluster ({@code dependencies.BucketNext}); a test hands in
 * {@code testkit.InMemoryIdSource}. Every id an entity is given is unique for that entity, whichever shard minted it and whichever
 * process asked.
 *
 * <p>Both verbs throw {@code IdServiceUnavailable} when no shard answered and {@code IdRefused} when the service refused the request
 * itself; neither mints anything then.
 */
public interface IdSource {

    /** {@code count} new ids of {@code entity} (1..10 000), in the service's wire form: decimal for the counters, the string itself otherwise. */
    List<String> mint(String entity, IdKind kind, int count);

    /**
     * {@code count} new ids of a COUNTER entity, every one above {@code highestInUse}: the shard that mints them is first registered
     * with a start above it, or moved forward past it. The way to put a generator over ids that existed before it (a tree's partners):
     * an id already in use is never handed out again. {@code highestInUse < 1} = nothing to stay above.
     */
    List<String> mintAbove(String entity, IdKind kind, int count, long highestInUse);
}
