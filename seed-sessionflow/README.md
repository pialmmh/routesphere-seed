# seed-sessionflow — RoutesphereFlow, the one base flow (the framework)

One base for every kind of traffic: an ad view, a voice call, a WiFi session, an SMS. statewalk 4.1 owns the states; this module owns the
routesphere lifecycle; the application answers only its own questions (P1, design `routesphere-flow.html`; the owner's requirements
R1–R13 of 2026-10-23, `docs/agents/routesphere-seed-flow-requirements.md`). It is a framework library, never a service: routesphere (call,
SMS), ad-sphere and wifi-sphere embed it.

```
statewalk 4.1 SessionSupervisor (final):  IDLE ─► INIT ─► ADMITTING ─► ROUTING ─► CONNECTING ─► ACTIVE ─► SUCCEEDED
                                                                          │ retry? ─► ROUTING            │
                                              every refusal, timeout, failure ──────────────────────────► FAILED
RoutesphereFlow (this module)              onStart · prepare · admit · nextHop · retry · … · settle · release · record  — all FINAL
the application                            its forced answers ■ + the hooks it needs; ONE signaling machine; ONE supervisor class
```

## 1 · The classes you meet

| Class | What it is |
|---|---|
| `api.RoutesphereFlow<C>` | **The base.** statewalk's `SessionHandler<C>`: every library call implemented, final. A big lane extends it |
| `api.RoutesphereApp` | **The builder** (R5): the same questions as lambdas — a small lane is a handful of lines |
| `api.RoutesphereSupervisor<C>` | The lane's own named class (R6): `AdSupervisor`, `CallSupervisor`, `SmsSupervisor` — its name and statewalk's spec, no logic |
| `api.Lookups` | The one-line helpers: a root by its door; a payer inside the session's own tree — by id, by exact name, and (P1-O) through its ROUTE: route credentials, address, SIP account, prefix — each a get on the snapshot's index |
| `api.RootTree` / `api.RootIndex` | One root's snapshot as a session takes it, and its index: every key a partner is found by, built ONCE at the snapshot's install (P1-O) |
| `api.RoutesphereContext` | Everything one session knows (persisted: ids and amounts only). Your context extends it |
| `api.RoutesphereFacts` / `api.Ingress` | The facts your signaling machine stamps — with the INGRESS the root is found by (`listen` · `esl` · `vlan` · `topic`) |
| `api.RoutesphereEngine<C>` | statewalk's registry: your supervisor class + your signaling machine + the budget child + your channel |
| `dependencies.RoutesphereKit` | What the host hands in — by hand, or `RoutesphereKit.fromBootstrap(source, app)` from ONE bootstrap url (R8); `toBuilder()` gives that kit the lane's own sinks before it is used |
| `api.Deployment` / `api.RootResources` | What prime-context's bootstrap said: the served roots and each root's own resources |
| `api.LogText` | The ONE cleaner of what goes into a log line (P1-L, P1-M): a request's value escaped and capped, a url's password masked — a delegate of seed-logtext's |

## 2 · Write an application — one package (R6)

```
com.telcobright.<app>/    <App>Facts       the RoutesphereFacts of its door (a record of ids and words)
                          <App>Flow        its answers: a subclass of RoutesphereFlow, or a RoutesphereApp builder
                          <App>Supervisor  the named class: extends RoutesphereSupervisor<<App>Context>
                          <App>Signaling   its ONE MachineSpec — shows its SessionStart sample (.upSample), refused at start otherwise
                          <App>Frames      the wire's decoder (a frame → a Delivery)
                          <App>Context     the session's own facts (extends RoutesphereContext)
```

A big lane subclasses (the compiler asks for every ■):

```java
public final class AdFlow extends RoutesphereFlow<AdContext> {
    @Override public String name() { return "AdSupervisor"; }                                        // ■ the lane's ONE name
    @Override protected int serviceGroup(AdContext c) { return 30; }                                 // ■ the CDR's group
    @Override protected String identifyRootLevelTenant(RoutesphereFacts f) { return byListener(((AdFacts) f).listen()); }        // ■ (a)
    @Override protected Tier identifyPartner(AdContext c, Candidate k) { return byPartnerId(c, ((AdCandidate) k).payerPartnerId()); }  // ■ (c)
    @Override protected TierRate rateAtLevel(AdContext c, Tier t, Partner p) { ... }                 // ■ (e) null = UNRATED
    @Override protected RoutePlan route(AdContext c) { return RoutePlan.one(((AdCandidate) c.candidateBeingAdmitted()).creative()); }  // ■ (d)
    @Override protected boolean delivered(AdContext c, Outcome o) { return c.shown; }               // ■ the call: c.answered()
    @Override protected RefundPolicy refundPolicy(AdContext c) { return RefundPolicy.of(Rule.REFUND, unshown); }   // ■ the call: callsRule()
    @Override protected RerouteAction rerouteActionFor(String cause) { return RerouteAction.FAIL_TERMINAL; }      // ■ one hop, no retry
    // and only what differs from the call's defaults: selectCandidates, confirmAdmission, billedUnits, settleOutcome, buildSdr, …
}
public final class AdSupervisor extends RoutesphereSupervisor<AdContext> {
    public AdSupervisor(AdFlow flow) { super(flow, AdContext::new); }
}
```

A small lane uses the builder — the seven forced answers and the service group REQUIRED (`build` refuses a missing one in words), the
hooks the call's defaults when not given, the base's lookups the identify lambdas' last argument (`find`):

```java
RoutesphereFlow<SmsContext> flow = RoutesphereApp.<SmsContext>builder("SmsSupervisor")
    .serviceGroup(0)
    .identifyRootLevelTenant((facts, find) -> find.byIngress(facts.ingress()))                        // which root operator owns this door?
    .identifyPartner((sms, candidate, find) -> find.byPartnerId(sms, accounts.get(sms.user)))  // who pays at the leaf?
    .rateAtLevel((sms, tier, partner) -> rates.perSegment(tier, partner, sms.segments))        // what does this tier charge?
    .route(sms -> RoutePlan.of(smscs))                                                       // where, in failover order?
    .delivered((sms, outcome) -> "DELIVERED".equals(sms.endCause))                             // did the service happen?
    .refundPolicy(sms -> RefundPolicy.of(Rule.REFUND, Rule.REFUND))                            // what does a failed one pay?
    .rerouteActionFor(cause -> RerouteAction.FAIL_TERMINAL)                                    // retry, next hop, or stop?
    .build(kit);                                              // the sample: seed-sessionflow-samples …/sms/SmsFlow.java
```

Run it — the kit from ONE bootstrap url (R8), the engine through the lane's own class (the one door):

```java
RoutesphereKit kit = RoutesphereKit.fromBootstrap(BootstrapSources.fromYml(yml, System::getenv), "ad-sphere");
RoutesphereEngine<AdContext> engine = RoutesphereEngine.of(new AdSupervisor(new AdFlow(kit)))
    .signaling(AdPlayback.spec(...)).channel(httpChannel, AdFrames::decode).start();
```

**The ingress — two lines and the guard** (the owner's shape of 2026-10-24; the guard, P1-K): every ingress — an HTTP resource, an ESL
listener, an SMPP bind, a Kafka consumer — reads the same lines for every request:

```java
String root = engine.identifyRootLevelTenant(facts);       // 1 · which root operator owns this door: the lane's rule over the ingress
if (root == null) return rejectNoRoot(request);             //   the guard: the ingress's OWN protocol answer (an HTTP status, an ESL
                                                            //   hangup, an SMPP error) — the door has already said it and counted it
engine.launchStateMachine(new AdFacts(…, root));            // 2 · the session; its root rides in RoutesphereFacts.root()
```

**No root = refused, and said** (P1-K — the owner's ruling of 2026-10-24: "if we fail to identify the root level tenant at the ingress
points, the request shall be rejected and logs shall be generated"). When the lane's rule names no root — null, or the rule throws — the
door, for every lane, writes a WARN line (`[<app>] no root at listen 10.10.188.2:7070 (session s-1): no rule names this door — the request
is refused`: the app, the ingress's kind and value, the session id when the facts carry one, the reason in words; a failed rule's words
and its stack), counts it (`PoolStats.refusedNoRoot`) and answers null. Flood-safe, per door: the first refusal in each 60 s window is
said in full; the rest are counted and said as ONE summary line at the door's next refusal after the window (`… and N more refused at
<door> in the 60 s window from <its start> (no root)`) — no timer thread; at most 1024 doors are held apart, a further door shares one
window. With debug on (`config.routesphere.debug`), every refusal is said. The rejection itself is the ingress's own: the door only says
and counts. `engine.saidAtTheDoor()` gives the last 64 lines (the operations' view; a test's). A lane whose facts carry no root keeps the
old road (the machine asks the rule at its start and refuses `NO_TENANT`): since P1-L that refusal is said through the SAME sayer — the
kit's (`kit.noRootAtTheDoor()`), the same window per door, the same `refusedNoRoot` — with "— the session is refused NO_TENANT". An ingress
that follows the two lines never launches a session without a root, so nothing is counted twice.

**No request writes a log line of its own** (P1-L): the ad's session id is built from the request's MAC (`ad-<tenant>-<mac>-<ms>-<n>`), a
reserve's reference from the session id, a lane's failure may quote the request — a CR/LF in any of them would forge a line. Every value
of that kind the seed logs goes through ONE cleaner — since P1-M in the seed's bottom module **seed-logtext**
(`com.telcobright.seed.logtext.LogText`, no dependency of its own, so seed-campaign reaches it too); `api.LogText` is its thin delegate
(a lane's import stands). `clean(value)` writes every control character as a visible escape (C0 — CR, LF, TAB, … —, DEL, C1, and U+2028 /
U+2029: `\n`, `\r`, `\t`, the others `\uXXXX`) and, since P1-M, every Unicode FORMAT character (the whole Cf category: the bidi controls,
the marks, the zero-width characters, the TAG characters — what reorders or hides what a terminal shows; one above U+FFFF as its two
escapes), and keeps at most **200 characters** ("…" when cut, never inside an escape or a character); `failureText(words)` the same at
**1000** for a failure's text or a long text a line carries whole; `cleaned(throwable)` the same stack with every text in its chain
cleaned. The door says its door, the session id and a failed rule's words and stack through it; so do the seed's lines that take a
session id, a call id, a reference, a task id, a lane's cause or a store's or a ledger's words (sessionflow, seed-switch-ledger,
seed-campaign). A lane logging a request's value uses it too: `log.warn("… {}", LogText.clean(request.mac()))`.

**No password inside a url is printed** (P1-M, P1-N — the principle: an over-mask beats a leak; a line may lose a user name, never show
a password): `LogText.maskPassword(url)` writes the userinfo's password (`redis://:***@host`, `redis://user:***@host`,
`jdbc:mysql://user:***@host`) — and a userinfo WITHOUT ':' whole (`redis://***@host`: Lettuce reads `redis://password@host` as the
password; a bare token `https://***@host`) —, the value of every parameter whose name says password, passwd, pwd or secret
(`?password=***`, `&pwd=***`, `;password=***`, case-blind, inside a longer name too) and of every key-value block that names one
(Connector/J's `address=(host=…)(password=***)`) as `***`, the rest as it is. The userinfo runs to the LAST '@' before the first '?' or
'#' — a password may carry ';' or '/' unencoded (Connector/J's authority runs past ';'): an '@' in a path, or in a ';' property before any
'?', therefore over-masks (`https://host/users/a@b/x` → `https://***@b/x`), accepted; a '?' or '#' unencoded inside a password still ends
the search (every parser breaks there), accepted. It is used wherever the seed prints a url: the start line (every root's switch-db and redis url, the bootstrap's `where()`), the records' text (`SwitchDb`,
`RootResources`, the ledger's words, `LedgerSettings`, `Deployment`), the orchestrix ledger's lines and refusals. Only for printing: the url
used to connect is the one written (`BootstrapSource.origin()` stays raw — seed-tenant's trees connect by it).

The engine's numbers (`engine.stats()`, a `PoolStats`), each counted where it happens (P1-F): `machinesBuilt` is the supervisors
statewalk's pool built (the base builds no machine; it never passes `size` unless a reset failed); `busy` is a NEW session refused
because every machine was live (statewalk's `CAPACITY_EXCEEDED`), counted at the lane's channel's inbound and at
`engine.launch(id, firstFrame)` — `engine.deliver` counts nothing, so a lane that counts its own refusals there keeps doing so;
`sessions` is the sessions live now, `live` the registry's live CELLS (kept as it was: two per session — its supervisor and its
signaling machine — three with the budget child). A `PoolStats` made with the ten numbers of 2.0 still builds; its `sessions` is
`PoolStats.NOT_COUNTED` (-1). `refusedNoRoot` (P1-K) is the requests the door refused because no root owns their door — and the sessions
the machine refused `NO_TENANT` at their start (P1-L); a `PoolStats`
made with the ten numbers of 2.0 or the eleven of P1-F still builds, its `refusedNoRoot` `NOT_COUNTED`.

## 3 · The contract (R2–R4)

| | method | forced ■ / final / default | the call's answer (the default where there is one) |
|---|---|---|---|
| | `name()` · `serviceGroup(ctx)` | ■ | `CallSupervisor` · 0 (billing detects a voice call) |
| (a) | `identifyRootLevelTenant(facts)` | ■ | `byEslSocket(…)`; the helpers `byListener` · `byEslSocket` · `byVlan` · `byTopic` · `byIngress`; null → `NO_TENANT` |
| (b) | `iterateResellerTenants(leaf)` | final | leaf → reseller → … → root, inside the session's own tree |
| (c) | `identifyPartner(ctx, candidate)` | ■ | the caller's partner; the helpers (§4.0) `byRouteCredentials` · `byIp` · `byDomain` · `bySipAccount` · `byPrefix` · `bySystemId` · `byPartnerId` · `byPartnerName`; null → `PARTNER_NOT_FOUND` (or the helper's cause: `CREDENTIALS_REJECTED`) |
| (c) | `partnerAbove(tier)` | default | `res_<id>` → partner `<id>` of the tier above; only when no partner has that id, the partner named EXACTLY as the tenant (one get, no loop) |
| (d) | `route(ctx)` | ■ | the dialplan's hops, in order (made once at admission; route by `ctx.candidateBeingAdmitted()`) |
| (e) | `rateAtLevel(ctx, tier, partner)` | ■ | one talk minute at the tier's rate |
| (e) | `reserveBalance` · `finalizeTier` | default | the prepaid piece (§5.1): reserve one unit per tier; finalize = rate × billed units, the rest back |
| | `delivered(ctx, outcome)` | ■ | answered |
| | `refundPolicy(ctx)` | ■ | `RefundPolicy.callsRule()` (a failed session pays nothing) |
| (f) | `rerouteActionFor(cause)` | ■ | the v1 table (`USER_BUSY` → REROUTE, `SWITCH_CONGESTION` → RETRY_SAME, …) |
| (f) | `handleRerouting` · the retry ritual | default · final | RETRY_SAME · REROUTE under `maxAttempts` · FAIL_TERMINAL |
| (g) | `finishSession(ctx, outcome)` | final | teardown · the slot · the CDR per tier on `cdr_<root>` · the task · the SDR · `onEnded` |
| | the gates, `billedUnits`, the renewal, the CDR fields, the task words, `buildSdr`, `topicsOf` | default | the call's (`billedUnits` null = as reserved; no SDR; no topics of its own) |
| | every statewalk call (`onStart` … `record`) | final | the order of the steps, the money, the finish — the same in every lane |

`RoutesphereFlowDriftTest` guards it: the abstract methods are exactly the ■ ones, every statewalk call is final, and the class javadoc
marks the ■ ones and only those.

The order of the gates per tier is the call switch's: `checkPartner` → `authorizeBeforeSlot` → the leaf's channel slot →
`authorize` → `applyRootRules` (root only) → `reserveBalance`. A refused candidate gives every reserve of its try back; the next
reserves under `<session>#<try>#L<tier>`. `release` (never answered) gives every reserve back — the refund rule decides only a
session offered and never consumed (the ad's `unshown`). An answered session is settled, never released (statewalk's D-A1).

## 4 · Per root

**The root's source** (P1-I): the root is the lane's rule over the INGRESS — the door the session came in at, read by
`engine.identifyRootLevelTenant(facts)` — and NEVER a value the request carries: not a body field, not a header, not a query. A caller
that could name the root could bill another operator. `RoutesphereFacts.root()` carries only what that rule answered for this ingress.

Every lookup of a session is scoped to the root its ingress named: the entry partner (`byPartnerId` — the root's OWN partner
map; an id two tiers hold names nobody), the tier walk (a chain that ends at another root is refused `PARTNER_NOT_FOUND`), the
rates, the CDR's topic. The session takes its root's snapshot at its start (`ctx.tree`, `ctx.treeVersion`) and keeps it to its end;
the doorbell (`config_event_loader_<root>`) reloads that root only. A restored session takes its root's snapshot again.

## 4.0 · Identification — the partner through its route, one get each (P1-O)

The owner, 2026-10-25: *"The DynamicContext holds the partner entities AND the route entities, transformed into hashmaps by the key the
business logic needs … the client passes a param (e.g. route username+password) matched with the routes key indexed in the DynamicContext;
route → partner; one partner may own many routes. Avoiding a LIST lookup on the hot path is a STRICT rule for partner and tenant
identification."*

**One index per snapshot, never per session.** When a root's snapshot is INSTALLED — its first load and every reload, the cell's swap in
seed-context (`SnapshotIndexer`), or `TenantLookup.of` at its construction — `RootTreeInstaller` makes its `RootTree`, whose constructor
walks every tier ONCE into `RootIndex`: flat maps key → the tier and the partner (`EntryPartner`):

| map | key, as prime-context serves it | helper |
|---|---|---|
| `byPartnerId` | the partner id (every tier's `partners`) | `byPartnerId(ctx, id)` |
| `byPartnerName` | the partner name, EXACT (no case-blind fallback loop) | `byPartnerName(ctx, name)`, `partnerAbove`'s name road |
| `byRouteKey` | `route.field5`: an ip, an `ip:port`, a domain (`routeWisePartners` + `ipOrDomainWiseRoute`) | `byIp(ctx, ip)` (the bare key) · `byIp(ctx, ip, port)` (`ip:port`, then the bare ip; no `:5060` default — the lane passes the port it saw) · `byDomain` |
| `byRouteUsername` | `route_metadata.extra1` → `RouteEntry(tier, metadata, route, partner)` (`usernameVsRouteMetaData`) | `byRouteCredentials(ctx, user, password)` · `routeOfCredentials` (the route itself) · `bySystemId` (the SMPP name) |
| `bySipAccount` | the SIP user name (`sipAccountWisePartners`) | `bySipAccount(ctx, account)` |
| `byPrefixLength` | length → (prefix → …) (`prefixWisePartners`) | `byPrefix(ctx, number)`: one get per length present, the longest first |

A route's partner is the one its tier's `partners` holds under `route.idPartner` — the SAME instance `byPartnerId` answers; never
`route.getPartner()` (rtc-domain's is `@Transient`). A user name's route is its row's back reference, else the tier's route that carries the
row (by the row's id). **A key two holders share names nobody** (seed-tenant's D5 rule, kept for every key): two tiers, or two partners of
one tier — it leaves its map, `RootIndex.heldByTwo()` lists it, ONE WARN at the build; a route whose tier does not hold its partner is left
out, said once. `routesOf(leaf)` gives a partner's routes (`partnerVsRoutes`: one partner, many routes). The counter
`rootIndexBuilds` (`TenantLookup`, `RoutesphereKit`, `PoolStats`) reads roots × loads — never sessions.

**Route credentials.** `byRouteCredentials` takes the user name and the secret a client sends (the aggregator SMS API's, the ad App's) and
checks it against `route_metadata.extra2` through `internal/Secrets` (the architect's A4 as amended by A4′): a route's credentials are
MACHINE secrets the system generates (`Secrets.newMachineSecret()`: 32 random bytes, base64url) and stores as
`{sha256}<base64 salt>$<base64 SHA-256(salt ‖ UTF-8 secret)>` (`Secrets.hashMachineSecret`, a 16-byte salt) — the check is ONE SHA-256 and a
constant-time compare, microseconds. `{pbkdf2}<iterations>$<salt>$<hash>` is still verified for compatibility but written by nothing: a slow KDF
must not guard a credential checked on every request (~0.1 s of CPU each, and a CPU amplifier for wrong secrets). Anything else is the
LEGACY plaintext of the SMS rows, compared in constant time on its UTF-8 bytes; a blank value verifies nothing. An unknown user name, a wrong
or missing secret: null, and the session's `identificationCause` is `CREDENTIALS_REJECTED` — one cause for both, and the same work (an
unknown user name is checked against a decoy, `Secrets.compareWithNobody`), distinct from `PARTNER_NOT_FOUND`; the base refuses the
candidate with it (an HTTP lane answers 401, an SMPP lane its bind error). The secret is never kept, logged or put in a text. The testkit's
`routeCredentials` writes the `{sha256}` form; `legacyRouteCredentials` a plaintext row; `routeCredentialsStoredAs` any value a test pins.

**On the served tree.** prime-context serves a `usernameVsRouteMetaData` row WITHOUT its route (its `RouteMetadata.route` is
`@JsonIgnore`): rtc-domain's deserializer wires a row's route from `partnerVsRoutes`, and the index finds it by the row's id among the
tier's routes (`partnerVsRoutes`, `idVsRoute`, `ipOrDomainWiseRoute`) when it is not wired; a row whose route is not served is left out with
one WARN. `ServedTreeRoundTripTest` proves it on a tree serialized as prime-context serves it and read back by `Trees.parse`.

**The climb.** Above the leaf, `partnerAbove` takes the id at the end of the child's database name in the PARENT's `partners`; only when no
partner has that id, the parent's partner named exactly as the child (its name, else its database name) — a get on the session's index (a
tier the lookups found carries it), never a walk of the parent's partners.

**The guard.** `HotPathHasNoListScanTest` scans the main sources of seed-sessionflow, seed-tenant and seed-routing for a `for (…)` over
`partners()` / `routes()` / `children()` / `tiers()` / `tenants()` (without case: `getPartners()` counts) and for every `.stream(` and
`.forEach(`; each must stand in `docs/agents/hot-path-allowlist.txt` as `Class#method — reason` (the index builder, the install, the start
and close roads, a session's own few lines, the testkit). A new walk in a flow class is red. `NoWalkAfterInstallTest` proves it at run time:
after the install every map of every tier refuses to be walked, and every helper and a three-tier climb still answer.

**A lane's own lookup** (one that implements `TenantLookup` itself) gets each tree object installed once by the default `snapshot`
(`installOnce`: the first session of a new tree pays the build); a lookup that serves sessions should hand out its own installed snapshot
(seed-tenant's `TenantTrees.snapshot` does). Never print a `Route` or a `RouteMetadata` whole: rtc-domain's Lombok `toString` walks route →
row → route and prints the row's password; `RouteEntry`'s own text names the tier, the route and the partner only.

## 4.1 · Naming (R9) — `seed-naming`

| thing | convention | the seed's call |
|---|---|---|
| a root's database / schema | the root's name: `link3`, `btcl` | — |
| a reseller tier | `<root>_res_<partnerId>`, nested `_<partnerId>` (`link3_res_5`, `link3_res_5_2`); the seed root keeps the legacy `res_<id>` — named by the bootstrap (`naming.seed-root`), never in code | `TenantNames.withSeedRoot(seed).tierDb(root, parentDb, id)`, `parentOf(root, tierDb)`; `Deployment.tenantNames()` |
| a platform topic | `<name>_<root>`: `cdr_<root>`, `cdr_summary_ping_<root>`, `config_event_loader_<root>` | `Topics.platform(name, root)` |
| an application's own topic | `<app>_<in\|out>_<name>_<root>` (publish and subscribe never share one) | `Topics.app(app, Direction.IN, name, root)` |
| a Redis key / stream | `<root>:<app>:<name>` | `RedisKeys.of(root, app, name)` |

Every topic name is checked as Kafka checks it (its characters, ≤ 249, no `.`). At the start, on a kit from the bootstrap, the engine
makes every served root's `cdr_<root>`, `config_event_loader_<root>` and the lane's own `topicsOf(root)` (`TopicProvisioner`: idempotent; a
cluster that refuses is said once and the start goes on), and again when a root is added live.

## 4.2 · The bootstrap (R8) and a tenant created live (R10)

The application's yml holds ONE thing of the platform:

```yaml
prime-context:
  bootstrap-url: http://10.10.188.2:7300/api/v1/bootstrap/platform/ad-sphere
  token-env: AD_SPHERE_BOOTSTRAP_TOKEN           # the NAME of the variable, never its value
```

**These two lines live at RUN TIME only** (the architect's rule, P1-F): in the box's external `config/application.yml` beside the jar
(Quarkus reads `config/application.yml` from the process's working directory — the unit's `WorkingDirectory=`), or in the unit's
environment (`PRIME_CONTEXT_BOOTSTRAP_URL`, `PRIME_CONTEXT_TOKEN_ENV`). **Never inside the jar**: Quarkus's BUILD reads every file in the
jar, and it runs seed-bootstrap-config's factory too — a url the build can see makes `mvn package` fetch prime-context. Nor in the
build's own run-time places: no `config/` folder or `.env` in the project's directory, no `PRIME_CONTEXT_*` exported in the shell that
builds. There is no build-time guard in code: no reliable, documented signal tells a config factory it runs inside the build (what was
looked at: `seed-bootstrap-config/README.md`).

`RoutesphereKit.fromBootstrap(source, app)` reads prime-context's envelope (§C.1: `config.routesphere` — the roots, the pool, the timers,
the identification rules, each root's `switch-db`, `kafka`, `redis`, `ledger`, `journal`), and makes per served root its tree (seed-tenant's
`PrimeContextTrees`), its doors, its ledger (`switch` = seed-switch-ledger's MemLedger port, the default; `orchestrix` for the transition),
its CDR road (`cdr_<root>` on its own Kafka) and its journal (its own directory). The makers are found on the classpath; a missing one is
refused in words. The start says, one line per root, every resource it got.

The lane's OWN sinks — its SDR writer, its task store (the ad's `ad_sdr`, its `campaign_task`), a CDR road of its own beside the root's —
go on that kit before it is used (P1-F):

```java
RoutesphereKit kit = RoutesphereKit.fromBootstrap(source, "ad-sphere", makers).toBuilder()
    .sdr(adSdrWriter).tasks((ctx, row) -> campaigns.closed(row))
    .build();                                     // then the flow, then the engine
```

`toBuilder()` keeps everything the road made — the trees, the doors, the ledger, the CDR road, the journal, the clock, the zone, the
settings and the road itself, so the engine on the new kit starts the road and its close closes what the road opened; the new kit's own
books (slots, counters, the CDR sequence) start fresh. Refused, in words, once an engine runs on the kit. The sample envelope and the `tenant_created` message are
`src/test/resources/bootstrap/` — pinned byte for byte with prime-context.

The application's OWN keys (everything in `config` beside `routesphere`: `adsphere.*`, `sdr.*`, `quarkus.http.*`, …) reach its
MicroProfile / SmallRye Config through **seed-bootstrap-config** (a `ConfigSourceFactory` at ordinal 275, the same road, never a secret) —
so the application's yml is the two lines above and nothing else. See `seed-bootstrap-config/README.md` — and its "When the values are
visible": from RUNTIME_INIT on (P1-G, proven on Quarkus 3.26.1); a bean Quarkus REST builds at STATIC_INIT (one a resource or filter
injects by its type) sees the jar's own values — reach it through `Instance<…>`.

**No secret from the bootstrap** (P1-K — the owner's ruling of 2026-10-24, "Environment only", re-affirming his rule of 2026-09-24): the
seed reads NO secret from prime-context's envelope. A password by reference (`password-ref: env:<NAME>` — switch-db's, and the switch
ledger's own `ledger.db`) is read from the PROCESS environment only, which the box's environment file fills (the secreteer: SOPS + age →
`/etc/secreteer/<tenant>/<app>.env` → the unit's `EnvironmentFile=`). A variable not in the environment refuses the root's start in words
naming the variable and the login, never a value; a `password-ref` that is not `env:<NAME>` is refused without repeating its text, and no
record prints it (P1-L: `SwitchDb`, the ledger's words — and so `RootResources` and `Deployment` — show "<not an env: reference —
hidden>"; an `env:` reference is printed as it is). The
envelope's `secrets` are still parsed into `Deployment.secrets()` — deprecated, read by nobody, kept for the record's signature — and a
`Deployment`'s text names them, never their values. Before P1-K, switch-db's password fell back to the envelope's `secrets`.

Live: the engine listens to `tenant_created` (§C.2) on the platform's Kafka. A new ROOT this application serves → the bootstrap read again,
the root's tree, doors, ledger, CDR road, journal and topics made, and it is served — no restart. A new reseller TIER → its root's tree
reloaded — and, first, THAT root's ledger loads the tier (`LedgerPort.tierAdded(tierDb)`: the switch ledger loads the one schema between
the root's money moves, see `seed-switch-ledger/README.md`, with what P2 must prove on PostgreSQL), so no session sees a tier its ledger
cannot serve. The same event twice is once; another application's is nothing. The engine's close closes everything the road opened: the
listener, the admins, every root's ledger (a switch ledger's MemLedger), CDR road and journal, the producers, the trees.

**Live tenants made safe (P1-G):**

- **A `kind: root` message comes BEFORE the root is served.** prime-context announces a root at its provisioning; its tree road answers
  404 ROOT_NOT_SERVED until tenant-master's switch row exists (and its next build), and the deployment document may list the root only later
  (a PUT after the creation). Such a root is never half-served (`LiveTrees.serve` leaves nothing when it fails — a lane's own trees undo the
  same): it goes PENDING and is tried again every `config.routesphere.pending-root.retry-sec` (default 30 s: the bootstrap read again, the
  tree loaded) until served, or given up after `pending-root.give-up-min` (default 120) — said once each (pending with the reason, served,
  given up: "a restart, or a new tenant_created, serves it"); a later word tries at once. One daemon thread `<app>-pending-roots` per road,
  stopped by the engine's close (`road.pendingRoots()`, `road.said()`). A root's roads are all-or-nothing (P1-H): a try that fails
  half-way closes what it opened — its ledger first, then its CDR road and journal —, takes the tree back, leaves the deployment and the
  doors as they were; the road never opens a second ledger for a root while one is open. **A deployment must not set `pending-root` yet**:
  prime-context's document checker (`DeploymentShape`) refuses keys it does not know today — logged for a prime-context round; until it
  accepts the key, the defaults (30 s, 120 min) are the pace.
- **The pending line names the real reason (P1-P).** It says WHO keeps the root out, on one line (the words cleaned by `LogText`):
  "not listed for `<app>` yet (its roots …)" when the document does not list it yet; "prime-context does not serve its tree yet: `<its
  answer>`" when the tree fetch failed; "the lane `<app>` refused it: `<its words>`" when the LANE refused the root for a reason of its own (a
  door it cannot open, ad-sphere's `ROOT_DOOR_UNBOUND`; a rule the root lacks). **A lane's own `LiveTrees` throws
  `LiveTrees.LaneRefusal`** with its words for such a refusal, as it is (not wrapped). Anything else a serve throws reads as the tree's
  failure. The root goes pending and is tried again either way.
- **A tree never shows a tier its ledger was not handed.** A reseller's provisioning rings the doorbell `config_event_loader_<root>` AND
  sends `tenant_created`; the ring can come first. Every NEW tree passes the road's gate before a session can see it (`spi/TreeGate`,
  `LiveTrees.gateEachNewTree`): each tier its root's ledger was not built over is handed to that ledger first (`tierAdded`), once — the ring
  and the event together are one hand. seed-tenant's trees pass every loaded tree through it (the ring, a reload, the backstop). **A lane's
  OWN LiveTrees** (ad-sphere's `AdTrees`) must take the gate (`gateEachNewTree`) and call `gate.pass(root, newTree)` before it swaps its
  own snapshot; the default does nothing. A ledger that throws keeps the new tree from being shown; the switch ledger says its own failure
  and the tree is shown, the tier's money refused until it loads, and the gate hands that tier again on the next tree (`LedgerPort.tierLoaded` false: no restart needed).
- **The seed root's prefix is the bootstrap's word**: `config.routesphere.naming: {seed-root, seed-prefix}` (the envelope's one addition,
  PC-0022 §9.1) — `res_` when no prefix is said; a value that is not a name prefix is refused at the read. The naming rule is held to
  prime-context's own table (`seed-naming`'s `bootstrap/naming-cases.tsv`, byte-identical, 23 cases).
- **The deployment's tenant is the operator's** (btcl on the bed), never `platform`: tenant-master reserves that word (PC-0022 §9.7) — the
  pinned sample's `platform` is a sample's.
- **A deployment's identification rules have ONE home**: `config.routesphere.identification.rules` — prime-context serves nothing at the old
  home `config.identification` to a deployment (the seed reads only the new home).
- **`tenant_created`'s `apps` are sorted** (prime-context writes them so); the message names the root (its key) and is sent after the
  provisioning's writes are committed.
- **A root's switch ledger has ONE writer**: serve each root from ONE process. Its MemLedger is STANDALONE — the only writer of the root's
  package accounts and reserves; a second process on the same root (or another replication mode) would be a second writer of the same
  money, and a live tier's merge (its rows beside it) assumes the one writer (`seed-switch-ledger/README.md`).
- **A root added live needs its door to be a listener the process already has**: Quarkus binds its listeners at the start, so a new root
  whose rule names another address gets no request until the unit listens there — a window step (P2 gap 9).
- **The pinned samples stay byte-identical with prime-context's** (`src/test/resources/bootstrap/`: `deployment.json` sha256
  e1ca4f43a016ce4f…, `tenant-created.json` 63f396e263457824… — equal at prime-context framework-bootstrap 8f8d922, checked 2026-10-24).

## 5 · Money

ONE ledger port (`spi.LedgerPort`: reserve · settle · release) — idempotent per (session id, tier): the same reference twice moves
money once, so a session restored from an older row and ended again (persistence is async) moves nothing twice (§L.1).
`CdrSinks.oncePerSession` dedups the CDR in the process; billing-core takes a session once per tier across processes.

The budget child (`internal.BudgetChild`, a statewalk 4.0 `MachineSpec`) is spawned in INIT when the flow renews
(`reservePeriodSec > 0`), rests until statewalk's `SessionActive` (G-1), renews every tier once per period (C14: the narrowest window
decides; a partial window arms the final cut; a refused window may rotate onto `nextAccount` — O4), and publishes `BudgetExhausted`
when the money ends (the supervisor sends `Stop(BALANCE_EXHAUSTED)` down). A persisting lane renews at most once a minute: every tick
rewrites the session's row (§L.2; the engine refuses less).

## 5.1 · The prepaid piece (R12)

Prepaid is strictly package-account + mem-ledger, and it is ONE reusable piece of the base — no lane writes money code:

| api | when | what it does |
|---|---|---|
| reserve — `reserveBalance(ctx, tier, partner)` | ADMITTING, every tier leaf → root | the tier's rate (`rateAtLevel`: package units before money, ONE unit) held on ITS account through the one ledger port, `<session>#L<tier>` |
| finalize — `finalizeTier(ctx, tier, outcome)` | the final state, every tier, once | the cost of the session: delivered → rate × `billedUnits` (null = the units reserved); the reserve comes back, the difference is the final adjustment (more used than reserved = an extra debit, the call's rule); not delivered → the lane's refund rule (`refundPolicy` on `settleOutcome`) |

The SMS's case is the refund rule: a failed message — no SMSC took it, or it was not delivered and the lane refunds by config — returns
its whole reserve at every tier (`SmsPrepaidPieceTest`); an answered call pays rate × its units and the rest of its reserve comes back
(`PrepaidPieceTest`). Every verb is idempotent per (session, tier). The default ledger of a kit made from the bootstrap is the in-switch one —
`ledger.kind: switch` (seed-switch-ledger: a MemLedger over the root's switch database, the package accounts of every tier); `orchestrix`
(portal-api's REST roads) stays for the transition.

## 6 · Persistence

`RoutesphereContext` is persisted by statewalk (Jackson): ids and amounts only — the tiers as `TierLine`s, the plan, the candidates,
the root and its snapshot's version. The live objects (the `RootTree`, the `LevelAdmission`s, tenants, partners, the `CampaignTask`)
are `@JsonIgnore`; the base brings them back from the root's snapshot before it touches money. A subclass follows the same rule.
`billedUnits` sees `ctx.restoredAtMs` (§L.3: whether the downtime of a restored session is billed is the lane's decision).

**One recovery road per lane** (P1-F3) — a process that dies leaves sessions in the air; a lane picks ONE way to finish them:

| road | the kit / the engine | what the next start does |
|---|---|---|
| statewalk's store | `persistence(RESUME, provider)`, `SessionJournals.none()` | restores every unfinished session from its row and ends it itself (its timers, its wire, settle once per (session, tier)) |
| the journal of the sessions in the air (R1-6) | `journal(SessionJournals.file(path))`, the store OFF or SAVE | closes every session the journal holds: never handed over → every reserve back, one record at 0; handed over → charged as reserved (A2), LOST_AT_RESTART |

The engine refuses the journal together with RESUME at `start()`: a session in both would be closed by the journal and then restored
and ended again. A lane that persists its sessions takes RESUME; the journal is for a lane that runs without a store (the ad's road
before statewalk 4.0). Which road each lane takes is its own round's decision (P2 for the ad).

## 7 · Packages

```
api/           RoutesphereFlow · RoutesphereApp · AppFlow · RoutesphereSupervisor · Lookups · RoutesphereContext · RoutesphereFacts · Ingress
               Tier · Candidate · RootTree · RootIndex · RouteEntry · RootTreeInstaller · TierLine · RoutePlan · RoutesphereEngine · AdmissionChain
               SessionSettlement · SessionCdr
               Deployment · RootResources · TenantCreated · CdrEvent · TierRate …
spi/           LedgerPort · CdrSink · SdrSink · TaskSink · TenantLookup · TenantResolver · SessionJournal   (the host implements these)
               BootstrapSource · KafkaClients · TreesMaker · LiveTrees · LedgerMaker                         (the bootstrap's makers)
dependencies/  RoutesphereKit · KitMakers · BootstrapSources · TopicProvisioner · RoutesphereSettings · CdrSinks · Ledgers
               TenantResolvers · IdentificationRules · RefundPolicy …
internal/      BudgetChild · ChannelSlots · CdrAssembler · KafkaCdrSink · OncePerSessionCdrSink · OrchestrixLedger · FileCdrJournal · Secrets
               BootstrapRoad · DeploymentReader · HttpBootstrapSource · PerRootLedger · PerRootCdrSink · PerRootJournal · LiveRules
               TenantCreatedListener · RealKafkaClients · OrchestrixLedgerMaker …
testkit/       InMemoryLedger · RecordingCdrSink · TenantTreeBuilder · ManualClock · MapBootstrapSource · FakeKafka

seed-naming    TenantNames · Topics · RedisKeys · Direction
seed-bootstrap-config    BootstrapConfigSourceFactory (the application's whole config from the bootstrap, ordinal 275)
seed-tenant    PrimeContextTrees (the TreesMaker)       seed-switch-ledger    SwitchLedgerMaker (the `switch` LedgerMaker)
```

## 8 · Tests

```bash
mvn -o test        # at the reactor's root: the whole suite (never one module alone)
```

`src/test/.../base/` proves the base on a real statewalk 4.1 registry; `…/bootstrap/` the kit from §C's sample (no network: fakes): `Lab` (two roots with overlapping partner ids), `LabFlow`
(the smallest application), `LabCall` (the smallest signaling machine the 4.0 builder accepts). The four samples (ad, voice, WiFi,
SMS) and their scenarios live in `seed-sessionflow-samples`.

`testkit.FakeKafka` (P1-F) runs on kafka-clients 3.7.1 (the seed's) and 4.0.0 (Quarkus 3.26.1's — an application's tests on that
platform can use it): its producer is `MockProducer(boolean, Partitioner, Serializer, Serializer)`, in both — 4.0 removed the
three-argument one. `FakeKafkaOnKafka4Test` reads FakeKafka's class file for it; the run on both versions is in `docs/agents/P1-F-state.md`.
The seed's own `KafkaCdrSinkPerRootTest`, `SessionsInTheAirTest` (and seed-tenant's `DoorbellTest`) still build the three-argument one:
they run on the seed's 3.7.1 only.

## 9 · History

- 2026-10-25, night — P1-O (the owner's identification rules of 2026-10-25; the architect's decisions A1–A9): the partner through its ROUTE
  — `byRouteCredentials` / `routeOfCredentials` / `bySystemId` (one cause `CREDENTIALS_REJECTED`, one decoy compare for an unknown user;
  `internal/Secrets`: a machine secret's `{sha256}` form — A4′ —, `{pbkdf2}` verified only, the legacy plaintext), `byIp` (the port first, no default), `byDomain`, `bySipAccount`, `byPrefix`, `routesOf` — every one a get on the `RootIndex`
  built ONCE at the snapshot's install (seed-context's `SnapshotIndexer`, `RootTreeInstaller`, the counter `rootIndexBuilds`), never per
  session; `byPartnerName` and the climb's name road EXACT and one get; the climb's loop, the per-session partner walk and the served-root
  loops gone; seed-routing's longest prefix by length; `HotPathHasNoListScanTest` + `docs/agents/hot-path-allowlist.txt`; the samples find
  their partners through the served route maps (voice `byIp` then `bySipAccount`, the SMS HTTP door `byRouteCredentials` → 401); the tree
  road sends the bootstrap's bearer. State: `docs/agents/P1-O-state.md`, report `docs/agents/P1-O-report.md`.
- 2026-10-24, evening (4) — P1-N (the architect's probe of P1-M's `maskPassword`): three url forms it printed raw are masked — a userinfo
  without ':' whole (Lettuce's `redis://password@host`, a bare token), a userinfo password carrying ';' or '/' (the userinfo runs to the
  last '@' before '?' or '#'), Connector/J's `(password=…)` blocks; an over-mask beats a leak. State: `docs/agents/P1-F-state.md`.
- 2026-10-24, evening (3) — P1-M (from P1-L's open points): the log cleaner moved to the seed's bottom module `seed-logtext`
  (`api.LogText` its delegate) so seed-campaign's lines are cleaned too; a password inside a url is masked wherever the seed prints one
  (`maskPassword`: the userinfo's, any password/pwd/secret parameter; the url used to connect untouched); the whole Unicode format category
  escaped (bidi controls, marks, zero-width and TAG characters). State: `docs/agents/P1-F-state.md`.
- 2026-10-24, evening (2) — P1-L (the architect's review of P1-K's door): no value a request carries writes a log line of its own — ONE
  cleaner, `api.LogText` (control characters escaped, 200 characters, a failure's text 1000, its stack cleaned), through which the door and
  the seed's lines that take a session id, a reference or a lane's words say them; the machine's `NO_TENANT` start road is said through
  the SAME sayer as the door (the kit's: the same window, the same `refusedNoRoot`; no double count); a written `password-ref` is printed
  by no record. State: `docs/agents/P1-F-state.md`.
- 2026-10-24, evening — P1-K (the owner's ruling of 16:51, "Environment only", re-affirming his rule of 2026-09-24): a password comes
  from the process environment only — switch-db's no longer falls back to the bootstrap envelope's `secrets` (`SwitchDb.password(env,
  secrets)` removed; `passwordFromTheEnvironment` for both logins; a refusal names the variable, never a value, nor a written
  `password-ref`); `Deployment.secrets()` deprecated (read by nobody) and a `Deployment`'s text names the secrets, never their values.
  And the owner's ruling of the same evening: a request whose door no rule names is refused AND said — the door
  (`engine.identifyRootLevelTenant`) writes a WARN line and counts `refusedNoRoot` for every lane, flood-safe per door (a 60 s window, one
  summary line, no timer thread; every refusal with debug on), and still answers null: the ingress's guard line rejects in its own
  protocol. State: `docs/agents/P1-F-state.md`.
- 2026-10-24, afternoon — P1-J (ad-sphere P2c's end-to-end run against prime-context 8f8d922): every reload of a root's tree merges its
  new rows into its switch ledger (`LedgerPort.treeReloaded`, coalesced: at most one merge per `ledger.merge-spacing-sec`, default 5 s) —
  an account written after its tier exists pays at the next ring; the switch ledger's OWN login `ledger.db` (optional; the app's and the
  ledger's roles kept apart). Before it: P1-G (live tenants made safe), P1-H (a root's roads all-or-nothing; timing tests under load),
  P1-I (a connect cut by the budget says so; the root's source). State: `docs/agents/P1-F-state.md`.
- 2026-10-24, morning — P1-F, the six gaps the ad-sphere port found, fixed additively: the bootstrap url at RUN time only (no build
  guard: no documented signal), `RoutesphereKit.toBuilder()` (the lane's own sinks on a kit from the bootstrap), `CdrSinks.all` keeps
  the root, `machinesBuilt` = the supervisors' pool's own count and `busy` counted at the door (`DoorChannel`, `engine.launch`; never
  `deliver`), `PoolStats.sessions` beside the cells, `FakeKafka` on kafka-clients 3.7.1 and 4.0.0. State: `docs/agents/P1-F-state.md`.
- 2026-10-24 — the owner's readable shape: every ingress reads `root = engine.identifyRootLevelTenant(facts)` then
  `engine.launchStateMachine(…)` (the root rides in `RoutesphereFacts.root()`; the machine takes it and asks the lane nothing); the base's
  story in his words — `startSession · prepareCandidates · admitSession · performAdmission · route · reroute · finalizeTier · finishSession`
  (statewalk's `onStart/prepare/admit/retry` are one-line adapters); renamed hooks: `identifyTenant → identifyRootLevelTenant`,
  `routing → route`, `finalizeCost → finalizeTier` (the builder keeps the old two as deprecated aliases).
- 2026-10-24 — P1-E, the framework round (the owner's R1–R13 of 2026-10-23): `identifyRootLevelTenant` / `identifyPartner` forced with the base's
  helpers (`Lookups`), the facts' `SessionStart` sample refused at start, one supervisor class per lane (`RoutesphereSupervisor`, the one
  engine door), the builder (`RoutesphereApp`; the SMS sample made with it), the kit from ONE bootstrap url (`fromBootstrap`, the makers on the
  classpath), naming and topic auto-creation (`seed-naming`, `TopicProvisioner`), a tenant created live (`tenant_created`), the prepaid
  piece written down (the default ledger `switch`), the ad sample's answer at `/shown`. Report: `docs/agents/P1-E-report.md`.
- 2026-10-23, evening — P1-E item 0: the records road REMOVED (the owner's correction, R13: every switch publishes its CDR to Kafka;
  billing-core writes `cdr` + the summary input in one transaction and pings; a switch never writes a CDR table). `cdrStatements`,
  `sdrStatements`, `summaryEntryOf`, `RecordStores`, the writer and its tests left; the CDR sink is required again at build.
- 2026-10-23 — P1-R, the records road: the application's CDR/SDR SQL (`cdrStatements`, `sdrStatements`, `summaryEntryOf`), written by the
  base in ONE transaction per batch, one writer per root, with billing-core's lock, once rule and summary input; the leftovers at start
  through the same writer; one road per lane at build (report: `docs/agents/P1-R-report.md`).
- 2026-10-22 — P1: the 3.x base (`SessionFlow`, `SessionFlowSteps`, its own graph `SessionFlowSupervisor`, `LevelBalanceTracker`,
  `SessionFlowEngine`) replaced by `RoutesphereFlow` on statewalk 4.0.0; the reactor at 2.0.0-SNAPSHOT. The walk, the settlement,
  the CDR, the task close, the rotation, the journal kept and re-pointed. statewalk 4.0 has no DEFERRED: a session ends SUCCEEDED or
  FAILED.
- 2026-10-09 — the owner's "session base class": seed-callflow → seed-sessionflow, `CallFlow` → `SessionFlow` and every Call-named base type → Session (B1, move-only; hooks, verbs, `Cdr*` and every string unchanged). B2: `SessionFlowContext.task` (a `CampaignTask` every session kind carries) closed by the base in `close()` — `CLOSE_TASK`, after the CDR — and handed to the kit's `TaskSink` (`CampaignService.closed(row)` keeps the counters); `CampaignKind.WIFI`. O4: `nextAccount(ctx, level)` — a tier whose account cannot fund the next window rotates onto the next one (the WiFi's one purchase per device, the next queued): the current account settled for everything it held and closed as a `ClosedSpan`, a fresh series on the next account, ONE CDR RECORD PER ACCOUNT (`<sid>.<n>`, the same `callId`; billing keys a record on the id and the tenant); the tiers above keep one record.
- 2026-09-29 — the first shape, ad-only: `RoutedSessionSupervisor`, `ChainAdmission`, `AdBillingPort`, `LevelCdrWriter` (the switch wrote its
  own CDR rows).
- 2026-10-03 — the base pipeline (`SessionFlow`); ad-sphere moved onto it (`AdCallFlow`) and the ad-only classes were removed. The orchestrix
  ledger is now `Ledgers.orchestrix(...)`, an adapter of `LedgerPort`: reserve = road 16 on the partner's `billing_account_id`; what orchestrix
  cannot give back yet is written to an owed journal.
- 2026-10-03, later — the admission's budget (§5): one deadline for the whole admission, every ledger call bounded by what is
  left, the `unsure` journal line, and a caller that leaves during preprocessing or admission ends the call.
