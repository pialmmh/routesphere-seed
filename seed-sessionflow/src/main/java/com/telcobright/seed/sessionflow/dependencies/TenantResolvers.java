package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.seed.sessionflow.api.IdentificationRule;
import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.spi.TenantResolver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The resolvers a product can choose from (ARCH-0077-A item 8). {@link #ofRules}: an EXACT match on (kind, match) — no match is empty,
 * NEVER a default; two rules on one (kind, match) refuse the build in words. The rules come from the facade's bootstrap
 * ({@link IdentificationRules#fromBootstrap}); a lane's step (a) {@code identifyTenant} asks the resolver (through the base's helpers) with the session's
 * {@code Ingress} and the answer is the session's ROOT ({@code RoutesphereKit.builder().identification(rules)} also checks, at load, that every
 * rule names a root this process serves).
 */
public final class TenantResolvers {

    private TenantResolvers() {}

    public static TenantResolver ofRules(List<IdentificationRule> rules) {
        Map<Ingress, IdentificationRule> byFact = new HashMap<>();
        for (IdentificationRule rule : rules) {
            IdentificationRule twice = byFact.putIfAbsent(new Ingress(rule.kind(), rule.match()), rule);
            if (twice != null) throw new IllegalArgumentException("two identification rules name the same request: " + twice + " and " + rule);
        }
        return ingress -> ingress == null ? Optional.empty() : Optional.ofNullable(byFact.get(ingress)).map(IdentificationRule::tenant);
    }

    /** No rule: every request is nobody's (a product that names its tenant another way). */
    public static TenantResolver none() { return ingress -> Optional.empty(); }
}
