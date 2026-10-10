package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The module registers exactly two entities (ARCH-0077-A item 6): {@value Books#ACCOUNT} and {@value Books#RESERVE}. The MemLedger handed in
 * is asked what it holds ({@code getStats().getCacheStatistics()["cache_contents"]}: schema → entity → rows); any other entity — a
 * {@code PackagePurchase} above all — refuses the build in words: {@code packagepurchase} is never written by this module, and a ledger
 * that holds more than the two is not this module's to drive.
 */
public final class TwoEntities {

    static final Set<String> THE_TWO = Set.of(Books.ACCOUNT, Books.RESERVE);

    private TwoEntities() {}

    public static void check(MemLedger ledger) {
        Set<String> held = entitiesHeldBy(ledger);
        Set<String> others = new TreeSet<>(held);
        others.removeAll(THE_TWO);
        Set<String> missing = new TreeSet<>(THE_TWO);
        missing.removeAll(held);
        if (!others.isEmpty()) {
            throw new IllegalStateException("the switch ledger registers exactly two entities — " + Books.ACCOUNT + " and " + Books.RESERVE
                + " — but the MemLedger handed in also holds " + others + ": packagepurchase is never written by this module;"
                + " build the ledger with the two entities only");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("the switch ledger registers exactly two entities — " + Books.ACCOUNT + " and " + Books.RESERVE
                + " — but the MemLedger handed in lacks " + missing + ": register both");
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> entitiesHeldBy(MemLedger ledger) {
        Map<String, Object> cache;
        try {
            cache = ledger.getStats().getCacheStatistics();
        } catch (RuntimeException e) {
            throw new IllegalStateException("the MemLedger handed in cannot say what it holds (not initialized?): " + e.getMessage(), e);
        }
        Object contents = cache == null ? null : cache.get("cache_contents");
        if (!(contents instanceof Map<?, ?> perSchema)) throw new IllegalStateException("the MemLedger handed in reports no cache contents: is it initialized?");
        Set<String> held = new TreeSet<>();
        for (Object entities : perSchema.values()) held.addAll(((Map<String, ?>) entities).keySet());
        return held;
    }
}
