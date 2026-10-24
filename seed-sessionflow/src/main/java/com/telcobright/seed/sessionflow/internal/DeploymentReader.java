package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.naming.api.TenantNames;
import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.api.PendingRootPace;
import com.telcobright.seed.sessionflow.api.IdentificationRule;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.dependencies.IdentificationRules;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.statewalk.session.SessionSupervisor;
import com.telcobright.statewalk.session.SessionTimings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Reads §C.1's {@code config.routesphere} into a {@link Deployment}, saying in words what is missing. The pool, the threads and the
 * timers fall back to the base's defaults; the ledger's kind to {@code switch} (R12); the zone to Asia/Dhaka. statewalk's ROUTING timer is
 * fixed (1 s): a {@code routingSec} the bootstrap gives is noted, never applied.
 */
public final class DeploymentReader {

    private static final Logger log = LoggerFactory.getLogger(DeploymentReader.class);

    public static final String KEY = "routesphere";
    private static final java.util.concurrent.atomic.AtomicBoolean SAID_THE_LEGACY_PREFIX = new java.util.concurrent.atomic.AtomicBoolean();
    static final String DEFAULT_ZONE = "Asia/Dhaka";

    private DeploymentReader() {}

    public static Deployment read(Map<String, Object> envelope, String app) {
        String served = word(map(envelope.get("app")), "code");
        if (app != null && !app.equals(served)) throw refused("the bootstrap url serves the app '" + served + "', not '" + app + "' — its yml names another app's url");
        Map<String, Object> rs = map(map(envelope.get("config")).get(KEY));
        if (rs.isEmpty()) throw refused("the bootstrap of " + served + " carries no config." + KEY + ": nothing says what it serves");
        List<String> roots = rootsOf(rs, served);
        Map<String, RootResources> resources = resourcesOf(rs, roots, served);
        return new Deployment(word(map(envelope.get("tenant")), "code"), served, word(envelope, "env"), roots, settingsOf(rs), zoneOf(rs),
            rulesOf(rs, roots, served), resources, platformKafkaOf(rs, resources, roots), word(map(rs.get("naming")), "seed-root"),
            secretsOf(envelope), word(envelope, "etag"), pendingRootOf(rs), seedPrefixOf(map(rs.get("naming"))));
    }

    /**
     * P1-G · {@code naming.seed-prefix} (PC-0022 §9.1: prime-context's {@code primecontext.reseller-db-prefix}): the legacy {@code res_} when not
     * said — said once when a seed root is named without it; a value that is not a name prefix refused here, in words.
     */
    private static String seedPrefixOf(Map<String, Object> naming) {
        String prefix = word(naming, "seed-prefix");
        if (prefix == null) {
            String seed = word(naming, "seed-root");
            if (seed != null && SAID_THE_LEGACY_PREFIX.compareAndSet(false, true)) {
                log.info("the bootstrap names the seed root {} without naming.seed-prefix: its tiers keep the legacy {}", seed, TenantNames.LEGACY_PREFIX);
            }
            return null;
        }
        try {
            return TenantNames.withSeedRoot(word(naming, "seed-root"), prefix).seedPrefix();
        } catch (IllegalArgumentException notAPrefix) {
            throw refused("config." + KEY + ".naming.seed-prefix " + notAPrefix.getMessage());
        }
    }

    /** P1-G · {@code pending-root: {retry-sec, give-up-min}} — 30 s and 120 min when not said. */
    private static PendingRootPace pendingRootOf(Map<String, Object> rs) {
        Map<String, Object> pace = map(rs.get("pending-root"));
        return new PendingRootPace(number(pace, "retry-sec", PendingRootPace.DEFAULT.retrySec()), number(pace, "give-up-min", PendingRootPace.DEFAULT.giveUpMin()));
    }

    private static List<String> rootsOf(Map<String, Object> rs, String app) {
        List<String> roots = new ArrayList<>(new LinkedHashSet<>(words(rs.get("roots"))));
        if (roots.isEmpty()) throw refused("the bootstrap of " + app + " serves no root (config." + KEY + ".roots is empty): the application would serve nobody");
        return roots;
    }

    private static Map<String, RootResources> resourcesOf(Map<String, Object> rs, List<String> roots, String app) {
        Map<String, Object> blocks = map(rs.get("root"));
        Map<String, RootResources> out = new LinkedHashMap<>();
        for (String root : roots) out.put(root, resourcesOfOne(root, map(blocks.get(root)), app));
        return out;
    }

    private static RootResources resourcesOfOne(String root, Map<String, Object> block, String app) {
        String at = "config." + KEY + ".root." + root;
        if (block.isEmpty()) throw refused("the root '" + root + "' is served by " + app + " but " + at + " is missing: its CDR road, ledger and directory are not said");
        Map<String, Object> kafka = map(block.get("kafka"));
        String bootstrap = word(kafka, "bootstrap");
        if (bootstrap == null) throw refused(at + ".kafka.bootstrap is missing: the root's CDR (cdr_" + root + ") has no road");
        String dir = word(map(block.get("journal")), "dir");
        if (dir == null) throw refused(at + ".journal.dir is missing: the root's sessions in the air and its ledger's state have no place");
        Map<String, Object> db = map(block.get("switch-db"));
        RootResources.SwitchDb switchDb = db.isEmpty() ? null
            : new RootResources.SwitchDb(word(db, "url"), word(db, "schema"), word(db, "username"), word(db, "password-ref"));
        Map<String, Object> ledger = map(block.get("ledger"));
        String kind = word(ledger, "kind") == null ? RootResources.Ledger.SWITCH : word(ledger, "kind");
        return new RootResources(root, switchDb,
            new RootResources.KafkaRoad(bootstrap, (int) number(kafka, "partitions", 1), (short) number(kafka, "replication", 1)),
            word(map(block.get("redis")), "url"), new RootResources.Ledger(kind, ledger), Path.of(dir), ledgerLoginOf(at, kind, map(ledger.get("db"))));
    }

    /** P1-J · {@code ledger.db {user, password-ref[, url, schema]}}: the switch ledger's own login; absent = null (switch-db's, as before). */
    private static RootResources.SwitchDb ledgerLoginOf(String at, String kind, Map<String, Object> db) {
        if (db.isEmpty()) return null;
        if (!RootResources.Ledger.SWITCH.equals(kind)) throw refused(at + ".ledger.db is the switch ledger's own login, and this root's ledger is " + kind);
        String user = word(db, "user") != null ? word(db, "user") : word(db, "username");
        if (user == null) throw refused(at + ".ledger.db names no user: the switch ledger's own login connects as a role of its own");
        return new RootResources.SwitchDb(word(db, "url"), word(db, "schema"), user, word(db, "password-ref"));
    }

    private static List<IdentificationRule> rulesOf(Map<String, Object> rs, List<String> roots, String app) {
        List<IdentificationRule> rules = IdentificationRules.fromRoutesphere(rs);
        for (IdentificationRule rule : rules) {
            if (!roots.contains(rule.tenant())) throw refused("the identification rule " + rule + " names the root '" + rule.tenant() + "', which "
                + app + " does not serve (config." + KEY + ".roots " + roots + "): a session it matched would have no tree");
        }
        return rules;
    }

    private static RoutesphereSettings settingsOf(Map<String, Object> rs) {
        RoutesphereSettings d = RoutesphereSettings.defaults();
        Map<String, Object> t = map(rs.get("timings"));
        SessionTimings timings = new SessionTimings(number(t, "initSec", d.timings().initSec()), number(t, "admittingSec", d.timings().admittingSec()),
            number(t, "connectingSec", d.timings().connectingSec()), number(t, "activeMaxSec", d.timings().activeMaxSec()));
        noteAFixedRoutingTimer(t);
        long healthy = timings.initSec() + timings.admittingSec() + SessionSupervisor.ROUTING_SEC + timings.connectingSec() + timings.activeMaxSec();
        return new RoutesphereSettings((int) number(rs, "pool", d.pool()), (int) number(rs, "threads", d.threads()),
            number(rs, "globalTimeoutSec", healthy + 60), timings, number(rs, "reservePeriodSec", 0), number(rs, "reserveInitialDelaySec", 0),
            number(rs, "slotReconcileSec", d.slotReconcileSec()), Boolean.parseBoolean(String.valueOf(rs.getOrDefault("debug", "false"))),
            number(rs, "admissionReserveMs", d.admissionReserveMs()));
    }

    private static void noteAFixedRoutingTimer(Map<String, Object> timings) {
        long routing = number(timings, "routingSec", SessionSupervisor.ROUTING_SEC);
        if (routing != SessionSupervisor.ROUTING_SEC) {
            log.warn("the bootstrap's timings.routingSec {} is not applied: statewalk's ROUTING timer is fixed at {} s", routing, SessionSupervisor.ROUTING_SEC);
        }
    }

    private static ZoneId zoneOf(Map<String, Object> rs) {
        String zone = word(rs, "zone");
        return ZoneId.of(zone == null ? DEFAULT_ZONE : zone);
    }

    private static String platformKafkaOf(Map<String, Object> rs, Map<String, RootResources> resources, List<String> roots) {
        String own = word(map(rs.get("kafka")), "bootstrap");
        return own != null ? own : resources.get(roots.get(0)).kafka().bootstrap();
    }

    private static Map<String, String> secretsOf(Map<String, Object> envelope) {
        Map<String, String> out = new LinkedHashMap<>();
        map(envelope.get("secrets")).forEach((k, v) -> { if (v != null) out.put(k, String.valueOf(v)); });
        return out;
    }

    // ── the map's words ──

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) { return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }

    static String word(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private static List<String> words(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(v -> v != null && !String.valueOf(v).isBlank()).map(String::valueOf).toList();
    }

    private static long number(Map<String, Object> map, String key, long otherwise) {
        Object v = map.get(key);
        if (v == null) return otherwise;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw refused("config." + KEY + " '" + key + "' is not a number: " + v);
        }
    }

    private static IllegalStateException refused(String why) { return new IllegalStateException(why); }
}
