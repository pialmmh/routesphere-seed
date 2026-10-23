package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.IdentificationRule;
import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.dependencies.TenantResolvers;
import com.telcobright.seed.sessionflow.spi.TenantResolver;

import java.util.List;
import java.util.Optional;

/** The identification rules of a kit made from the bootstrap: every served root's doors, swapped whole when a root is added live (R10). */
public final class LiveRules implements TenantResolver {

    private volatile TenantResolver current;
    private volatile List<IdentificationRule> rules;

    public LiveRules(List<IdentificationRule> rules) { replace(rules); }

    /** The rules from now on (a session that already found its root keeps it). */
    public void replace(List<IdentificationRule> next) {
        TenantResolver resolver = TenantResolvers.ofRules(next);
        this.rules = List.copyOf(next);
        this.current = resolver;
    }

    public List<IdentificationRule> rules() { return rules; }

    @Override public Optional<String> tenantOf(Ingress ingress) { return current.tenantOf(ingress); }
}
