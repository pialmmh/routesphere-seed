# seed-sessionflow — RoutesphereFlow, the one base flow

One base for every kind of traffic: an ad view, a voice call, a WiFi session, an SMS. statewalk 4.0 owns the states; this
module owns the routesphere lifecycle; the application answers only its own questions (P1, design `routesphere-flow.html`).

```
statewalk 4.0 SessionSupervisor (final):  IDLE ─► INIT ─► ADMITTING ─► ROUTING ─► CONNECTING ─► ACTIVE ─► SUCCEEDED
                                                                          │ retry? ─► ROUTING            │
                                              every refusal, timeout, failure ──────────────────────────► FAILED
RoutesphereFlow (this module)              onStart · prepare · admit · nextHop · retry · … · settle · release · record  — all FINAL
the application                            the owner's methods (a)–(g) + the smaller hooks; ONE signaling machine (a MachineSpec)
```

## 1 · The classes you meet

| Class | What it is |
|---|---|
| `api.RoutesphereFlow<C>` | **The base.** statewalk's `SessionHandler<C>`: every library call implemented, final. You extend it once per application |
| `api.RoutesphereContext` | Everything one session knows (persisted: ids and amounts only). Your context extends it |
| `api.RoutesphereFacts` / `api.Ingress` | The facts your signaling machine stamps — with the INGRESS the root is found by (`listen` · `esl` · `vlan` · `topic`) |
| `api.RoutesphereEngine<C>` | statewalk's registry: the base supervisor + your signaling machine + the budget child + your channel |
| `dependencies.RoutesphereKit` | What the host hands in: the trees per root, the identification rules, the ONE ledger port, the sinks, the clock |

## 2 · Write an application

```java
public final class AdFlow extends RoutesphereFlow<AdContext> {
    @Override public String name() { return "AdSupervisor"; }                     // the registered name = the lane's own table (§L.4)
    @Override protected int serviceGroup(AdContext c) { return 30; }
    @Override protected Tier identifyEntryPartner(AdContext c, Candidate k) { return entryOfPartner(c, ((AdCandidate) k).advertiserId()); }   // (c)
    @Override protected TierRate rateAtLevel(AdContext c, Tier t, Partner p) { ... }                       // (e) the tier's rate; null = UNRATED
    @Override protected RoutePlan routing(AdContext c) { return RoutePlan.one(((AdCandidate) c.chosen).creative()); }    // (d)
    // and only what differs from the call's defaults: selectCandidates, confirmAdmission, delivered, billedUnits,
    // settleOutcome, refundPolicy, rerouteActionFor, buildSdr, onEnded, …
}

RoutesphereKit kit = RoutesphereKit.builder()
    .tenants(tenantTrees)                                  // seed-tenant's TenantTrees: one snapshot per root, reloaded per root
    .identification(IdentificationRules.fromBootstrap(config))   // each rule must name a served root — checked at build
    .ledger(port).cdr(CdrSinks.kafkaPerRoot(brokers, "cdr_", "ad-sphere")).tasks(campaigns::closed).sdr(sdrs)
    .zone(ZoneId.of("Asia/Dhaka")).settings(settings)
    .build();
RoutesphereEngine<AdContext> engine = RoutesphereEngine.of(new AdFlow(kit), AdContext::new)
    .signaling(AdPlayback.spec(...)).channel(httpChannel, AdFrames::decode).start();
```

## 3 · The owner's methods

| | method | default | when the base asks |
|---|---|---|---|
| (a) | `identifySiblingLevelTenants(facts)` | the kit's rules on `facts.ingress()`; none → `NO_TENANT`, never a default root | `onStart` (IDLE → INIT) |
| (b) | `iterateResellerTenants(leaf)` | FINAL: leaf → reseller → … → root, in the root's own tree | `admit` |
| (c) | `identifyEntryPartner(ctx, candidate)` | abstract: who pays, in which tier | `admit`, per candidate |
| (c) | `identifyPartner(tier)` | `res_<id>` → partner `<id>` of the tier above, else the partner named as the tenant | `admit`, above the leaf |
| (d) | `routing(ctx)` | abstract: a `RoutePlan` of `Route` hops, made once at admission (C8) | `admit`; `nextHop` reads it |
| (e) | `reserveBalance(ctx, tier, partner)` | `rateAtLevel` + ONE unit through the ledger port, `<session>#L<tier>` | `admit`, per tier |
| (e) | `finalizeCost(ctx, tier, outcome)` | delivered → rate × `billedUnits` (null = as reserved), else the refund rule | `settle` (answered) |
| (f) | `handleRerouting(ctx, failed)` | `rerouteActionFor(cause)`: RETRY_SAME · REROUTE (under `maxAttempts`) · FAIL_TERMINAL | `retry` (CONNECTING) |
| (g) | `finishSession(ctx, outcome)` | FINAL: teardown · the slot · the CDR per tier on `cdr_<root>` · the task · the SDR · `onEnded` | `record` (both finals) |

The order of the gates per tier is the call switch's: `checkPartner` → `authorizeBeforeSlot` → the leaf's channel slot →
`authorize` → `applyRootRules` (root only) → `reserveBalance`. A refused candidate gives every reserve of its try back; the next
reserves under `<session>#<try>#L<tier>`. `release` (never answered) gives every reserve back — the refund rule decides only a
session offered and never consumed (the ad's `unshown`). An answered session is settled, never released (statewalk's D-A1).

## 4 · Per root

Every lookup of a session is scoped to the root its ingress named: the entry partner (`entryOfPartner` — the root's OWN partner
map; an id two tiers hold names nobody), the tier walk (a chain that ends at another root is refused `PARTNER_NOT_FOUND`), the
rates, the CDR's topic. The session takes its root's snapshot at its start (`ctx.tree`, `ctx.treeVersion`) and keeps it to its end;
the doorbell (`config_event_loader_<root>`) reloads that root only. A restored session takes its root's snapshot again.

## 5 · Money

ONE ledger port (`spi.LedgerPort`: reserve · settle · release) — idempotent per (session id, tier): the same reference twice moves
money once, so a session restored from an older row and ended again (persistence is async) moves nothing twice (§L.1).
`CdrSinks.oncePerSession` dedups the CDR in the process; billing-core takes a session once per tier across processes.

The budget child (`internal.BudgetChild`, a statewalk 4.0 `MachineSpec`) is spawned in INIT when the flow renews
(`reservePeriodSec > 0`), rests until statewalk's `SessionActive` (G-1), renews every tier once per period (C14: the narrowest window
decides; a partial window arms the final cut; a refused window may rotate onto `nextAccount` — O4), and publishes `BudgetExhausted`
when the money ends (the supervisor sends `Stop(BALANCE_EXHAUSTED)` down). A persisting lane renews at most once a minute: every tick
rewrites the session's row (§L.2; the engine refuses less).

## 6 · Persistence

`RoutesphereContext` is persisted by statewalk (Jackson): ids and amounts only — the tiers as `TierLine`s, the plan, the candidates,
the root and its snapshot's version. The live objects (the `RootTree`, the `LevelAdmission`s, tenants, partners, the `CampaignTask`)
are `@JsonIgnore`; the base brings them back from the root's snapshot before it touches money. A subclass follows the same rule.
`billedUnits` sees `ctx.restoredAtMs` (§L.3: whether the downtime of a restored session is billed is the lane's decision).

## 7 · Packages

```
api/           RoutesphereFlow · RoutesphereContext · RoutesphereFacts · Ingress · Tier · Candidate · RootTree · TierLine · RoutePlan
               RoutesphereEngine · AdmissionChain · SessionSettlement · SessionCdr · SessionTaskClose · SpanRotation · CdrEvent · TierRate …
spi/           LedgerPort · CdrSink · SdrSink · TaskSink · TenantLookup · TenantResolver · SessionJournal   (the host implements these)
dependencies/  RoutesphereKit · RoutesphereSettings · CdrSinks · Ledgers · TenantResolvers · IdentificationRules · RefundPolicy …
internal/      BudgetChild · ChannelSlots · CdrAssembler · KafkaCdrSink · OncePerSessionCdrSink · OrchestrixLedger · FileCdrJournal …
testkit/       InMemoryLedger · RecordingCdrSink · TenantTreeBuilder · ManualClock
```

## 8 · Tests

```bash
mvn -o test        # at the reactor's root: the whole suite (never one module alone)
```

`src/test/.../base/` proves the base on a real statewalk 4.0 registry: `Lab` (two roots with overlapping partner ids), `LabFlow`
(the smallest application), `LabCall` (the smallest signaling machine the 4.0 builder accepts). The four samples (ad, voice, WiFi,
SMS) and their scenarios live in `seed-sessionflow-samples`.

## 9 · History

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
