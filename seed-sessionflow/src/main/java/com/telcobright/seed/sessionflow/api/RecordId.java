package com.telcobright.seed.sessionflow.api;

import java.util.Objects;

/**
 * A number the writer hands out under the batch lock (P1-R §A) — billing-core's {@code MaxIdSeededAutoIncrementManager}: the first id one
 * try of a batch asks of a {@link #seed} is the seed's answer + 1, every next one a number more, so an id never collides with the rows another
 * writer of the same schema committed under the same lock. One {@link #key} is one number in a batch: a record's INSERT and its summary entry
 * that name the same key carry the same id (the cdr's {@code IdCall} and its chargeable's {@code idEvent}).
 *
 * @param seed the query that answers the highest id taken (one row, one number)
 * @param key  what the id is for, among the seed's ids ({@code <tier>|<channelCallUuid>})
 */
public record RecordId(String seed, String key) {

    public RecordId {
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(key, "key");
    }
}
