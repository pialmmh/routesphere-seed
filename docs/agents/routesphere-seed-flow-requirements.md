# routesphere-seed-flow — the framework: requirements (the owner, 2026-10-23)

Written 2026-10-23 ~22:10 +06 by the architect from the owner's rulings of the same evening. This page is the
requirements record; the design pages (`~/arch-drafts/launch/session-supervisor/routesphere-flow.html`,
`routesphere/docs/architecture/ad-is-a-call.md`) and the briefs follow it. Twin of the Trilium note under
*Routesphere → routesphere-seed-flow*.

## 0 · What the seed is

`routesphere-seed` (module `seed-sessionflow`, class `RoutesphereFlow`) is a **framework library**. It is never a
standalone Quarkus service. The applications — **routesphere** (call, SMS), **ad-sphere** (ads), **wifi-sphere**
(WiFi sessions) — embed it and extend it.

```
statewalk 4.1          the states, their order, timers, pools, rows, restore, the outbox (a library)
routesphere-seed       RoutesphereFlow: the lifecycle (a)–(g), the money, the CDR road, the tenants (a framework)
application            one package per app: facts, flow, supervisor, signaling machine, frames, context
```

The owner's sentence: *"all new applications/flows shall extend routesphere flow, which shall be in the
routesphere-seed lib; each flow shall implement its own supervisor (CallSupervisor, AdSupervisor, SmsSupervisor),
all on statewalk's registry, with as much code re-use as possible."*

## 1 · The extension contract (R1–R6)

**R1 · Small, readable, incremental.** Adding an application = a handful of small methods, each answering one
question. The owner: *"root level tenant identification logic will vary for call, sms, wifi; we need to add support
for new applications with readable small incremental code — new implementations of identifyTenant(),
identifyPartner() …"*

**R2 · The forced methods** (abstract in the base; every lane must write them):

| Method | The question | Call | Ad | WiFi | SMS |
|---|---|---|---|---|---|
| `identifyTenant(facts)` | which root operator owns this door? | by the ESL socket | by the HTTP listener | by the VLAN | by the bind / listener |
| `identifyPartner(ctx)` | who pays at the leaf? | the caller's partner (ip / prefix) | the chosen campaign's advertiser | the subscriber | the bind's account |
| `rateAtLevel(ctx, tier, partner)` | what does this tier charge; how much is reserved? | one talk minute | a view, or a second | a data window | one message |
| `routing(ctx)` | where does it go, in failover order? | dialplan hops | the creative | the gateway | the SMSCs |
| `delivered(ctx)` | did the service happen? | answered | shown | answered | delivery report |
| `refundPolicy(ctx)` | what does a non-delivered session pay? | nothing | the unshown rule (config) | nothing | nothing (refund) |
| `rerouteActionFor(cause)` | retry same, next hop, or stop? | the v1 table | stop | stop | congestion → next |

`identifyTenant` and `identifyPartner` are the owner's names; the base's one-line helpers do the common lookups
(`byListener`, `byEslSocket`, `byVlan`, `byTopic` against prime-context's identification rules; `byPartnerId`,
`bySubscriber`, `byIpOrPrefix`, `bySystemId` inside the session's own tree). `null` = refused by the base
(`NO_TENANT`, `PARTNER_NOT_FOUND`).

**R3 · Final in the base** (no lane may change): the order of the steps; the reseller walk leaf → root inside the
session's own tree; reserve / renew / finalize through the one ledger (idempotent per session and tier); the retry
ritual; the finish (teardown, slot, CDR per tier, task close, SDR, the machines back to the pool); the CDR road.

**R4 · Defaults** (the call's answer; a lane overrides only when it differs): the partner above the leaf (`res_<id>`
rule), the candidates (one: itself), the admission gates, `billedUnits`, the budget renewal, the CDR fields, the task
close words, the SDR (none).

**R5 · Builder syntax.** *"For defining a new routesphere application we shall use builder syntax like the new
statewalk implementations."* `RoutesphereApp.builder("SmsSupervisor").facts(…).identifyTenant(f -> …)
.identifyPartner(…).rateAtLevel(…).routing(…).delivered(…).refundPolicy(…).rerouteActionFor(…)
.signaling(spec).channel(channel, decoder).build()` — the same questions as lambdas. Big lanes subclass; small
lanes use the builder. *"Method overrides, or when applicable, a callback contained by the domain classes of the
specific application within the package."*

**R6 · One package per application.** *"A new domain or application shall have all its code in one package/folder,
sub-folders if the domain logic is extensive."*

```
com.telcobright.<app>/   <App>Facts · <App>Flow · <App>Supervisor · <App>Signaling · <App>Frames · <App>Context
```

The supervisor class is thin — the name and the spec; statewalk's `SessionSupervisor` stays final (the 2026-10-05
ruling: the states and the money contract are the same in every lane).

## 2 · Multi-tenancy (R7–R11)

**R7 · Any number of root tenants per application**, started through prime-context's config. link3 and btcl are
two roots of the same application. Each root holds its own tree: zero or many resellers at any depth, each with its
own database/schema, clients under them.

**R8 · The application's own yml holds ONE bootstrap url.** *"routesphere-seed/flow shall fetch all config through
the prime-context bootstrap url; its own yml config shall contain only a bootstrap url."* Everything else — the served
roots, the identification rules, the trees, Kafka, Redis, the ledger, pools, timings, the CDR topic — comes from
`GET /api/v1/bootstrap/{tenant}/{app}`. *"For integrating each application, the developer or the agent adds the
necessary services in the prime-context code base and tests the integration."*

**R9 · Naming conventions — the seed resolves them.**

| Thing | Convention (decided as recommended, 2026-10-23) |
|---|---|
| root database / schema | the root's name: `link3`, `btcl` |
| reseller tier | `<root>_res_<partnerId>`; nested: `_<partnerId>` per level (`link3_res_5`, `link3_res_5_2`). The id is the partner id in the parent's database — the call switch's live rule reads it at the end of the name. The seed root btcl keeps the legacy `res_<id>`. A word like *reseller1* is a display name, never the database name |
| platform topics (Kafka) | `<name>_<root>`: `cdr_<root>`, `cdr_summary_ping_<root>`, `config_event_loader_<root>` — unchanged, so billing-core and summary-service need no change |
| an application's own topics | `<app>_<name>_<root>`; the direction in the name (`…_in_…` / `…_out_…`); publish and subscribe never share a topic |
| Redis (the topic equivalent) | keys / streams `<root>:<app>:<name>` |
| auto-creation | the seed creates the topics (and Redis keys' namespaces) at service start for every served root, and again when a root is added live |

**R10 · A tenant created dynamically.** Online registration → prime-context provisions (database/schema, tables,
partner rows, grants) → a Kafka notification *tenant created* → every running application that serves that
application kind loads the new root live (the doorbell), creates its topics, starts serving. No restart.

**R11 · Provisioning moves to prime-context.** Today RTC-Manager / TelcoREST creates reseller and client databases
and their partner rows, with its own UI. *"Those functions (api and ui) shall be moved to the tenant side."* The api
moves into prime-context (its provisioning road already makes PostgreSQL schemas); the UI into the one tenant GUI
(the owner's 2026-10-10 ruling). The BSS agents and the prime-context agent receive written instructions
(`docs/architecture/instructions/`).

## 3 · Prepaid (R12)

**R12 · Strictly package-account + mem-ledger.** *"Our prepaid part is strictly based on package-account and
mem-ledger cache. Prepaid reserve and finalize balance shall be one reusable piece inside the flow: a reserve api and
a finalize api; finalize computes the cost of the session, refunds the reserve and makes the final adjustment (the
current call flow already does this). For some applications, e.g. SMS, finalize just returns the balance for a
failed message."* The base already holds this once (`reserveBalance` → `finalizeCost`: delivered → rate × units,
the rest back; not delivered → the lane's refund rule). The default ledger becomes the in-switch one
(`seed-switch-ledger`: mem-ledger + package accounts); the ad lane moves to it from orchestrix's REST roads.

## 4 · Records: the CDR road (R13)

**R13 · Every switch publishes its CDR to Kafka; billing-core writes.** The owner's correction of 2026-10-23:
*"routesphere, ad-sphere, wifi-sphere all shall generate the CDR into Kafka (see the ESL call's finalizing: balance,
CDR generation). The billing service (Java) generates the final normalized CDR and writes the input for the summary
data in one transaction, then notifies the summary service through Kafka; the summary service generates the hourly
and daily summaries. Billing and summary get new logic beans close to the current call processing, extended to the
new services."*

```
switch ─ CDR per tier ─▶ Kafka cdr_<root> ─▶ billing-core: normalize · cdr + acc_chargeable + summary_affected in ONE tx · ping
                                                        └─▶ summary-service: hourly / daily sums
```

- billing-core and summary-service get **one processor bean per kind** (call, ad, wifi, later sms), the call's as
  the model, switched on per tenant by config (`kinds: call, ad, wifi`). Today the ad (group 30) and WiFi (group
  101) logic exists in both on branch `postgres-ad-call`; it is to be shaped into beans and merged to master.
- The *records road* of P1-R (the switch writing `cdr` and `summary_affected` itself) was the architect's misreading
  and is removed. The 2026-10-02 rule stands: never write CDRs from a switch.

## 5 · The first target and the order of work

**First target: ad-sphere refactored onto the framework, with full prime-context integration.**

| # | Round | Repository | Content |
|---|---|---|---|
| 1 | undo | routesphere-seed | the records road removed; `cdrStatements` no longer a hook |
| 2 | framework | routesphere-seed | R1–R6 (forced methods, helpers, builder, supervisors, one package), R8 (the kit from the bootstrap url), R9 (naming + auto-create), R10 (the tenant-created handler), R12 (the switch ledger default) |
| 3 | prime-context | prime-context | the bootstrap serves the whole app config; the provisioning api from TelcoREST; the tenant-created event; the MySQL twin |
| 4 | BSS | portal-api / the BSS owner | registration and tenant screens on prime-context's roads (instructions only from here) |
| 5 | billing + summary | billing-core, summary-service | one bean per kind by config; `postgres-ad-call` → master; the MySQL span key before voice |
| 6 | P2 | ad-sphere | the ad on the framework: one package, own supervisor, the answer at `/shown`, Kafka CDR, the switch ledger, config from the bootstrap url only |
| 7 | voice, SMS | routesphere | the same, after the ad |

Rounds 2, 3 and 5 run in parallel (different repositories, one Maven lock on the build PC). P2 starts when round 2's
contract items are merged, so the ad is ported once.

## 6 · Decided as recommended (the owner, "continue as you recommend", 2026-10-23)

1. Database names by partner id (R9), words are display names.
2. The topic convention of R9; Redis as `<root>:<app>:<name>`.
3. The ad's prepaid moves to the switch ledger inside P2 (after the base port, its own item).
4. Framework + prime-context first, then P2.
5. The records road removed.
6. The ad's SDR stays in the report database (`wifi_report.ad_sdr`) and on its Kafka envelope, written by ad-sphere's
   own small writer (sphere-kit's SDR classes depend on statewalk 3 and leave the ad's path).

The owner may overrule any line here; a change is written into this page first.
