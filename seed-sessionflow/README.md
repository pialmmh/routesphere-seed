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
| `api.Lookups` | The one-line helpers: a root by its door, a payer inside the session's own tree |
| `api.RoutesphereContext` | Everything one session knows (persisted: ids and amounts only). Your context extends it |
| `api.RoutesphereFacts` / `api.Ingress` | The facts your signaling machine stamps — with the INGRESS the root is found by (`listen` · `esl` · `vlan` · `topic`) |
| `api.RoutesphereEngine<C>` | statewalk's registry: your supervisor class + your signaling machine + the budget child + your channel |
| `dependencies.RoutesphereKit` | What the host hands in — by hand, or `RoutesphereKit.fromBootstrap(source, app)` from ONE bootstrap url (R8); `toBuilder()` gives that kit the lane's own sinks before it is used |
| `api.Deployment` / `api.RootResources` | What prime-context's bootstrap said: the served roots and each root's own resources |

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
    @Override protected String identifyTenant(RoutesphereFacts f) { return byListener(((AdFacts) f).listen()); }        // ■ (a)
    @Override protected Tier identifyPartner(AdContext c, Candidate k) { return byPartnerId(c, ((AdCandidate) k).payerPartnerId()); }  // ■ (c)
    @Override protected TierRate rateAtLevel(AdContext c, Tier t, Partner p) { ... }                 // ■ (e) null = UNRATED
    @Override protected RoutePlan routing(AdContext c) { return RoutePlan.one(((AdCandidate) c.candidateBeingAdmitted()).creative()); }  // ■ (d)
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
    .identifyTenant((facts, find) -> find.byIngress(facts.ingress()))                        // which root operator owns this door?
    .identifyPartner((sms, candidate, find) -> find.byPartnerId(sms, accounts.get(sms.user)))  // who pays at the leaf?
    .rateAtLevel((sms, tier, partner) -> rates.perSegment(tier, partner, sms.segments))        // what does this tier charge?
    .routing(sms -> RoutePlan.of(smscs))                                                       // where, in failover order?
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

The engine's numbers (`engine.stats()`, a `PoolStats`), each counted where it happens (P1-F): `machinesBuilt` is the supervisors
statewalk's pool built (the base builds no machine; it never passes `size` unless a reset failed); `busy` is a NEW session refused
because every machine was live (statewalk's `CAPACITY_EXCEEDED`), counted at the lane's channel's inbound and at
`engine.launch(id, firstFrame)` — `engine.deliver` counts nothing, so a lane that counts its own refusals there keeps doing so;
`live` is the registry's live cells.

## 3 · The contract (R2–R4)

| | method | forced ■ / final / default | the call's answer (the default where there is one) |
|---|---|---|---|
| | `name()` · `serviceGroup(ctx)` | ■ | `CallSupervisor` · 0 (billing detects a voice call) |
| (a) | `identifyTenant(facts)` | ■ | `byEslSocket(…)`; the helpers `byListener` · `byEslSocket` · `byVlan` · `byTopic` · `byIngress`; null → `NO_TENANT` |
| (b) | `iterateResellerTenants(leaf)` | final | leaf → reseller → … → root, inside the session's own tree |
| (c) | `identifyPartner(ctx, candidate)` | ■ | the caller's partner; the helpers `byPartnerId` · `byPartnerName`; null → `PARTNER_NOT_FOUND` |
| (c) | `partnerAbove(tier)` | default | `res_<id>` → partner `<id>` of the tier above, else the partner named as the tenant |
| (d) | `routing(ctx)` | ■ | the dialplan's hops, in order (made once at admission; route by `ctx.candidateBeingAdmitted()`) |
| (e) | `rateAtLevel(ctx, tier, partner)` | ■ | one talk minute at the tier's rate |
| (e) | `reserveBalance` · `finalizeCost` | default | the prepaid piece (§5.1): reserve one unit per tier; finalize = rate × billed units, the rest back |
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

Every lookup of a session is scoped to the root its ingress named: the entry partner (`byPartnerId` — the root's OWN partner
map; an id two tiers hold names nobody), the tier walk (a chain that ends at another root is refused `PARTNER_NOT_FOUND`), the
rates, the CDR's topic. The session takes its root's snapshot at its start (`ctx.tree`, `ctx.treeVersion`) and keeps it to its end;
the doorbell (`config_event_loader_<root>`) reloads that root only. A restored session takes its root's snapshot again.

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
so the application's yml is the two lines above and nothing else. See `seed-bootstrap-config/README.md`.

Live: the engine listens to `tenant_created` (§C.2) on the platform's Kafka. A new ROOT this application serves → the bootstrap read again,
the root's tree, doors, ledger, CDR road, journal and topics made, and it is served — no restart. A new reseller TIER → its root's tree
reloaded — and, first, THAT root's ledger loads the tier (`LedgerPort.tierAdded(tierDb)`: the switch ledger loads the one schema between
the root's money moves, see `seed-switch-ledger/README.md`, with what P2 must prove on PostgreSQL), so no session sees a tier its ledger
cannot serve. The same event twice is once; another application's is nothing. The engine's close closes everything the road opened: the
listener, the admins, every root's ledger (a switch ledger's MemLedger), CDR road and journal, the producers, the trees.

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
| finalize — `finalizeCost(ctx, tier, outcome)` | the final state, every tier, once | the cost of the session: delivered → rate × `billedUnits` (null = the units reserved); the reserve comes back, the difference is the final adjustment (more used than reserved = an extra debit, the call's rule); not delivered → the lane's refund rule (`refundPolicy` on `settleOutcome`) |

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
               Tier · Candidate · RootTree · TierLine · RoutePlan · RoutesphereEngine · AdmissionChain · SessionSettlement · SessionCdr
               Deployment · RootResources · TenantCreated · CdrEvent · TierRate …
spi/           LedgerPort · CdrSink · SdrSink · TaskSink · TenantLookup · TenantResolver · SessionJournal   (the host implements these)
               BootstrapSource · KafkaClients · TreesMaker · LiveTrees · LedgerMaker                         (the bootstrap's makers)
dependencies/  RoutesphereKit · KitMakers · BootstrapSources · TopicProvisioner · RoutesphereSettings · CdrSinks · Ledgers
               TenantResolvers · IdentificationRules · RefundPolicy …
internal/      BudgetChild · ChannelSlots · CdrAssembler · KafkaCdrSink · OncePerSessionCdrSink · OrchestrixLedger · FileCdrJournal
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

## 9 · History

- 2026-10-24 — P1-E, the framework round (the owner's R1–R13 of 2026-10-23): `identifyTenant` / `identifyPartner` forced with the base's
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
