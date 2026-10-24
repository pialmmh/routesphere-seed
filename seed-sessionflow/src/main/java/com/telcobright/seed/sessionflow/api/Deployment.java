package com.telcobright.seed.sessionflow.api;

import com.telcobright.seed.naming.api.TenantNames;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.internal.DeploymentReader;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What prime-context's bootstrap says this application serves (§C.1, read once at start and again when a root is added live): the roots,
 * the pool and the timers, the identification rules, each root's resources, the platform's Kafka. Refused in words when a root is served
 * without its resources, when no root is served, or when a rule names a root not served — a session it matched would have no tree.
 *
 * <p><b>No secret from the bootstrap</b> (P1-K — the owner's ruling of 2026-10-24, "Environment only", re-affirming his rule of 2026-09-24):
 * the seed reads NO secret from the envelope. A password by reference ({@code password-ref: env:<NAME>}: switch-db's, the ledger's own
 * {@code ledger.db}) is read from the PROCESS environment only, which the box's environment file fills (the secreteer); a variable not
 * there refuses the root's start in words naming it, never a value. The envelope's {@code secrets} are still parsed — {@link #secrets()},
 * deprecated, read by nobody — and a deployment's text names them, never their values.
 *
 * @param tenant         the deployment's tenant code (the envelope's {@code tenant.code}: {@code platform})
 * @param app            the application's code ({@code ad-sphere})
 * @param env            the environment ({@code bed}, {@code prod})
 * @param roots          the served roots, in the bootstrap's order
 * @param settings       the pool, the threads, statewalk's timers
 * @param zone           the CDR's wall clock ({@code config.routesphere.zone}; Asia/Dhaka when not said)
 * @param rules          every root's doors
 * @param resources      each root's own resources
 * @param platformKafka  where the platform topics are ({@code tenant_created}): {@code config.routesphere.kafka.bootstrap}, else the first root's
 * @param seedRoot       the root whose reseller tiers keep the legacy {@code res_<id>} names ({@code config.routesphere.naming.seed-root}); null = none
 * @param secrets        the envelope's secrets as parsed — read by NOBODY (P1-K: a password comes from the process environment only)
 * @param etag           the envelope's version
 * @param pendingRoot    how a root not served yet is tried again ({@code config.routesphere.pending-root}; P1-G)
 * @param seedPrefix     the seed root's tier prefix ({@code config.routesphere.naming.seed-prefix}; P1-G); null = the legacy {@code res_}
 */
public record Deployment(String tenant, String app, String env, List<String> roots, RoutesphereSettings settings, ZoneId zone,
                         List<IdentificationRule> rules, Map<String, RootResources> resources, String platformKafka, String seedRoot,
                         Map<String, String> secrets, String etag, PendingRootPace pendingRoot, String seedPrefix) {

    public Deployment {
        roots = List.copyOf(roots);
        rules = List.copyOf(rules);
        resources = Map.copyOf(resources);
        secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
        pendingRoot = pendingRoot == null ? PendingRootPace.DEFAULT : pendingRoot;
    }

    /** The twelve words of P1-E, kept (P1-G): a root not served yet is tried at {@link PendingRootPace#DEFAULT}. */
    public Deployment(String tenant, String app, String env, List<String> roots, RoutesphereSettings settings, ZoneId zone,
                      List<IdentificationRule> rules, Map<String, RootResources> resources, String platformKafka, String seedRoot,
                      Map<String, String> secrets, String etag) {
        this(tenant, app, env, roots, settings, zone, rules, resources, platformKafka, seedRoot, secrets, etag, PendingRootPace.DEFAULT, null);
    }

    /**
     * The envelope's {@code secrets} as the reader parsed them — read by nobody in the seed.
     *
     * @deprecated P1-K (the owner's ruling of 2026-10-24, "Environment only"): the seed reads no secret from the bootstrap; a password by
     *             reference ({@code env:<NAME>}) comes from the process environment only — the box's environment file, which the secreteer
     *             fills. Kept for the record's signature (the reader still parses the envelope's {@code secrets}); never read a credential
     *             here.
     */
    @Deprecated
    public Map<String, String> secrets() { return secrets; }

    /** P1-K · Never a secret's value in a log or a message: the envelope's secrets by their NAMES only; every other part as a record says it. */
    @Override
    public String toString() {
        return "Deployment[tenant=" + tenant + ", app=" + app + ", env=" + env + ", roots=" + roots + ", settings=" + settings + ", zone=" + zone
            + ", rules=" + rules + ", resources=" + resources + ", platformKafka=" + platformKafka + ", seedRoot=" + seedRoot
            + ", secrets (names only)=" + secrets.keySet() + ", etag=" + etag + ", pendingRoot=" + pendingRoot + ", seedPrefix=" + seedPrefix + "]";
    }

    /** The deployment of {@code app} in this envelope; refused in words when the envelope serves another application or says too little. */
    public static Deployment of(Map<String, Object> envelope, String app) { return DeploymentReader.read(envelope, app); }

    /** One served root's resources; empty when the root is not served. */
    public Optional<RootResources> root(String root) { return Optional.ofNullable(resources.get(root)); }

    /** How this deployment's reseller tiers are named (R9): {@code <root>_res_<id>}, the seed root's under its prefix by the bootstrap's word. */
    public TenantNames tenantNames() { return TenantNames.withSeedRoot(seedRoot, seedPrefix); }

    /** The rules of one root's doors. */
    public List<IdentificationRule> rulesOf(String root) { return rules.stream().filter(r -> r.tenant().equals(root)).toList(); }
}
