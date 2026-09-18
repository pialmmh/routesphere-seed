package com.telcobright.seed.routing.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.telcobright.seed.routing.api.RoutingDecision;
import com.telcobright.seed.routing.api.RoutingRefusal;
import com.telcobright.seed.routing.api.RoutingRequest;
import com.telcobright.seed.routing.policy.CompiledPolicy;
import com.telcobright.seed.routing.policy.PolicyFormatException;
import com.telcobright.seed.routing.policy.PolicyType;
import com.telcobright.seed.routing.policy.PolicyTypes;
import com.telcobright.seed.routing.policy.RoutingPolicy;
import com.telcobright.seed.routing.spi.PolicyStore;
import com.telcobright.seed.routing.spi.RouteDirectory;
import com.telcobright.seed.routing.store.PolicyCatalog;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Everything a SCREEN for routing policies will need, as plain calls — the product wraps them in its own REST
 * resource behind its own officer gate (the library knows no HTTP): list, read, CHECK a draft without saving it,
 * save (a new version, never a silent overwrite), switch on/off, delete, the history, SIMULATE a request against
 * the policy in service or against an unsaved draft (with the trace of every rule looked at), and the type
 * descriptors a screen draws its editor from.
 */
public final class PolicyAdmin {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The result of checking a draft: a format error (with its path), or the warnings and the routes it names. */
    public record Checked(boolean ok, String error, List<String> warnings, Set<String> routes) {}

    public record Saved(RoutingPolicy policy, List<String> warnings) {}

    private final PolicyStore store;
    private final PolicyCatalog catalog;
    private final PolicyTypes types;
    private final RouteDirectory directory;

    public PolicyAdmin(PolicyStore store, PolicyCatalog catalog, PolicyTypes types, RouteDirectory directory) {
        this.store = store;
        this.catalog = catalog;
        this.types = types;
        this.directory = directory == null ? RouteDirectory.ALL_UP : directory;
    }

    public List<RoutingPolicy> list(String domain) { return store.list(domain); }

    public Optional<RoutingPolicy> get(String domain, String name) { return store.find(domain, name); }

    public List<RoutingPolicy> history(String domain, String name, int limit) { return store.history(domain, name, limit); }

    /** Compile and lint a draft; nothing is saved. */
    public Checked check(RoutingPolicy draft) {
        try {
            CompiledPolicy compiled = types.compile(draft);
            return new Checked(true, null, compiled.warnings(directory), compiled.routeNames());
        } catch (PolicyFormatException e) {
            return new Checked(false, e.getMessage(), List.of(), Set.of());
        }
    }

    /**
     * Save a draft as the next version. {@code draft.version()} is the version the editor started from (0 =
     * create). A document that does not compile is refused with {@link PolicyFormatException}; a stale version
     * with {@code PolicyConflictException}. The catalog reloads at once, so the change is in service on return.
     */
    public Saved save(RoutingPolicy draft, String by) {
        CompiledPolicy compiled = types.compile(draft);
        RoutingPolicy saved = store.save(draft, by);
        catalog.reload();
        return new Saved(saved, compiled.warnings(directory));
    }

    /** Switch a policy on or off — a new version like any other change. */
    public Optional<Saved> enable(String domain, String name, boolean on, String by) {
        return store.find(domain, name).map(p -> p.enabled() == on ? new Saved(p, List.of()) : save(p.withEnabled(on), by));
    }

    public boolean delete(String domain, String name, String by) {
        boolean had = store.delete(domain, name, by);
        if (had) catalog.reload();
        return had;
    }

    /** Route a made-up request through the policy IN SERVICE, with the trace. Nothing is sent anywhere. */
    public RoutingDecision simulate(String domain, String name, RoutingRequest request) {
        CompiledPolicy policy = catalog.get(domain, name);
        if (policy == null) {
            return RoutingDecision.refused(RoutingRefusal.NO_POLICY, "no policy '" + name + "' for " + domain + " in service", "policy", name, 0, null,
                List.of("policy '" + name + "' is not in the catalog"));
        }
        return policy.evaluate(request, directory, "policy", true);
    }

    /** Route a made-up request through an UNSAVED draft — "what would this change do?" before the save. */
    public RoutingDecision simulateDraft(RoutingPolicy draft, RoutingRequest request) {
        return types.compile(draft).evaluate(request, directory, "policy", true);
    }

    /** The type descriptors: what a screen needs to draw an editor for each policy type. */
    public JsonNode typeDescriptors() {
        ArrayNode out = JSON.createArrayNode();
        for (PolicyType t : types.all()) out.add(t.descriptor());
        return out;
    }

    /** What is in service now, and what is stored but does not compile. */
    public PolicyCatalog.Snapshot inService() { return catalog.snapshot(); }

    public boolean reload() { return catalog.reload(); }
}
