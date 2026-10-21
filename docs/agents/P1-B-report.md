# P1 · stream B (the base) — report

Branch `p1-base` (worktree `routesphere-seed/worktrees/p1-base`), from `seed-context` @ 1297f1d + the brief 4c2e0c5.
Code head **1418404** (B11); this report is the commit after it. Written 2026-10-22 ~05:35 +06, unattended (the owner asleep).
Nothing pushed. Nothing left running. Nothing written to `~/.m2` (one early `install` was removed again, see §8).

## 0 · In one look

| § | what | state | commit | tests |
|---|---|---|---|---|
| A | 2.0.0-SNAPSHOT, statewalk 4.0.0 pinned, resolved version proved | done | 712e303, 61b9631 | StatewalkPinTest 2 |
| B | `RoutesphereFlow` = statewalk's `SessionHandler`, every call FINAL; context, kit, engine, the dry run | done | 712e303 (B0), 61b9631, e218fa4, 1418404 | Lifecycle 4, AdmissionWalk 6, AdmissionBudget 3, Kit 3, DryRun 2, OneTablePerLane 1 |
| C | (a) the root first, per protocol | done | 1310577, f9401b5 | RootFirst 8, KafkaCdrSinkPerRoot 1 |
| D | one snapshot per root; the doorbell per root | done | 61b9631, 1310577 | SnapshotPerSession 2, seed-tenant RootSnapshot 2 |
| E | (e) one ledger port; the budget child on 4.0; idempotent per (session, tier) | done | 83bac8a, 8d613b3, a9ee0bb, 2ffb0e7 | CreditControl 11, Rotation 1, SessionsInTheAir 2, switch-ledger ChainOnTheLedger 10 + WindowRenewal 7 |
| F | (f) handleRerouting by cause | done | c862269 | Rerouting 6 |
| G | (g) finishSession | done | c862269, 2d4e576 | FinishSession 5, CdrWire 3 |

**The reactor offline, clean copy of 1418404** (`git archive 1418404 | tar -x`, then `mvn -o -q test` at its root, nothing else on the
path): **352/0/0/3** (§4). Before P1 (1297f1d + brief): 425/0/0/4. B0 retired the 3.x engine's tests (−150); §B–§G re-proved them on the
new base: +77 since B0 (275 → 352; the switch ledger's 17 among them).

## 1 · §A — branch, version, pins

- `master` is an ancestor of `seed-context`: 0 commits of master missing from seed-context, seed-context 108 ahead. The round sits on the
  pinned SEED.
- Version **1.0-SNAPSHOT → 2.0.0-SNAPSHOT** in the parent and every module; the BOM's own libs as `${project.version}`.
- statewalk pinned ONCE: the parent's `statewalk.version = 4.0.0`, used by seed-sessionflow and managed in seed-bom. `statewalk-v2` left
  the BOM (no module used it). Module name kept `seed-sessionflow` [assumes D6].
- **The RESOLVED statewalk of every module** (`mvn -o test-compile dependency:tree -Dincludes=com.telcobright:statewalk,statewalk-v2,state-walk`):
  seed-sessionflow, seed-tenant, seed-switch-ledger → `com.telcobright:statewalk:jar:4.0.0` and nothing else; the other modules carry none.
  The first tree also showed `state-walk:1.0-SNAPSHOT` **through rtc-domain** (its own CampaignTask entity implements a state-walk
  interface; the seed never loads it) → excluded on the rtc-domain dependency of the three modules.
- `StatewalkPinTest` rewritten: the base IS a 4.0 `SessionHandler`, `SessionActive` exists (4.0 only), and the resolved jar's
  `pom.properties` says 4.0.0.

## 2 · §B — the base

`api/RoutesphereFlow<C extends RoutesphereContext> implements SessionHandler<C>`. Its only field is the `RoutesphereKit` (pool rule; the
logger is static). The library calls, each FINAL, each a short sequence of named steps:

| library call (state) | the base does | asks the application |
|---|---|---|
| `onStart` (IDLE → INIT) | the root by the ingress; the root's snapshot; the task. A refusal here is kept and answered by `admit` | (a) `identifySiblingLevelTenants`, `buildTask` |
| `prepare` (INIT) | the candidates; the budget child when the flow renews | `selectCandidates`, `renewsBudget` |
| `admit` (ADMITTING) | per candidate: the leaf, the tiers leaf → root (check · before-slot · the leaf's slot · authorize · root rules · reserve), the route at the root (C8), the last gate; a refused candidate gives every reserve of its try back | (c) `identifyEntryPartner`, `identifyPartner`; (b) `iterateResellerTenants`; (e) `reserveBalance` → `rateAtLevel`; (d) `routing`; `confirmAdmission` |
| `nextHop` (ROUTING) | the plan's current hop | (d) the plan made at admission |
| `retry` (CONNECTING) | records the attempt on the plan, then the rule | (f) `handleRerouting` → `rerouteActionFor` |
| `onProgress` / `onEstablished` / `onActive` / `onEnd` / `onFailed` | the PDD stamp; the failure kept | `onSignalingProgress`, `onAnswered`, `onServiceStarted`, `onServiceEnded`, `onSignalingFailed` |
| `settle` (answered final) | per tier, once, under the session's money lock | (e) `finalizeCost` → `delivered`, `chargeAtSettle`, `billedUnits`, `settleOutcome`, `refundPolicy` |
| `release` (FAILED, never answered) | every reserve back; the refund rule decides only a session offered and never consumed | `settleOutcome`, `refundPolicy` |
| `record` (both finals) | teardown, the slot, the CDR per tier on the root's road, the task, the SDR, the close | (g) `finishSession` → `onTeardown`, `cdrPerTier`, `fillCdr`, `buildSdr`, `onEnded` |

- **Deleted** (the 3.x base): `SessionFlow`, `SessionFlowSteps`, `SessionFlowContext`, `SessionFlowEngine`, `SessionMachine`,
  `SessionState`, `SessionFlowTimings`, `DryRun`, `LaunchResult`, `RequestFacts`, `publishes/BudgetStart`, `publishes/Preprocessed`,
  `internal/SessionFlowSupervisor`, `internal/LevelBalanceTracker`, `SessionFlowKit`, `SessionFlowSettings` — and the 3.x events are
  gone with statewalk 3.3.
- **Kept, re-pointed, logic unchanged**: `AdmissionChain` (the walk, the budget, the slot, undo, the belt, the F9 journal),
  `SessionSettlement` (+ `release`), `SessionCdr`, `SessionTaskClose`, `SpanRotation`, `CdrAssembler`, `ChannelSlots`, `OrchestrixLedger`,
  `CdrSinks`, the journals, `RefundPolicy` / `SettleOutcome`, `RerouteAction`, `TenantResolvers` / `IdentificationRules`.
- **New**: `RoutesphereContext`, `RoutesphereFacts`, `Ingress` (listen · esl · vlan · topic), `Candidate` (+ `SELF`), `Tier` (the live
  walk view), `RootTree` (one root's snapshot + its OWN partner map; an id two tiers hold names nobody), `TierLine` (a tier's persisted
  picture), `RoutePlan` (Route hops, Jackson-friendly), `RoutesphereEngine` (statewalk 4.0's registry: `SessionSupervisor.spec(flow.name(),
  contexts, flow)` + `.signaling(spec)` + the budget child + `.channel(channel, decoder)` + `.globalTimeout`, optional store),
  `RoutesphereKit`, `RoutesphereSettings`, `internal/BudgetChild`, `internal/TaskJson`, `internal/OncePerSessionCdrSink`, `spi/SdrSink`
  (statewalk 4.0 removed its own), `TenantLookup.snapshot(root)` (a default; seed-tenant's `TenantTrees` answers its real version),
  `CdrSink.publish(root, …)` (a default), `KafkaCdrSink.perRoot` / `CdrSinks.kafkaPerRoot` (`cdr_<root>`).
- **Persistence (§L.6)**: the context is ids and amounts — the root + its snapshot's version, the tiers as `TierLine`s, the settlements,
  the plan, the candidates, the task (as `TaskJson.TaskLine`). The live objects (`RootTree`, `LevelAdmission`s, tenants, partners, closed
  spans) are `@JsonIgnore`; before money the base takes the root's snapshot again by the root and rebuilds the tiers from their lines
  (`bringBackTheLiveTiers`), stamps `restoredAtMs` and notes when the version moved. A context with a task, a plan and a tier is < 4 KB.
- **Two cells, one lock**: in 4.0 the budget child and the supervisor run on two FIFO chains, so a renewal could cross the settlement:
  both take the context's `money` lock (a `ReentrantLock`, never a monitor — a blocking ledger call must not pin a carrier).
- The module README is rewritten for the new base.

## 3 · §C – §G

**§C (a) the root first.** `identifySiblingLevelTenants(facts)` = the kit's rules on `facts.ingress()`; no match → `NO_TENANT`, never a
default. `RoutesphereKit.builder().identification(rules)` refuses at build a rule naming a root this process does not serve. From then on
every lookup is scoped to the root: the entry (`entryOfPartner` on the root's own partner map), the walk (a chain ending at another root
is refused `PARTNER_NOT_FOUND` — the belt), the rates, the CDR's topic (`publish(root, …)` → `cdr_<root>`). Tested on two roots whose
partner ids overlap (both have 1 and 3), a request per root, cross-root ids refused with the ledger never asked, the belt against an
override, NO_TENANT (no record: no tree — `cdrLost` +1), the unknown-root rule, the four ingress kinds.

**§D per root.** A session takes its root's snapshot at its start (`ctx.tree`, `ctx.treeVersion`) and keeps it: a session started before
btcl's reload finishes on v1 (its tiers' tenants are the v1 objects), one after it sees v2, tele2 untouched. `TenantTrees.snapshot` answers
the served root's real version; its doorbell reloads that root only (tested in seed-tenant). A restored context re-takes the snapshot.

**§E credit control.** ONE `LedgerPort` [assumes D3]; its contract now says **idempotent per (session id, tier)**: references
`<session>#L<tier>`, `<session>#<try>#L<tier>`, windows `…#W<n>`. `reserveBalance` per tier at admission; `finalizeCost` per tier in the
final state: delivered → rate × `billedUnits` (null = as reserved), the rest back, negative = an extra debit; not delivered → the refund
rule. The budget child (`BudgetChild`, LevelBalanceTracker as a 4.0 `MachineSpec`): IDLE `.timeout(0)` → HOLDING `.timeout(0)` →
`SessionActive` → FIRST_RENEWAL / RENEWING → WINDING_DOWN; `BudgetExhausted` → statewalk's `Stop(BALANCE_EXHAUSTED)`; the final-window
cut; O4 rotation; its own small persisted context (a restored child finds its session through the kit). §L.2: a persisting engine whose
child renews faster than 60 s is refused. Every answered session is settled, never released (tested with ACTIVE's maximum). §L.1 tested
three ways: a crash between the settle and the final row (the older row restored through statewalk's mapper and ended again — 6 asks, 3
moves, the CDR once via `CdrSinks.oncePerSession`), on the REAL switch ledger after a restart (a new MemLedgerPort over the same MemLedger:
nothing moves), and the base's own once-guard (a second settle on one context never reaches the ledger).

**§F rerouting.** RETRY_SAME → the same hop; REROUTE → the plan's next hop while `attempts < maxAttempts` (v1's 3); FAIL_TERMINAL → the
end. The attempt is recorded on the plan before the rule; nothing is re-admitted or re-reserved; statewalk's CN-2 holds (a failure that
ended the signaling machine fails at once, the rule not asked). The v1 table is the lab's (and the voice sample's) `rerouteActionFor`.

**§G finish.** Both finals, once: teardown (once), the slot, ONE CDR message with a record per tier leaf first (each tier's own charge, the
hierarchy, the contract's JSON names, the root zone's wall clock), the task closed (SENT / FAILED, the cause), the SDR, `onEnded`; a step
that throws stops no other; every pool back (supervisor, signaling, budget child: available = created).

## 4 · Tests and numbers

The reactor offline after every commit (`mvn -o -q test` at the worktree's root; surefire reports cleared before each count):

| commit | total | seed-sessionflow | seed-switch-ledger | seed-tenant |
|---|---|---|---|---|
| 1297f1d + brief (before) | 425/0/0/4 | 210/0/0/1 | 49 | 12 |
| 712e303 B0 | 275/0/0/3 | 76 | 33 | 12 |
| 61b9631 B1 | 279/0/0/3 | 80 | 33 | 12 |
| 1310577 B2 | 291/0/0/3 | 90 | 33 | 14 |
| 83bac8a B3 | 300/0/0/3 | 99 | 33 | 14 |
| 8d613b3 B4 | 317/0/0/3 | 99 | 50 | 14 |
| c862269 B5 | 328/0/0/3 | 110 | 50 | 14 |
| f9401b5 B6 | 338/0/0/3 | 120 | 50 | 14 |
| 2d4e576 B7 | 344/0/0/3 | 126 | 50 | 14 |
| a9ee0bb B8 | 345/0/0/3 | 127 | 50 | 14 |
| 2ffb0e7 B9 | 346/0/0/3 | 128 | 50 | 14 |
| e218fa4 B10 | 349/0/0/3 | 131 | 50 | 14 |
| 1418404 B11 | 352/0/0/3 | 134 | 50 | 14 |

(B0's own row is derived: B1's 279 less its 4 new tests. The other modules are unchanged throughout: seed-campaign 72, seed-config 5,
seed-config-client 5, seed-context 20/0/0/1, seed-idgen 19, seed-routing 33/0/0/2. The skipped 4 → 3: the retired `KafkaCdrSinkIT`.)

**Clean copy of 1418404** (`git -C <wt> archive 1418404 | tar -x -C <scratch>/clean`, then `mvn -o -q test` at its root, offline):
**352/0/0/3** — seed-campaign 72, seed-config-client 5, seed-config 5, seed-context 20/0/0/1, seed-idgen 19, seed-routing 33/0/0/2,
seed-sessionflow 134, seed-switch-ledger 50, seed-tenant 14; the same as the worktree's run, module for module.

seed-sessionflow per class: ResellerPartnerRule 5, StatewalkPin 2, base.AdmissionBudget 3, base.AdmissionWalk 6, base.CdrWire 3,
base.CreditControl 11, base.DryRun 2, base.FinishSession 5, base.OneTablePerLane 1, base.Rerouting 6, base.RootFirst 8, base.Rotation 1,
base.RoutesphereFlowLifecycle 4, base.RoutesphereKit 3, base.SessionsInTheAir 2, base.SnapshotPerSession 2, ChannelSlots 6, RefundPolicy 16, KafkaCdrSinkPerRoot 1,
OrchestrixLedger 12, OrchestrixReturnRoad 21, TenantLookupCode 4, TenantResolver 5, InMemoryLedgerAccountKeyed 5.

The lab (`src/test/.../base/`): `Lab` — two roots on statewalk 4.0's real registry; `LabFlow` — the smallest application; `LabCall` — the
smallest signaling machine the 4.0 builder accepts (IDLE first, an `up` on every transition, Reject and Stop in every live state).

**Mutants** (a scratch script; each restored byte-identical; the worktree clean after): M1 reroute ignores the cap, M2 the belt lets a
foreign tree through, M3 no rule = a default root, M4 release always charges, M5 the budget child meters before SessionActive, M6 a
refused candidate keeps its reserves, M7 settle twice per context (survived first — the ledger's idempotency absorbed it; B9 added the
test), M8 the snapshot retaken on every call, M9 delivered charges the reserve whatever the units, M10 the CDR on no root's road —
**10/10 red**.

**With stream T**: a trial merge of `p1-samples` @ c5cf3d5 (T6) onto `p1-base` @ c862269 (B5) in a scratch clone: clean, the reactor
392/0/0/3 (seed-sessionflow-samples 60). T already merged B0–B4 into its branch (fdda807). B6–B10 add no API break (additions only:
`CdrSinks.oncePerSession` / `kafkaPerRoot`, `KafkaCdrSink.perRoot`, the task persisted).

**A flake, not mine**: one reactor run saw `OrchestrixLedgerTest.aReserveNeverWaitsLongerThanTheAdmissionHasLeft…` fail once (a local
HttpServer's connect over 500 ms while two builds ran); green on the rerun and in every other run. OrchestrixLedger and its test are
untouched in P1.

## 5 · Assumptions and decisions for the owner

The owner's open decisions (design §9), built on the recommended options:

| | assumption | where it shows |
|---|---|---|
| D1 | ONE signaling machine per lane (the ad: AdPlayback) | the engine registers exactly one `.signaling(spec)` plus helper children |
| D2 | the ESL endpoint identity is carried by the channel | the base reads `facts.ingress()` (`Ingress.esl(host, port)`); the channel's change is P3's |
| D3 | voice money through the base port (no bypass) | `LedgerPort` is the only money road; the base names no ledger |
| D4 | SMS refund by config, in P5 | the refund rule (`refundPolicy`, `delivered`) is ready in the base |
| D5 | the WiFi subscriber as a partner — sample only | T's WifiFlow |
| D6 | module name kept `seed-sessionflow` | the class is `RoutesphereFlow` |
| D7 | P1 after P0 | statewalk 4.0.0 released; built on it alone |

Decisions the base made where the design page left a gap — please confirm:

1. **The unshown rule lives in `release`.** In 4.0 an ad offered and never viewed never reaches the answer → FAILED → `release`. The
   design's table puts the unshown rule under `finalizeCost` (SUCCEEDED) and "every reserve back" under `release`. The base's `release`
   gives every reserve back **unless** `settleOutcome` says OFFERED_NOT_CONSUMED and the lane's policy charges it (the call's rule:
   unshown = CHARGE — the 2026-10-16 D1 default). The call's default is unchanged: a never-answered call pays nothing.
2. **`routing` runs at admission**, after the tiers and before the last gate (C8), so a candidate with no route is refused `NO_ROUTE` and
   the next is tried; `nextHop` reads the plan (the design's `routing(ctx).current()` holds — the plan is kept on the context).
3. **`billedUnits` null = as reserved**: a pre-rated event (a view, a message) pays its reserve; a lane priced by time overrides
   `billedUnits` (the ad's watched seconds) or `chargeAtSettle` whole (the voice call's pulses).
4. **§L.3 is the owner's business decision**: `billedUnits` sees `ctx.restoredAtMs`; the base bills nothing differently by itself, so a
   session restored after a lost BYE bills the downtime unless the lane says otherwise (the call's answer: P3, reconcile with FreeSWITCH).
5. Smaller shapes: `identifySiblingLevelTenants` has the brief's default (the design had it abstract) and answers the root's code (a
   String); `buildTask` answers a refusal (null = ok); `finalizeCost(ctx, LevelAdmission, Outcome)` returns the tier's `TierSettlement`;
   `identifyPartner(tier)` reads the tier below from `Tier.below()`; `applyRootRules(ctx, rootTier, partner)`; `NO_TENANT` is the base's
   word; a NO_TENANT session writes no CDR (no tree) — counted and logged.
6. **The switch ledger's second end after a restart** moves nothing, but its words are OWED ("no reserve row open … nothing moved"): after
   a restart it cannot tell "settled before" from "reaped". The money is right; the CDR of that second end is deduped downstream.

## 6 · DEFERRED — every use (statewalk 4.0 has none)

- seed @ 1297f1d: `SessionState.DEFERRED` (the state constant); `internal/SessionFlowSupervisor` (ADMITTED `--SignalingDeferred-->`
  DEFERRED, its `close(DEFERRED)`); `SessionTaskClose` (a DEFERRED session left its task OPEN); `SessionFlowContext`'s javadoc; the tests
  `SessionFlowLifecycleTest:198`, `SessionTaskCloseTest:238`; the sample `Wire` (a `Defer` frame → `SignalingDeferred`). All removed.
- consumers: **ad-sphere** `flow/internal/AdCallRegistry.java:146` treats DEFERRED as a final state — P2 must drop it. wifi-sphere and
  routesphere `callflow-v3-voice`: no use.
- In the new base a session ends SUCCEEDED or FAILED; "end by design, the task stays open" has no road. If a lane needs it (an SMS
  re-queued for later), the base would need a hook that keeps the task open on FAILED — not built (no lane asks yet).

## 7 · What is left

- **§L.4** is proved (B11: two lanes on one pooled H2 database through statewalk's JDBC store, one table each) — on H2, not MariaDB;
  the 64 KB `TEXT` cap (E-F1) is the owner's.
- **The dry run** is back (`simulate`, B11). A lane's `confirmAdmission` must claim nothing when `ctx.dryRun` is set (the 3.x hook had a
  `StepMode` parameter for it): P2's AdFlow must check `ctx.dryRun` before it claims the quota.
- **The 3.x engine's `busy` counter** is no longer counted: the door is statewalk's kit, which refuses a full pool with
  `RejectedExecutionException` (tested) before the flow sees the session; `launched` is counted again (B11). Count `busy` in the channel
  adapter, or drop it from `PoolStats`.
- **A restored session's CampaignTask** now travels with its context (B10); the `detail` map's values come back as JSON types (an `Long`
  may come back an `Integer`).
- The C1–C23 regression table and the four samples are stream T's (§H).
- The Kafka doorbell end to end (seed-context's `KafkaDoorbellIT`) needs a broker: still skipped offline.

## 8 · Notes for the architect

- Merge order: B (this branch, 1418404 + this report), then T. T already holds B0–B4; B5–B11 are additive.
- The other implementations of the one port: OrchestrixLedger is idempotent by the road's own key (road 16's reference; the return road
  "may be asked again"), MemLedgerPort by its reserve row, the testkit's InMemoryLedger by its closed tiers.
- One slip, repaired: an early `mvn install` (04:34) wrote the seed's 2.0.0-SNAPSHOT into `~/.m2`. I removed exactly what it wrote
  (the 2.0.0-SNAPSHOT version dirs, the four artifact dirs that did not exist before, the 2.0.0 lines of the metadata files) at 04:35;
  every later build ran `test` only. Nothing else outside the worktree and the scratch dir was written.
- The lab's tests on the real registry use real time (1–3 s per budget-child test); the whole seed-sessionflow suite runs in ~40 s.
