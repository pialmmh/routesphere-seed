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
base's `EntryPartner`, and `tiersHoldingTwice`: an id two tiers of one tree hold names nobody (D5).

**Built once per snapshot, never per session** (P1-O — true since then): every loaded tree is INSTALLED in the same step that swaps it in —
seed-context's cell calls the trees' `SnapshotIndexer` (`internal/RootInstall`), which builds the base's `RootTree` with its `RootIndex`
(partner id, exact name, route key, route user name, SIP account, prefix → the tier and the partner; seed-sessionflow README §4.0) and the
D5 map, and the snapshot carries them (`InstalledRoot`). `snapshot(root)` hands every session that same instance; a served root named by its
code or its database name is found by one get (the install keeps both), never by a walk of the served roots. `rootIndexBuilds()` (and
`walks()`) count the installs: roots × loads. An index that cannot be built fails that load — the last good snapshot stays.

## The lifecycle

```
INIT       directory.active() → the served tenants (the gateway document / application.properties — never a guess)
           source.fetch(tenant) → prime-context POST /get-specific-tenant-root?name=  → Trees.ready: index + chains → installed (RootTree +
           its RootIndex, built once, P1-O) → Snapshot v1
           the Kafka doorbell subscribes config_event_loader_<tenant> for every served tenant; the backstop timer is armed
SERVE      a session reads the current tree and keeps its tiers for its whole life; an unknown tenant is absent
RELOAD     a DB write → prime-context rebuilds → a record on config_event_loader_<tenant> (the content is ignored, the topic names
           the tenant) → debounce → fetch → installed → Snapshot v2 swapped whole; a failed fetch (or index) keeps v1 (ContextEvent.LoadFailed)
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
                RootInstall (the install: RootTree + its index + the D5 map, per load) · InstalledRoot
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

The deployed trees of a kit made from the bootstrap (`PrimeContextTrees`) ask the tree road with the bootstrap's own token (P1-O:
`Authorization: Bearer …`, the SAME token the envelope is asked with, read at every ask; no header when the kit has none) — so
prime-context may close its tree roads to callers without it (PC-0025 item 3). The token is never printed.

Tests: `mvn -o test` in this module; the stories are in `TenantTreesTest`, `TreesTest`, `DoorbellTest`, `IndexPerSnapshotTest`,
`TreeRoadBearerTest`, each file's header naming the rule it breaks once.
