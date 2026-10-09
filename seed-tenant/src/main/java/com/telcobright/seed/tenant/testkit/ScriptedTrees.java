package com.telcobright.seed.tenant.testkit;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.tenant.spi.TreeSource;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** A test's tree source: one tree per tenant, swappable between loads, a tenant can be scripted to fail, every load counted. */
public final class ScriptedTrees implements TreeSource {

    private final Map<String, Tenant> byTenant = new ConcurrentHashMap<>();
    private final Map<String, Integer> loads = new ConcurrentHashMap<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();

    public ScriptedTrees serve(String tenantId, Tenant tree) { byTenant.put(tenantId, tree); return this; }
    public ScriptedTrees failing(String tenantId) { failing.add(tenantId); return this; }
    public ScriptedTrees recover(String tenantId) { failing.remove(tenantId); return this; }
    public int loads(String tenantId) { return loads.getOrDefault(tenantId, 0); }

    @Override
    public Tenant fetch(String tenantName) {
        loads.merge(tenantName, 1, Integer::sum);
        if (failing.contains(tenantName)) throw new TreeUnavailable("prime-context did not answer for " + tenantName + " (scripted)");
        Tenant t = byTenant.get(tenantName);
        if (t == null) throw new TreeUnavailable("no tree for " + tenantName + " (scripted)");
        return t;
    }
}
