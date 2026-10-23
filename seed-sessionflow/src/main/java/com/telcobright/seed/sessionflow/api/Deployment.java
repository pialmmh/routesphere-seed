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
 * @param secrets        the envelope's secrets (a password by reference reads them after the environment)
 * @param etag           the envelope's version
 */
public record Deployment(String tenant, String app, String env, List<String> roots, RoutesphereSettings settings, ZoneId zone,
                         List<IdentificationRule> rules, Map<String, RootResources> resources, String platformKafka, String seedRoot,
                         Map<String, String> secrets, String etag) {

    public Deployment {
        roots = List.copyOf(roots);
        rules = List.copyOf(rules);
        resources = Map.copyOf(resources);
        secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
    }

    /** The deployment of {@code app} in this envelope; refused in words when the envelope serves another application or says too little. */
    public static Deployment of(Map<String, Object> envelope, String app) { return DeploymentReader.read(envelope, app); }

    /** One served root's resources; empty when the root is not served. */
    public Optional<RootResources> root(String root) { return Optional.ofNullable(resources.get(root)); }

    /** How this deployment's reseller tiers are named (R9): {@code <root>_res_<id>}, the seed root's legacy {@code res_<id>} by the bootstrap's word. */
    public TenantNames tenantNames() { return TenantNames.withSeedRoot(seedRoot); }

    /** The rules of one root's doors. */
    public List<IdentificationRule> rulesOf(String root) { return rules.stream().filter(r -> r.tenant().equals(root)).toList(); }
}
