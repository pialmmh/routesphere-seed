package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.spi.LedgerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * I266 (the WiFi architect's suggestion, 2026-11-24, accepted for the seed commit after {@code 82a5c0c}) · A {@link LedgerPort} that KEEPS
 * the SPI's default {@code mergeIfUnknown} switches I266 off without a word: a purchase written after its partner's first admission stays
 * unseen, and the subscriber's tap reads "no balance". Said ONCE per ledger class, at WARN, at its first ask — so a forgotten wrapper shows
 * in the first journey's log. A ledger that decided to merge nothing (it holds every row it will see) overrides the road and stays quiet.
 */
public final class LedgersThatMergeNothing {

    private static final Logger log = LoggerFactory.getLogger(LedgerPort.class);
    /** The ledger classes said so far. */
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();
    /** The lines written per ledger class (a row's view: 0 or 1). */
    private static final Map<String, Integer> LINES = new ConcurrentHashMap<>();

    private LedgersThatMergeNothing() {}

    /** The SPI's default answer — nothing new —, said once for {@code ledger}'s class. */
    public static LedgerPort.NewRows nothingNew(Class<?> ledger) {
        if (SAID.add(ledger.getName())) sayIt(ledger.getName());
        return LedgerPort.NewRows.NOTHING_NEW;
    }

    /** How many lines were written for {@code ledger}'s class. */
    public static int linesFor(Class<?> ledger) { return LINES.getOrDefault(ledger.getName(), 0); }

    private static void sayIt(String ledger) {
        LINES.merge(ledger, 1, Integer::sum);
        log.warn("{} keeps LedgerPort's default mergeIfUnknown: it merges nothing — I266 is OFF on it (a purchase written after its partner's"
            + " first admission stays unseen: the tap reads no balance). A ledger that wraps another passes the road on; one that holds every"
            + " row it will see overrides it. Said once for this class.", ledger);
    }
}
