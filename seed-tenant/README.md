# seed-tenant — the tenant trees every product walks

The owner, 2026-10-09: "each sibling level tenant will have own dynamic context in a hashmap; the nested multi-level resellers will
have own dynamicContext, realized by the hierarchy; loads config dynamically through prime-context, sense a db change through kafka
topic notification" — and "we will use the same code and same data structure in the base library for tenant and multi-level context
loading mechanism, kafka connection; ad, call etc may have separate maven projects, but reuse the existing code."

This module is that code. It is routesphere-core's shape (`TenantHierarchyInitializer.tenantConfigs` + `ConfigEventConsumer`) on
the seed's cache (`seed-context`'s `TenantContexts`). The v2 call switch keeps its own loader (live on CCL; no step of the working
call changes); the ad and the WiFi depend on this.

## The structure

```
TenantTrees  (api)  = TenantContexts<Tenant>  ──►  the hashmap   tenantId → Snapshot(version, Tenant tree)
    "btcl"  → Snapshot(v3, tree)      a sibling tenant
    "tele2" → Snapshot(v1, tree)      another, its own tree

tree = Tenant "btcl"  ── context: DynamicContext (btcl's partners, plans, accounts)
         └─ Tenant "res_45"   ── context: DynamicContext (res_45's own)          the nested resellers, any depth
              └─ Tenant "res_45_7" ── context: DynamicContext (res_45_7's own)
       + tenantIndex  (flat: dbName → node, shared by the tree)
       + ancestorChain on each node (leaf → root — walked by each node's parent NAME: the wire carries it)
```

`TenantTrees` is the session pipeline's `TenantLookup`: `root` · `tenantOfPartner` · `tenantByDbName`, every answer from the CURRENT
snapshot, each inside ONE served tenant's tree (a partner id is unique inside one tree only). Plus `entryIn(tenant, partnerId)` → the
base's `EntryPartner`, and `tiersHoldingTwice`: an id two tiers of one tree hold names nobody (D5), one WARN at the map's build, and the
partner map is built once per snapshot, never per session.

## The lifecycle

```
INIT       directory.active() → the served tenants (the gateway document / application.properties — never a guess)
           source.fetch(tenant) → prime-context POST /get-specific-tenant-root?name=  → Trees.ready: index + chains → Snapshot v1
           the Kafka doorbell subscribes config_event_loader_<tenant> for every served tenant; the backstop timer is armed
SERVE      a session reads the current tree and keeps its tiers for its whole life; an unknown tenant is absent
RELOAD     a DB write → prime-context rebuilds → a record on config_event_loader_<tenant> (the content is ignored, the topic names
           the tenant) → debounce → fetch → Snapshot v2 swapped whole; a failed fetch keeps v1 (ContextEvent.LoadFailed)
           the same road: Doorbell.ring() after a product's own write (local first, then the record), reload() from an admin road, the backstop
DIRECTORY  refreshDirectory(): new tenants loaded, gone ones dropped, the doorbell re-subscribes
CLOSE      the consumer closed, the map dropped
```

## Packages

```
api/            TenantTrees (the map + the lookup) · Doorbell (the ringer)
spi/            TreeSource (where a tree comes from; TreeUnavailable)
dependencies/   TenantTreesBuilder (directory · source | primeContext · doorbell · listener · debounce · backstop · loadTimeout)
internal/       Trees (parse + ready, one place) · PrimeContextTreeSource (the HTTP road) · PartnerTiers (the D5 map) · KafkaDoorbell
testkit/        ScriptedTrees (a test's trees, swappable, failing, counted)
```

```java
TenantTrees trees = TenantTrees.builder()
    .directory(document::tenantIds)
    .primeContext("http://10.10.188.2:7091", Duration.ofSeconds(5), null)
    .doorbell("10.10.188.2:9092", "wifi-sphere-trees")
    .build().start();
SessionFlowKit kit = SessionFlowKit.builder().tenants(trees)…;          // the pipeline's TenantLookup
Doorbell bell = Doorbell.kafka(trees, "10.10.188.2:9092", "wifi-sphere"); // after an own write: bell.ring("btcl", "site 7 → res_45")
```

Tests: `mvn -o test` in this module; the stories are in `TenantTreesTest`, `TreesTest`, `DoorbellTest`, each file's header naming the
rule it breaks once.
