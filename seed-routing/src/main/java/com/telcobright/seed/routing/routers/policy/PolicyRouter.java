package com.telcobright.seed.routing.routers.policy;

import com.telcobright.seed.routing.api.RequestRouter;
import com.telcobright.seed.routing.api.RoutingDecision;
import com.telcobright.seed.routing.api.RoutingRefusal;
import com.telcobright.seed.routing.api.RoutingRequest;
import com.telcobright.seed.routing.policy.CompiledPolicy;
import com.telcobright.seed.routing.spi.RouteDirectory;
import com.telcobright.seed.routing.store.PolicyCatalog;

import java.util.List;

/**
 * POLICY routing: the config names a policy, the policy's type routes the request. The router holds the NAME, not
 * the policy — every request reads the catalog's current snapshot, so a saved change is in service on the next
 * request with no restart, and a request in flight finishes on the version it started with.
 */
public final class PolicyRouter implements RequestRouter {
    public static final String TYPE = "policy";

    private final String domain;
    private final String policyName;
    private final PolicyCatalog catalog;
    private final RouteDirectory directory;

    public PolicyRouter(String domain, String policyName, PolicyCatalog catalog, RouteDirectory directory) {
        if (policyName == null || policyName.isBlank()) throw new IllegalArgumentException("routing." + domain + ".policy: policy routing needs the policy's name");
        this.domain = domain;
        this.policyName = policyName.trim();
        this.catalog = catalog;
        this.directory = directory == null ? RouteDirectory.ALL_UP : directory;
    }

    @Override public String type() { return TYPE; }

    public String policyName() { return policyName; }

    @Override
    public RoutingDecision route(RoutingRequest request, boolean trace) {
        CompiledPolicy policy = catalog.get(domain, policyName);
        if (policy == null) {
            return RoutingDecision.refused(RoutingRefusal.NO_POLICY, "no policy '" + policyName + "' for " + domain + " in the store",
                TYPE, policyName, 0, null, trace ? List.of("policy '" + policyName + "' is not in the catalog") : null);
        }
        if (!policy.source().enabled()) {
            return RoutingDecision.refused(RoutingRefusal.POLICY_DISABLED, "policy '" + policyName + "' is switched off",
                TYPE, policyName, policy.source().version(), null, trace ? List.of("policy '" + policyName + "' is switched off") : null);
        }
        return policy.evaluate(request, directory, TYPE, trace);
    }
}
