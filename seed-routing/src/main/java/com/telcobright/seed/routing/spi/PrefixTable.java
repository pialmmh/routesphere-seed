package com.telcobright.seed.routing.spi;

import com.telcobright.seed.routing.spi.DialplanSource.Prefix;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * A source's dialplan prefixes indexed for the three-step longest match (P1-O: no list scan on the hot path) — by the called prefix's
 * length, by the calling prefix's length, and by both — so the router makes one get per length present, never a walk of the rows. Built
 * once per prefix list ({@link #of}): a source that keeps its tables per load answers {@link DialplanSource#prefixTableOf} with the table it
 * built then; the default indexes the rows {@code prefixesOf} hands. Equal keys keep the FIRST row, as the walk's {@code max} did.
 */
public final class PrefixTable {

    private static final char BETWEEN = '\u0000';

    private final int rows;
    private final NavigableMap<Integer, Map<String, Prefix>> byCalled;
    private final NavigableMap<Integer, Map<String, Prefix>> byCalling;
    private final NavigableMap<Integer, NavigableMap<Integer, Map<String, Prefix>>> byBoth;

    private PrefixTable(int rows, NavigableMap<Integer, Map<String, Prefix>> byCalled, NavigableMap<Integer, Map<String, Prefix>> byCalling,
                        NavigableMap<Integer, NavigableMap<Integer, Map<String, Prefix>>> byBoth) {
        this.rows = rows;
        this.byCalled = byCalled;
        this.byCalling = byCalling;
        this.byBoth = byBoth;
    }

    /** The rows indexed, once: every row with a called prefix by its length, every row with a calling prefix, every row with both. */
    public static PrefixTable of(List<Prefix> prefixes) {
        if (prefixes == null) return new PrefixTable(0, new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
        TreeMap<Integer, Map<String, Prefix>> byCalled = new TreeMap<>(), byCalling = new TreeMap<>();
        TreeMap<Integer, NavigableMap<Integer, Map<String, Prefix>>> byBoth = new TreeMap<>();
        for (Prefix p : prefixes) {
            if (!p.calledPrefix().isEmpty()) byCalled.computeIfAbsent(p.calledPrefix().length(), l -> new HashMap<>()).putIfAbsent(p.calledPrefix(), p);
            if (!p.callingPrefix().isEmpty()) byCalling.computeIfAbsent(p.callingPrefix().length(), l -> new HashMap<>()).putIfAbsent(p.callingPrefix(), p);
            if (!p.calledPrefix().isEmpty() && !p.callingPrefix().isEmpty()) {
                byBoth.computeIfAbsent(p.calledPrefix().length(), l -> new TreeMap<>()).computeIfAbsent(p.callingPrefix().length(), l -> new HashMap<>())
                    .putIfAbsent(p.calledPrefix() + BETWEEN + p.callingPrefix(), p);
            }
        }
        return new PrefixTable(prefixes.size(), Collections.unmodifiableNavigableMap(byCalled), Collections.unmodifiableNavigableMap(byCalling),
            Collections.unmodifiableNavigableMap(byBoth));
    }

    /** No rows at all: the source is unknown or has none (a table of rows of which none can match is NOT empty). */
    public boolean isEmpty() { return rows == 0; }

    /** Step 1 · the row whose called AND calling prefixes both match: the longest called, then the longest calling; null = none. */
    public Prefix both(String called, String calling) {
        if (called == null || calling == null) return null;
        for (Integer calledLength : byBoth.descendingKeySet()) {
            if (calledLength > called.length()) continue;
            Prefix hit = longestCallingFor(byBoth.get(calledLength), called.substring(0, calledLength), calling);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Step 2 · the row with the longest called prefix of the number; null = none. */
    public Prefix calledOnly(String called) { return longest(byCalled, called); }

    /** Step 3 · the row with the longest calling prefix of the number; null = none. */
    public Prefix callingOnly(String calling) { return longest(byCalling, calling); }

    private static Prefix longestCallingFor(NavigableMap<Integer, Map<String, Prefix>> byCallingLength, String calledPrefix, String calling) {
        for (Integer callingLength : byCallingLength.descendingKeySet()) {
            if (callingLength > calling.length()) continue;
            Prefix hit = byCallingLength.get(callingLength).get(calledPrefix + BETWEEN + calling.substring(0, callingLength));
            if (hit != null) return hit;
        }
        return null;
    }

    private static Prefix longest(NavigableMap<Integer, Map<String, Prefix>> byLength, String number) {
        if (number == null) return null;
        for (Integer length : byLength.descendingKeySet()) {
            if (length > number.length()) continue;
            Prefix hit = byLength.get(length).get(number.substring(0, length));
            if (hit != null) return hit;
        }
        return null;
    }
}
