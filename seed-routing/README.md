# seed-routing — the common request routing

One mechanism for every Telcobright switch: a **request** (incoming partner + app/source + attributes) is sent to a
**group of outgoing routes** by a **router that config picks**. Call and SMS route by **dialplan** (the default);
any domain switches to **policy routing** by naming a policy in its profile. Payment uses it first; ad is next.

```
 request ──► RequestRouter (config picks the type)
              ├─ dialplan   source → longest prefix → dialplan by percent → routes by priority ─┐
              └─ policy     routing.<domain>.policy: <name>                                      │
                              └─ RoutingPolicy (DB entity, ONE JSON document)                    │
                                   └─ PolicyType = the bean the entity's `type` activates        │
                                        └─ match-loadbalance: rules → route group ───────────────┤
                                                                                                 ▼
                                   route GROUP → tiers (failover) → strategy (weighted | hashed | round-robin | ordered)
                                                                                                 ▼
                         RoutingDecision: the pick + the failover order + router / policy@version / rule (+ trace)
```

Pure Java 21 (jackson + slf4j). It never imports a product; a Quarkus or Spring host wires it with one producer.

## The entity

`routing_policy(id, domain, name, type, enabled, version, description, policy_json, updated_at, updated_by)` —
`UNIQUE(domain, name)`; `policy_json` is **jsonb** on PostgreSQL and **json** on MySQL. Every save is a new
`version`; the old document stays in `routing_policy_history`. `JdbcPolicyStore.ensureSchema()` creates both
tables (idempotent). The version rides on every decision, so a record can say *why* months later.

## The first policy type: `match-loadbalance`

```json
{ "schema": 1,
  "rules": [
    { "name": "btcl-wifi-retail", "priority": 100,
      "match":  { "partner": "btcl", "app": "wifi-retail", "zone": "*" },
      "routes": [ { "route": "bkash", "weight": 100 } ],
      "strategy": "weighted" } ],
  "default": { "reject": "no-rule-matched" } }
```

- Rules are tried by `priority` (high first), then the more specific, then the name — never the typing order.
- Match forms: `"btcl"` · `"*"` · `["a","b"]` · `{"prefix":["88017"]}` · `{"range":[10,500]}` · `{"present":false}` · `{"not":…}`.
- A group: `weight` = the share inside a tier, `tier` = failover order. A route that is DOWN or unknown is never offered.
- Compiled once per version and indexed by the attribute that leaves the fewest rules to look at: 200 000 decisions
  over 10 000 rules take ~0.7 s on a laptop.
- A wrong document says where: `rules[0].routes[1].weight: must be above 0 …`.

A new way of routing = a new `PolicyType` in its own package + `PolicyTypes.register(...)`. No table changes.

## Config (flat keys under `routing.` — any config system)

```yaml
routing:
  payment:
    policy: btcl-wifi-retail      # a name switches policy routing on; blank = the domain's default
  sms:
    policy: ""                    # blank: dialplan (the default of call and SMS)
  store:    { kind: jdbc, table: routing_policy, reload-seconds: 30 }   # or kind: config (memory only)
  seed:     { mode: sync }        # sync = this file is the truth | if-absent = the store is | off
  policies:
    btcl-wifi-retail:
      domain: payment
      type: match-loadbalance
      document: |
        { "schema": 1, "rules": [ … ] }
```

## Wiring (a host)

```java
PolicyTypes types   = PolicyTypes.defaults();                       // + host types
PolicyStore store   = new JdbcPolicyStore(dataSource).ensureSchema();  // or new InMemoryPolicyStore()
RoutingSettings cfg = RoutingSettings.from(flatKeysUnderRouting);
PolicySeeder.seed(store, cfg.declaredPolicies(), cfg.seedMode(), types);
PolicyCatalog catalog = new PolicyCatalog(store, types).start(cfg.store().reloadSeconds());
RequestRouter router  = RequestRouters.standard().create("payment", cfg, RequestRouters.POLICY,
                            new RequestRouters.Parts(catalog, routeDirectory, dialplanSource));

RoutingDecision d = router.route(RoutingRequest.of("payment")
        .with("partner", "btcl").with("app", "wifi-retail").with("zone", "uttara").with("method", "bkash").build());
```

The host gives three small things: a `RouteDirectory` (does this route exist, is it UP), for dialplan a
`DialplanSource` (its in-memory prefix / dialplan / route tables), and a `DataSource` for the JDBC store. The
config doorbell (`seed-config-client`) calls `catalog.reload()`; without it the slow poll finds a change.

`PolicyAdmin` is the backend of a future screen: list · read · **check** a draft · save (optimistic version) ·
enable · delete · history · **simulate** (stored or draft, with the trace) · type descriptors.

## Tests

`mvn test` — 30 tests (H2). The JSON column for real, against throwaway databases:

```bash
mvn test -Dtest=RealDatabaseStoreTest \
  -Dseed.routing.pg.url="jdbc:postgresql://127.0.0.1:7432/routing?user=…&password=…" \
  -Dseed.routing.mysql.url="jdbc:mysql://127.0.0.1:7306/routing?user=…&password=…"
```
