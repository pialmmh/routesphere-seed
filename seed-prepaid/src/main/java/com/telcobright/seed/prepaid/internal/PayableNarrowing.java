package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S5 (A-0230 R1) · A lane's narrowing of the accounts the rule lets pay, made safe. Only the accounts the rule's list holds survive,
 * matched by id; the walk keeps the LIST's own objects (never the lane's copy), in the lane's order, each once. An account the lane names
 * that the list does not hold — expired, of another unit, of another partner, made up — is dropped, and the log says so once per lane:
 * a lane narrows, it never widens. A null answer is an empty one: none pays.
 */
final class PayableNarrowing {

    private static final Logger log = LoggerFactory.getLogger(RoutesphereFlow.class);
    /** The lanes said once: a lane that named an account its rule does not let pay. */
    private static final Set<String> SAID_FOREIGN = ConcurrentHashMap.newKeySet();

    private PayableNarrowing() {}

    /** The accounts of {@code named} that {@code fundable} holds, as {@code fundable}'s objects, in {@code named}'s order, each once. */
    static List<PackageAccount> within(String lane, List<PackageAccount> fundable, List<PackageAccount> named) {
        if (named == null || named.isEmpty()) return List.of();
        Map<Long, PackageAccount> listed = byId(fundable);
        Set<Long> kept = new HashSet<>();
        List<PackageAccount> narrowed = new ArrayList<>(named.size());
        for (PackageAccount account : named) {
            Long id = account == null ? null : account.getId();
            PackageAccount own = id == null ? null : listed.get(id);
            if (own == null) sayForeignOnce(lane, id);
            else if (kept.add(id)) narrowed.add(own);
        }
        return List.copyOf(narrowed);
    }

    /** I266 · The ids {@code named} holds that {@code fundable} lacks — each once, in {@code named}'s order, no null id. */
    static List<Long> unlisted(List<PackageAccount> fundable, List<PackageAccount> named) {
        if (named == null || named.isEmpty()) return List.of();
        Map<Long, PackageAccount> listed = byId(fundable);
        Set<Long> missing = new java.util.LinkedHashSet<>();
        for (PackageAccount account : named) {
            Long id = account == null ? null : account.getId();
            if (id != null && !listed.containsKey(id)) missing.add(id);
        }
        return List.copyOf(missing);
    }

    private static Map<Long, PackageAccount> byId(List<PackageAccount> fundable) {
        Map<Long, PackageAccount> byId = new HashMap<>();
        for (PackageAccount account : fundable) if (account != null && account.getId() != null) byId.putIfAbsent(account.getId(), account);
        return byId;
    }

    private static void sayForeignOnce(String lane, Long id) {
        if (!SAID_FOREIGN.add(lane)) return;
        log.warn("[{}] payableAccounts named account {} that the rule does not let pay this request (expired, another unit or another partner):"
            + " dropped — a lane narrows the rule's accounts, it never widens them (S5); said once per lane", lane, id);
    }
}
