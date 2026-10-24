# P1-F · stream F — report

The six seed gaps the ad-sphere port (P2) found (`ad-sphere/worktrees/p2-adflow/docs/ad-as-call/P2-state.md`, "Gaps for the seed"),
fixed ADDITIVELY on `p1-follow` (from routesphere-flow 723f160): new doors, every existing signature kept, no behaviour P2 relies on
changed. Every Maven run offline and under the shared lock; nothing installed into `~/.m2`; nothing pushed. State, line by line:
`docs/agents/P1-F-state.md`.

## Commits

| item | commit | what |
|---|---|---|
| 1 | 8a2324c · 1b dc2a990 | The bootstrap url and the token variable's name at RUN TIME only — the box's `config/application.yml` beside the jar (the unit's working directory) or the unit's environment (`PRIME_CONTEXT_BOOTSTRAP_URL`, `PRIME_CONTEXT_TOKEN_ENV`); never in the jar, nor in the build's own `config/`, `.env` or shell (the build and its `@QuarkusTest`s read them too). **No guard**: no reliable, documented signal tells a `ConfigSourceFactory` it runs inside the build — what was looked at is written in `seed-bootstrap-config/README.md`. Docs + one javadoc bullet. |
| 2 | f346695 | `RoutesphereKit.toBuilder()` — a kit made from the bootstrap takes the lane's own sinks before it is used: `fromBootstrap(source, app, makers).toBuilder().sdr(..).tasks(..).build()`. Every part the road made is kept (trees, doors, ledger, CDR road, journal, clock, zone, settings and the road itself: the engine on the new kit starts it and its close closes it). Refused in words once an engine runs on the kit. |
| 3 | 35081a6 | `CdrSinks.all` (`FanOutCdrSink`) hands `publish(root, callId, tiers)` to every sink. Before, a per-root sink behind the fan-out was handed the root-less call and published NOTHING. |
| 4 | d8f6769 | `machinesBuilt` = the supervisors statewalk's pool built (`poolStatistics(flow.name()).totalCreated()`); `FlowCounters.machinesBuilt` kept for its readers, deprecated for removal, never written (the base builds no machine). `busy` = a NEW session statewalk refuses because every machine is live (`… refused: CAPACITY_EXCEEDED`, `internal/BusyAtTheDoor`), counted at the lane's channel's inbound (`internal/DoorChannel`) and at the new door `RoutesphereEngine.launch(id, firstFrame)`; `deliver()` counts nothing. |
| 5 | 5f3f07e | `PoolStats.sessions` (the 11th number: `registry.activeIdCount()`); `live` kept as it was — the registry's CELLS (two per session, three with the budget child), both in the javadoc; the ten-number constructor of 2.0 kept (`sessions` = `PoolStats.NOT_COUNTED`, -1). |
| 6 | 8f6bc3c · proof 46a6c47 | `testkit.FakeKafka` builds its producer with `MockProducer(boolean, Partitioner, Serializer, Serializer)` — in kafka-clients 3.7.1 and 4.0.0 (4.0 removed the three-argument one); the partitioner null (an empty cluster: partition 0, no partitioner asked, in both). |
| docs | 66ec564 | The README's history line. |

## Numbers

- **Base 723f160**: the reactor from clean **518/0/0/3** (run/failed/errors/skipped; 129 s).
- **Head 66ec564**: the worktree from clean **535/0/0/3** (153 s) and a git-archive clean copy of the head **535/0/0/3** (136 s), one
  lock hold, 06:08–06:13.
- **+17 tests, all new**: LaneSinksOnTheBootstrapTest 4, CdrFanOutTest 3, PoolCountersTest 4, BusyAtTheDoorTest 3, PoolSessionsTest 2,
  FakeKafkaOnKafka4Test 1. **No existing test was touched or deleted.**
- **Red first** for every code item: item 2 by compile (no `toBuilder`); item 3 3/3 red on behaviour (one a CDR published nowhere);
  item 4 red on behaviour (machinesBuilt 0 ≠ 2, busy 0 ≠ 1) and by compile for `launch`; item 5 by compile; item 6 the guard red (the
  removed descriptor in FakeKafka's class file) and the run below.
- **Mutants: 37, 37 red** (item 2: 10, item 3: 5, item 4: 16, item 5: 5, item 6: 1); each restored with a fresh mtime and recompiled.
- **Item 6 on both kafka-clients** (both jars in `~/.m2`), outside Maven on git-archive copies, the six FakeKafka test classes (29 tests):

  | code | compiled on | run on | result |
  |---|---|---|---|
  | 35081a6 (before) | 3.7.1 | 4.0.0 | 29 run, **19 failed**: `NoSuchMethodError MockProducer.<init>(boolean, Serializer, Serializer)` — P2's symptom |
  | 35081a6 (before) | 4.0.0 | — | does not compile: FakeKafka.java:47 |
  | 8f6bc3c | 3.7.1 | 3.7.1 | 29/0 |
  | 8f6bc3c | 3.7.1 | 4.0.0 | 29/0 — the seed's jar as ad-sphere runs it |
  | 8f6bc3c | 4.0.0 | 4.0.0 | 29/0 |

## Decisions (defendable defaults, the owner asleep)

1. **Item 1, no guard.** SmallRye's `ConfigSourceContext` (3.13.4) has no phase; the profile is `prod` at the build and the run;
   Quarkus's `LaunchMode.NORMAL` is "a normal production build" at both and is not visible to a plain SmallRye library;
   `ExecutionMode.UNSET` is undocumented and UNSET in any plain JVM. The documented road is a Quarkus extension registering the source
   for the run time only (`RunTimeConfigBuilderBuildItem`) — a recommendation, not code.
2. **Item 2, one door: `toBuilder()`** rather than `fromBootstrap` overloads taking two sinks — it also takes ad-sphere's local CDR
   journal beside the root's road, so P2's whole `kitOnTheBootstrap` becomes one chain.
3. **Item 4, `deliver()` counts nothing.** ad-sphere counts its own busy around `deliver` (`AdDispatch.refusedAtTheDoor`;
   `AdCallStoryTest` asserts busy == 1): counting there would double it. The seed counts at the channel's inbound and at the new
   `launch()`.
4. **Item 4, `FlowCounters.machinesBuilt` kept** (the signature rule), `@Deprecated(forRemoval = true)`, never written — it has no
   place: nothing in the seed builds a machine.
5. **Item 6, the seed's own tests left on the three-argument constructor** (`KafkaCdrSinkPerRootTest`, `SessionsInTheAirTest`,
   seed-tenant's `DoorbellTest`): not the testkit, run on the seed's 3.7.1 only — named in the README.

## What P2 must change to use the new doors

1. **`AdDispatch.kitOnTheBootstrap`** → one chain, before the flow is made:
   `k.bootstrap().kit().toBuilder().sdr(new AdSdrSink(onRecord)).tasks((ctx, row) -> k.campaigns().closed(row))` plus, when the process
   keeps its local CDR journal, `.cdr(CdrSinks.all(k.cdr(), <that kit>.cdr()))`, then `.build()`. The clock, zone, settings and the road
   come with it.
2. **`AdCdrRoads` can go**: `CdrSinks.all` hands the root to every sink now.
3. **`AdDispatch.accept`** → `engine.launch(id, frame)` instead of `engine.deliver(id, frame)`, AND remove its own
   `counters().busy.incrementAndGet()` in `refusedAtTheDoor` — keep both and busy counts twice. (Keeping `deliver` + its own count stays
   right as today.) Note: ad-sphere answers `busy` for ANY refusal at its door; the seed counts only statewalk's CAPACITY_EXCEEDED (a
   shutdown is not busy) — ad-sphere's word to its caller is its own choice.
4. **`AdDispatch.stats()`** → `engine.stats()` as it is: `machinesBuilt()` is the number ad-sphere reads today
   (`poolStatistics(AdFlow.NAME).totalCreated()`), `sessions()` the views live (`activeIdCount()`). Its override puts sessions into `live`;
   `engine.stats().live()` is CELLS (2 per view) — read `sessions()` where a view count is meant.
5. **`TestKafka`** (its copy of FakeKafka) → the seed's `testkit.FakeKafka`: it runs on kafka-clients 4.0.0 now.
6. **The box**: the two lines only in `config/application.yml` beside the jar (the unit's `WorkingDirectory`) or the unit's environment
   — `P2-box-application.yml` already does this; keep no `config/` folder or `.env` in the project directory.
7. Re-pin the seed to the merged head (its private repository from `git archive`, as P2 1b did).

## Open points

- `BusyAtTheDoor` reads statewalk's refusal WORDS: 4.1 gives no typed refusal and no refusal counter or hook (its stage carries a
  `RejectedExecutionException` whose message ends `refused: CAPACITY_EXCEEDED`). `PoolCountersTest` pins the words on the real registry,
  so a statewalk that words it otherwise is red there. A typed refusal in statewalk would be the clean road — a statewalk recommendation.
- The registry now holds a `DoorChannel`: `registry().getChannel()` is the door (`lane()` is the lane's own object). Nobody in the seed or
  in P2 reads it.
- `FlowCounters.machinesBuilt`: remove in a later round, when no reader is left.
- seed-switch-ledger's `SwitchLedgerOnTheBootstrapKitTest` also uses FakeKafka; it was not in the 4.0.0 run (MemLedger's classpath and
  JVM flags) — the six seed-sessionflow classes cover FakeKafka's producer, consumer and admin.

## P1-G

The architect's brief: `docs/agents/P1-G-brief.md` (live tenants made safe, the naming table, the late config values). On this branch
after the P1-F report head 0d170db; additive — every existing signature kept (the `Deployment` 12-word constructor, `withSeedRoot(root)`,
`LiveTenantTrees.started(trees, served)`, `LiveTrees`' methods with defaults), ad-sphere's P2 on 723f160 + P1-F must not break.

### Commits

| item | commit | what |
|---|---|---|
| 1 | c54be9c | A new root that cannot be served yet is never half-served: `LiveTenantTrees.serve` undoes a failed serve (out of the directory again); the road keeps such a root PENDING (`internal/PendingRoots`): the bootstrap read again + the tree loaded every `config.routesphere.pending-root.retry-sec` (30), given up after `give-up-min` (120); said once pending (the reason in words), once served, once given up; one daemon thread `<app>-pending-roots` per road, stopped by the engine's close; a later word still tries at once. `api/PendingRootPace`, `Deployment.pendingRoot`, `road.pendingRoots()` / `road.said()`. |
| 2 | bc8f3a0 | A root's tree never shows a tier its ledger was not handed: `spi/TreeGate`, `LiveTrees.gateEachNewTree` (default: nothing); the road's gate `internal/TiersFirst` hands each tier of a NEW tree its ledger was not built over to that root's ledger FIRST, once — the doorbell's ring and `tenant_created` together are one hand, whichever comes first; seed-tenant's loader passes every loaded tree through the gate before the swap (`TenantTreesBuilder.gate`, `TreeGateHolder`). |
| 3 | 1b8f9d0 | A new tier's own account at its PARENT is loaded: after the tier's schema, the switch ledger's port calls `MemLedger.reloadNewEntities()` (a plain call on the pinned jar) under the same write gate (`internal/NewRowsOfATier`); a reserve row the reload would bring back is a ghost and is taken out at once. |
| 4 | b69099c | prime-context's `naming-cases.tsv` (8f8d922) byte-identical in seed-naming's test resources (sha256 3dfb24f2414f593f…, pinned by the test); `TenantNames` answers all 23 cases as prime-context's RootNaming — **no red to report**. |
| 5 | a292366 | `naming.seed-prefix` read beside `seed-root` (`Deployment.seedPrefix`, `TenantNames.withSeedRoot(root, prefix)`, `seedPrefix()`); `res_` when not said (said once); not a name prefix → refused at the read in words. |
| 6 | 0c26518 | Why item 12's values come late — proven on Quarkus 3.26.1 offline (`docs/agents/P1-G-quarkus-probe`); no code change; the rule and what an app must do in `seed-bootstrap-config/README.md`. |
| 7 | 7689115 | README lines (committed before item 6: item 6's Quarkus proof waited on the shared lock; each item stands alone). |

### Numbers

- **Head 0c26518** (all seven items): the worktree from clean **557/0/0/3** (131 s) and a git-archive clean copy of the head **557/0/0/3** (128 s),
  one lock hold 07:09–07:13 (offline, no install); the P1-F head 66ec564 was 535/0/0/3.
- **+22 tests, all new**: ServeOrNothingTest 2, PendingRootTest 4, TiersBeforeTreesTest 4, GateBeforeTheSwapTest 2, ParentRowsOfANewTierTest 2,
  NamingCasesTest 2, SeedPrefixTest 3, SeedPrefixFromTheBootstrapTest 3. **No existing test's assertion changed**; two test helpers grew:
  `BootstrapWorld` (+ `makers(Clock)`), `FakeLiveTrees` (the gate on serve and reload). P1-E's `TenantCreatedLiveTest` stays green unchanged.
- **Red first** for every code item (items 1–3 on behaviour: link3 half-served; the tree's refusal thrown out of the event and a not-listed
  root never tried again; a ring showing a tier with no hand; the parent's row absent; items 4–5 by the missing table / API).
- **Mutants: 35, 35 red** (item 1: 11, item 2: 11, item 3: 4, item 4: 4, item 5: 5); each restored with a fresh mtime and recompiled.
- **Item 6's proof** (a factory registered as ours, serving `probe.value`; the jar says `from-the-jar`; `@QuarkusTest` and the packaged jar):

  | reader | saw |
  |---|---|
  | the factory's calls | `[RUNTIME_INIT]` — once |
  | a `@Startup` bean nobody injects | from-the-factory @RUNTIME_INIT |
  | `@Observes StartupEvent` + `ConfigProvider.getConfig()` | from-the-factory @RUNTIME_INIT |
  | a `@Startup` bean a REST resource and a request filter inject by its type | **from-the-jar @STATIC_INIT**, still at a later request |
  | the same reached through `Instance<…>` | from-the-factory @RUNTIME_INIT |

### Decisions (defendable defaults)

1. **Item 2: a ledger that SAYS its own failure lets the tree be shown** (the switch ledger without `addDatabase`, the pinned mem-ledger):
   freezing the whole root's tree for one tier it cannot load would stop every other change of that root; the tier's money is refused
   until it loads (the port's own words). A ledger that THROWS keeps the tree from being shown.
2. **Item 2: the event still hands a tier the ledger was built over** (the ledger's own "nothing") — P1-E's test of the event kept as it is;
   the gate hands only tiers the ledger was not built over.
3. **Item 3: the ghost reserves.** The pinned `reloadNewEntities` is a whole-table read + `putIfAbsent` (bytecode); a settled reserve whose
   row the write-behind has not deleted would come back, and the orphan reaper would give its money back twice — taken out at once (the
   port is the only writer of its root's reserves: STANDALONE). Cost: the pinned jar reads both tables of every tier schema WHOLE; the
   high-water mark is mem-ledger 0f04624 (2026-10-11), LATER than the pin (2026-10-07) — from it on, the accounts above their mark.
4. **Item 6: no code change.** Marking the factory `@StaticInitSafe` would make a native image fetch prime-context at its BUILD and Quarkus
   ask it twice per start; the README says when the values are visible and what an app does.

### What P2 must change (P1-G)

1. **`AdTrees` (its own LiveTrees)**: `serve` must leave nothing when prime-context answers 404 ROOT_NOT_SERVED (throw, nothing kept) —
   the road then keeps the root pending and tries again; and `gateEachNewTree(gate)`: keep the road's gate and call
   `gate.pass(root, newTree)` before it swaps its own snapshot — else a doorbell ring before `tenant_created` shows a tier its ledger lacks.
2. **`AdSphereRuntime`**: its filters and resources reach it through `Instance<AdSphereRuntime>` (`get()` when they serve) — then it is
   created at RUNTIME_INIT and sees the bootstrap's values (proven), and `AdBootstrap.configOver` can go; or keep `configOver`.
3. Prove on PostgreSQL (mem-ledger main, with `addDatabase`): `AdTierCreatedPostgresIT.gap_…` (the parent's account) should now pass.
4. Nothing to change for the seed prefix or the pending pace; a deployment document that sets `pending-root` needs prime-context's
   checker to accept it (prime-context's round).

### Open points

- Item 1: a root whose roads fail half-way (its ledger made, then a later road fails) is retried whole — the first ledger would be made
  again (`PerRootLedger.add` replaces it unclosed). An edge, not fixed.
- Item 3: the ghost guard assumes STANDALONE (one writer per root's reserves); a replicated switch ledger needs another rule.
- Item 6: an opt-in `@StaticInitSafe` factory for JVM-mode apps (one fetch, cached for the run-time ask) — a later round if wanted.

## P1-H

The coordinator's round after P1-G: a root's roads all-or-nothing (P1-G's open point 2, rated a money risk), two doc lines, and — after
the gate of 9d97760 went red under load (371/2/0/3 at load ~6) — the timing tests made to hold under load. On this branch from 9d97760.

### Commits

| item | commit | what |
|---|---|---|
| 1 | ac897e3 | A root's roads are all-or-nothing. `serveItsRoads` opens the ledger, CDR road and journal (each written down as opened, `internal/RootRoadsMade`), the topics, then the doors, the deployment LAST; any failure closes what THAT try opened — the ledger first, then the CDR road (never its shared producer; a producer the try made for a Kafka no other root uses is closed) and the journal —, takes the tree's serve back (`LiveTrees.unserve`, a new default doing nothing) and the root goes pending. The road never opens a second ledger for a root while one is open (`refuseASecondLedger`, an ERROR in words); a ledger whose close failed is kept as still open and closed first at the next try. A start refused half-way closes what it opened. |
| 2 | 177fdc7 | Docs: a deployment must not set `pending-root` until prime-context's `DeploymentShape` accepts it; ONE writer per root (STANDALONE) said where a second could be configured — the switch ledger's README, `SwitchLedgerMaker` (javadoc and its `replicationMode` line), the sessionflow README. |
| 3 | 9f6873b | The timing tests hold under load (below). |

### Numbers

- **Head 9f6873b**: the worktree from clean **562/0/0/3** (136 s) and a git-archive clean copy of the head **562/0/0/3** (136 s),
  one lock hold 07:56–08:01 (offline, no install); the P1-G head 0c26518 was 557/0/0/3.
- **+5 tests, all new**: RootsRoadsAllOrNothingTest 5. Red first as the architect said: TWO open ledgers for link3 on the timer's road; the
  journal's exception thrown out of `onTenantCreated` on the event's road (a half-served root, no retry); the refused-start test written
  with the fix, its red shown by its mutant. **8 mutants, 8 red** (the undo's close, the ledger left in, the tree not taken back, the CDR
  road left in, the shared producer closed, the guard off, the deployment switched first, a refused start closing nothing).
  Not covered by a test: the journal's undo after a LATER step fails (the topics, the doors) — no step after the journal fails in the fakes.
- **Item 3, under an 8-thread CPU hog** (scratch `Hog.java`, one busy thread per core, stopped by PID; load 6–13), 20 runs each, no
  recompiling between runs:

  | class | before (the tests as they were) | after |
  |---|---|---|
  | PendingRootTest | **16 of 20 runs red** — 20 of 80 tests: "Expecting empty but was: [link3]" (the gate's failure: the deployment seen served before the root left pending) and "Expected size: 1 but was: 0" (the give-up line not yet written when the root left pending) | **0 of 20**: 80 run, 0 failed |
  | OrchestrixLedgerTest | **1 of 20 runs red**: aTimeoutRetriesTheSameReferenceOnce — "timed out twice" (a quick answer later than the 300 ms read timeout). The gate's own failure (the 500 ms connect timeout) did not come back in 20 runs; its cause is the same one (two clocks too close) | **0 of 20**: 240 run, 0 failed |
  | DebounceGateTest (hardened too) | — | 0 of 10 under the hog: 40 run |

### Item 3, what changed

- **PendingRootTest** waits on the WHOLE condition — served AND no longer pending — with a 30 s deadline, never a fixed sleep. One product
  change: `PendingRoots` says "given up" BEFORE the root leaves pending (a root no longer pending has been said).
- **OrchestrixLedgerTest** keeps its three clocks apart: the connect 30 s (a slow local accept never fires it), the read timeout 1 s (a
  quick answer stays under it), the slow answer 3 s (a late client timer still fires first); the repeats get 10 s of admission time; the
  "not its own 5 s" bound is 4.8 s. `slowestAnswerMs` reads the constant; one `as()` text names the read timeout instead of "300 ms".
- Also hardened (cheap): RootsRoadsAllOrNothingTest and TenantCreatedLiveTest wait 30 s; seed-config-client's DebounceGateTest (three
  presence checks after fixed sleeps → each waits for the fire; the burst's first fire waits 10 s) and DebounceGateCapTest (the trailing
  fire waited for, then 0.5 s for no second one). Every assertion's meaning unchanged.

The seed's other sleeps, read one by one and left:

| kind | where | why it is left |
|---|---|---|
| a rate by design | DebounceGateCapTest's gaps 5.5–8.5 s, ≥ 4 fires in 30 s | it measures the cap's pace; a loaded box stretches it — the one load-sensitive check left (34 s a run) |
| absence after a fixed sleep | DebounceGateTest burst (250 ms, no second fire), TenantContextsTest :114 :135 :165, CreditControlTest :188, switch-ledger CreditTest :80, TierAddedLiveTest :84 :113 | a loaded box can make them pass without proving, never red |
| time made to pass (pacing) | OneTablePerLaneTest :59 :61, VoiceFlowTest :78, WifiGatewayTest :60, EslCallTest :74, RestoreLostUpTest :73 :133 :176 :217, RotationTest :40, QueuedCampaignStoreTest :291 (a releaser thread) | a loaded box only adds time |
| a poll with a deadline (3–8 s) | FinishSessionTest :116, CreditControlTest :210 :231 :268, RotationTest :39, Bed :88, FlowBed :93, TenantTreesTest :127, DoorbellTest :40, RootSnapshotTest :38, LedgerLab.await, SwitchLedgerMakerTest :68, SwitchLedgerOnTheBootstrapKitTest :117, VoiceFlowDoorbellTest :59, WifiFlowTest :104, VoiceFlowTest :272, OrchestrixLedgerTest.journalLines, QueuedCampaignStoreTest :140, BucketNextLiveTest :115, RestoreLostUpTest :308 | they wait on the condition; a deadline can be raised where a gate ever shows one |
| an integration test | seed-context KafkaDoorbellIT | real Kafka; not in the reactor's run |

### What P2 must change (P1-H)

1. **`AdTrees.unserve(root)`** (the new `LiveTrees` default does nothing): take a root out again when the road's try fails after its
   tree was served — else its tree stays served without doors or roads until the next try (harmless, but not "nothing").
2. **One process per root**: a root's switch ledger has ONE writer (STANDALONE); never two ad-sphere instances on one root.
3. A deployment document must not carry `pending-root` until prime-context's checker accepts it.

## P1-I

The architect's brief (`docs/agents/P1-I-brief.md`): a connect cut by the admission's budget says so; the budget test holds under load;
the root's source guarded. On this branch, fast-forwarded by the architect to routesphere-flow 97afef3 (the owner's readable shape R14,
gate 569/0/0/3). Additive; every existing signature kept.

### Commits

| item | commit | what |
|---|---|---|
| 1 | e35fed7 | A connect that did not complete says which clock cut it (`OrchestrixLedger.noConnectionWithin`). Java's HttpClient applies the request's own timeout (the smaller of the read timeout and the time left) to the connect phase too. So: the admission's budget → "no connection within the N ms the admission had left"; the read timeout, when it is the shorter clock → "within its N ms read timeout"; the configured connect timeout → today's words. Always a `LedgerFault`: nothing was sent, the money is certain (no unsure line). |
| 2 | 9a91dc4 | The budget test's clocks far apart: the budget 1 s (a loaded local connect finishes inside it), the answer 3 s, its own read timeout 5 s; the words matched for any N; the elapsed between the budget and its own timeout. |
| 3 | 4650da7 | Docs: `RoutesphereFacts.root()` and the README (§4, the root's source) — the root is the lane's rule over the ingress, NEVER a value the request carries (a body field, a header, a query): a caller that could name the root could bill another operator. |

### Numbers

- **Head 4650da7**: the worktree from clean **572/0/0/3** (142 s, 14:12) and a git-archive clean copy of the head **572/0/0/3** (138 s,
  14:14), two short lock holds (offline, no install); 97afef3 was 569/0/0/3.
- **+3 tests, all new**: OrchestrixLedgerConnectTest 3 — red first on a connect that cannot complete (a local port whose accept queue
  is full: backlog 1, never accepted; the kernel drops the SYN): 2 of 3 red with "no connection within 30000 ms", the architect's finding
  reproduced at will. **6 mutants, 6 red** (item 1: 5 — one turns the cut connect into a NoAnswer and an unsure line appears; item 2: 1 —
  the budget not applied). One existing test touched: OrchestrixLedgerTest's budget test (its clocks, its words' match, its bound).
- **Item 2, under an 8-thread CPU hog** (stopped by PID), OrchestrixLedgerTest 20 runs each: **before** (the 200 ms budget, item 1's
  code) 0 of 20 red, 240 tests, load 9→18; **after** 0 of 20 red, 240 tests, load 13–17. The cut connect the gates saw (twice, at load
  ~3.5–6) did not come in these 20 runs; item 1's test makes it on demand, and the new budget (1 s) leaves a loaded connect five times
  the time the old one did.

### For P2 / the owner

- Nothing to change in ad-sphere for P1-I; its round (P2c) pins the root's source with a test on its side.

## P1-J

The architect's brief (`docs/agents/P1-J-brief.md`): ad-sphere P2c's end-to-end run against prime-context 8f8d922 found two seed gaps —
G1 (accounts written after a tier exists never reached the switch ledger) and G3 (one switch-db login for both the app's and the ledger's
roles). On this branch, fast-forwarded to routesphere-flow 206e7f0 (the merge of P1-I). Additive; the pinned §C samples byte-identical.

### Commits

| item | commit | what |
|---|---|---|
| 1 | 277b4d9 | Every new tree passes the road's gate before it is shown and now tells its root's ledger `treeReloaded()` (a new `LedgerPort` default doing nothing; `PerRootLedger` routes it). The switch ledger merges the rows written since (`NewRowsOfATier`: `reloadNewEntities`, ghost reserves out) under its write gate, at most ONE merge per `ledger.merge-spacing-sec` (default 5 s): the first ring at once (before the tree is shown), the rest of a burst ONE merge at the end of the spacing (`internal/CoalescedMerges`, the port's daemon thread). Never on a session's thread. |
| 2 | 6f7f8bf | The switch ledger's OWN login: an optional `ledger.db {user, password-ref[, url, schema]}` (`RootResources.ledgerDb`, the six-word constructor kept; `ledgerLogin()`, url and schema switch-db's where it says none). `SwitchLedgerMaker` connects with it when present; absent = switch-db's, as before. Its password from the process environment only. A NEW sample `bootstrap/deployment-with-ledger-login.json`. |
| 3 | cc7e90b | Docs: the switch ledger's README (the merge on every reload — its bound, cost, threads; the two logins and why; PC-0023 first) and the sessionflow README's history line. |

### Numbers

- **Head cc7e90b**: the worktree from clean **580/0/0/3** (148 s, 16:26) and a git-archive clean copy of the head **580/0/0/3** (148 s,
  16:29), two short lock holds (offline, no install); 206e7f0 was 572/0/0/3.
- **+8 tests, all new**: NewAccountsReachTheLedgerTest 2 (switch-ledger), LedgerLoginFromTheBootstrapTest 3 (sessionflow), LedgerOwnLoginTest 3
  (switch-ledger). Red first: G1 on P2c's order — an account written after its tier exists, a ring → "ACCOUNT_NOT_FOUND: account 77 of
  tier 0 is not held in schema btcl_res_5"; G3 by compile (no `ledgerDb`). **12 mutants, 12 red** (item 1: 6 — the gate silent, the port
  deaf, the merge empty, no coalescing, the burst never merged, a deferred merge per ask; item 2: 6). No existing test touched.
- **Under an 8-thread CPU hog** (stopped by PID, load 6→15): NewAccountsReachTheLedgerTest 20 runs, 0 red (40 tests).

### Decisions

1. **The merge runs at once on the first ring and BEFORE the tree is shown** (the gate's thread): G1's single ring after a purchase gives
   no ACCOUNT_NOT_FOUND window. Only an account written inside a burst waits — at most one spacing.
2. **Every reload counts, the backstop's too** (seed-context reloads every root each 5 min): the loader is not told why it loads; the
   cost is one merge per root per backstop, said in the README.
3. **The ledger's own password from the environment only**, as the brief asks. switch-db's password is UNCHANGED (the environment, then
   the envelope's `secrets` — P1-E's behaviour, pinned by `SwitchLedgerMakerTest`); that fallback contradicts P2's G4 "house rule" —
   left for the architect to rule on (a behaviour change).
4. `ledger.db`'s user is read as `user` (the brief's word) or `username` (switch-db's).

### For P2

1. Use `ledger.db` (the ledger's role) once prime-context accepts the key (PC-0023); until then the lab's role membership stands.
2. Nothing to change for G1: the merge is the seed's; P2's own `AdTrees` must call the road's gate (P1-G) for its reloads to merge.

## P1-K

The architect's brief (`docs/agents/P1-K-brief.md`), on the owner's ruling of 16:51 ("Environment only", re-affirming his rule of
2026-09-24): a password comes from the process environment only. The coordinator added item 5 during the round, on the owner's second
ruling: "if we fail to identify the root level tenant at the ingress points, the request shall be rejected and logs shall be generated".
On this branch, fast-forwarded by the architect to routesphere-flow c17ffaa (his merge of P1-J). Additive except where the ruling removes a road.

### Commits

| item | commit | what |
|---|---|---|
| 1 | 2a5a919 | switch-db's password from the PROCESS environment only: `SwitchLedgerMaker` reads `SwitchDb.passwordFromTheEnvironment` for both logins (switch-db's and the ledger's own). `SwitchDb.password(env, secrets)` removed. A missing variable is refused naming the variable and the login, never a value; a `password-ref` that is not `env:<NAME>` is refused WITHOUT repeating its text. SwitchLedgerMakerTest's pin moved here (the removed method would not compile otherwise). |
| 2 | d7620dc | The seed reads no secret from the bootstrap: said in `Deployment`'s javadoc and the README's bootstrap section. `Deployment.secrets()` deprecated (read by nobody; the signature kept; the reader still parses). A `Deployment`'s text names the secrets, never their values. |
| 3 | 53f8657 | Docs: the switch-ledger README (the password's one road, the refusal's words), the sessionflow README's history line. |
| 5 | 94e00c1 | The door refuses AND says: `RoutesphereEngine.identifyRootLevelTenant` — no root (null, or the rule throws) → a WARN line, `refusedNoRoot` counted (FlowCounters; PoolStats' twelfth number, the 10- and 11-number constructors kept), and null. Flood-safe per door (`internal/NoRootAtTheDoor`: a 60 s window, ONE summary line at the next refusal after it, no timer thread, at most 1024 doors held apart); debug on = every refusal said; `engine.saidAtTheDoor()`. The README's ingress shape: the two lines plus the guard. |

### Numbers

- **Head 94e00c1**: the worktree from clean **595/0/0/3** (147 s, 17:33) and a git-archive clean copy of the head **595/0/0/3** (159 s,
  17:35), two short lock holds 17:33–17:38 (offline, no install); c17ffaa was 580/0/0/3.
- **+15 tests, all new**: PasswordFromTheEnvironmentOnlyTest 2 (switch-ledger), NoSecretFromTheBootstrapTest 1, NoRootAtTheDoorTest 6,
  NoRootAtTheDoorWindowTest 6 (sessionflow). SwitchLedgerMakerTest's pin moved to the new behaviour (one test replaced, same count).
- **Red first**: item 1 — a password only in the envelope's `secrets` connected ("Expecting code to raise a throwable"), and the moved
  pin: a written `password-ref` was echoed in the refusal; item 2 — a `Deployment`'s text carried the secret's value; item 5 — the old
  door: no line, the count 0, a throwing rule escaped the door.
- **19 mutants, 19 red**: item 1, 3 (the fallback put back, the variable not named, the ref repeated); item 2, 3 (the values said, the
  override taken away, the names dropped — this one SURVIVED first, because "AD_SWITCH_DB_PASSWORD" is also the sample's `password-ref`;
  the test now names `AD_REPORTS_DB_PASSWORD`, which nothing else in the sample names); item 5, 13 (the log, the count, the window gone /
  never closing / its boundary / its count not reset, one window for every door, no bound, rule one, a throwing rule escaping, the old
  door, the session id dropped, the summary never said).
- **The burst** (item 5): 1000 refusals from 8 threads at the real door → 1 line, the count 1000; on the window's own clock: 1000, then
  the window's end → 3 lines ("… and 999 more refused at esl 10.10.197.9:8021 in the 60 s window from 2026-10-24T11:20:00Z"), 1001; a
  second window → "… and 10 more", 1012.
- **Under an 8-thread CPU hog** (stopped by PID; load 12 → 22): NoRootAtTheDoorTest 20 runs, NoRootAtTheDoorWindowTest 10 runs, 0 red.

### Decisions

1. `SwitchDb.password(env, secrets)` **goes**: no caller left in the seed, none in ad-sphere p2-adflow 514d37a (read only), and no public
   signature needs it. `passwordFromTheEnvironment(env)` is the one road, for both logins.
2. A refusal names the variable and the login, never a value. **A `password-ref` that is not `env:<NAME>` is no longer repeated** in the
   refusal: until now a password written there by mistake reached the log.
3. `Deployment.secrets()` is deprecated, not for removal: the brief keeps the signature and lets the reader parse. **A `Deployment`'s
   text names the secrets only** (the record's own `toString` printed their values; nothing in the seed logs a `Deployment` today).
4. The door's line names the app as `flow.name()` (as every engine line does). The summary names its window's START, not "the last
   60 s": it is written at the door's next refusal, which may come much later. A failed rule's stack is logged with its full line (once
   per window per door; every time with debug on).
5. The log itself is proven: a test reads slf4j-simple's output for one call (System.err swapped; test classes run one at a time).

### Open points

- **The machine's own road**: a lane whose facts carry no root keeps the old road (the machine asks the rule at its start and refuses
  `NO_TENANT`). That refusal is noted in the session's history, not by the door's WARN line. Should the same sayer cover it? Your call.
- A door that sees no further refusal never writes its last summary (no timer thread, as asked). The total stays in `refusedNoRoot`,
  and the engine's stop line prints the stats.
- `RootResources` / `SwitchDb`'s own record text still prints a `password-ref` as written. Nothing in the seed prints it
  (`BootstrapRoad.describe` prints url and schema only); a written password there is refused at the root's start.
- prime-context still serves the envelope's `secrets`; the seed ignores them. Whether the envelope keeps carrying them is prime-context's.

### For P2

1. Every password the seed reads (switch-db's, and `ledger.db`'s once PC-0023 lands) must be in the box's environment file (the
   secreteer). P2c's G4 says the bed's env file already carries every variable the document names, so nothing changes there. A box
   whose password lived only in the envelope's `secrets` now refuses the root at its start, naming the variable.
2. ad-sphere's ingress keeps its two lines and its guard. The door now says and counts the no-root refusals, so ad-sphere's own DEBUG
   line for that case is a duplicate (keep it or drop it). `PoolStats.refusedNoRoot` is the number to watch.

## P1-L

The architect's brief (`docs/agents/P1-L-brief.md`), written after his review of P1-K's door: the no-root log must be injection-safe,
the machine's NO_TENANT road must be said too (P1-K's open point 1), and a written password-ref must never be printed. On this branch,
fast-forwarded by the architect to routesphere-flow ab91c44 (his merge of P1-K). Additive.

### Commits

| item | commit | what |
|---|---|---|
| 1 | b1ea8f8 | ONE cleaner, `api/LogText`. It escapes every control character (C0, DEL, C1, U+2028/2029: `\n` `\r` `\t`, the others `\uXXXX`). A value keeps at most 200 characters and a failure's text 1000, with "…" when cut, never inside an escape or a character. `cleaned(throwable)` keeps the stack and cleans every text in its chain. The door cleans its door (also the window's key), the session id, and a failed rule's words and stack. 40 more lines in 18 files go through it. |
| 2 | b5c7a44 | The machine's NO_TENANT start is said through the SAME sayer. The sayer now lives in the kit (`kit.noRootAtTheDoor()`), beside the counters, and the app is named per call. The rule's question (`askTheLanesRule`) moved into the flow, so the door and the start share it. |
| 3 | 46fb183 | No record prints a written password-ref. `SwitchDb.toString` and the ledger's words (its `ledger.db` block) show it as "<not an env: reference — hidden>". An `env:` reference is printed as it is. |
| 4 | 060b500 | Docs: the README's ingress section (the cleaner, the caps, the start road), the class table, the bootstrap section, the history line. |

### Numbers

- **Head 060b500**: the worktree from clean **615/0/0/3** (162 s, 18:36) and a git-archive clean copy of the head **615/0/0/3** (155 s,
  18:50), two short lock holds (18:36–18:39 and 18:50–18:52; PC-0023's and P2's holds between them), offline, no install;
  ab91c44 was 595/0/0/3.
- **20 new tests**: LogTextTest 8, NoForgedLogLinesTest 4, NoTenantAtTheStartTest 5, NoWrittenPasswordRefIsPrintedTest 3.
  NoRootAtTheDoorWindowTest (P1-K's) now names the app per call; its assertions are unchanged.
- **Red first**:
  - item 1: a session id with "\n[app] forged" gave two log lines at the door, and a failed rule's "\n" gave two as well;
  - item 2: the start's NO_TENANT refusal had no line, and the count stayed 0;
  - item 3: all three records printed the written refs.
- **25 mutants, 25 red**:
  - item 1, 13: the cleaning (LF, U+2028, C1); the cap (a long clean value passing whole, never cut, off by one, an escape cut in half); the door's session id, its ingress, a failed rule's text and stack left raw; a cleaned stack keeping the raw text; the step line's id raw.
  - item 2, 7: the start saying nothing, the start using a sayer of its own, the start counting twice, the door counting twice, the start's window kept per session, a throw not noted, the door's words used at the start.
  - item 3, 5: SwitchDb's override gone, the env: check inverted, the ledger's words printed raw, nested words not walked, the key missed.
- **No double count, proven**: an ingress that follows the two lines refuses at the door and launches nothing (launched 0, no context made), and the count is 1. A door refusal and a start refusal at the same door in one window give a count of 2 and one line.
- A whole session with debug on and a forged id wrote 14 lines. None starts with the forged text, and 10 carry the escaped id.

### The lines cleaned (item 1)

Each takes a session id or call id, a reference built from one, a lane's cause, or a ledger's or a failure's words. Line numbers are at 060b500.

- `seed-sessionflow` AdmissionChain: 124,253,254,294,295,322
- `seed-sessionflow` RoutesphereFlow: 685,828,834,844
- `seed-sessionflow` SessionCdr: 89,199,212,242,252
- `seed-sessionflow` SessionSettlement: 95,96
- `seed-sessionflow` SpanRotation: 66
- `seed-sessionflow` ChannelSlots: 72
- `seed-sessionflow` FanOutCdrSink: 39
- `seed-sessionflow` FileCdrJournal: 60
- `seed-sessionflow` KafkaCdrSink: 63,79,82
- `seed-sessionflow` NoRootAtTheDoor: 65,119,138,141,144
- `seed-sessionflow` OncePerSessionCdrSink: 45
- `seed-sessionflow` OrchestrixLedger: 124,193,194,220
- `seed-sessionflow` OwedJournal: 95,105,141
- `seed-sessionflow` PerRootCdrSink: 37
- `seed-sessionflow` ReturnRoad: 121,157,167,171,176,181
- `seed-switch-ledger` Credit: 51
- `seed-switch-ledger` MemLedgerPort: 87,134,140
- `seed-switch-ledger` OrphanReaper: 69

### Not cleaned, and why

- **seed-campaign, 5 lines**: `DefaultCampaignService:206` and `JdbcCampaignStore:260` (the task id); `QueuedCampaignStore:177` and `:431` (the change's text); `ChangeJournal:201` (a journal line). A session's task id and text come from the lane. But seed-campaign sits BELOW sessionflow, so it cannot reach `api.LogText`. To clean them, the helper must move to a module both can reach: a tiny module of its own, or seed-campaign itself. That is your call; I did not add a module or a copy.
- `TenantCreatedListener:74`: a platform message's key and error, from prime-context's own Kafka, not a request.
- statewalk's own lines (another library): with debug on, at INFO, a forged id reached none of them. Its DEBUG lines are statewalk's side.
- Every other seed line logs config or tree values only: tenants, tiers, partners, files, urls, counts.

### Decisions

1. `LogText` lives in `api`, not `internal`, so a lane can clean its own lines with it (ad-sphere's MAC).
2. There are two caps: 200 per value, and 1000 for a failure's text (exception texts are longer by nature). A failure's stack is kept as a cleaned chain (its own text and up to 8 causes); its suppressed exceptions are not carried.
3. The door's window is kept under its CLEANED name, so memory stays bounded even for a huge door value.
4. Debug lines are guarded (`isDebugEnabled`), so nothing is cleaned while debug is off. A clean value is given back as it is, with no copy.
5. A throwing rule at the start used to give one ERROR line per session (`safely`). It now gives the sayer's windowed WARN with its cleaned stack. The history note is unchanged.
6. Backslashes are not escaped. A value's own literal "\n" text reads like an escaped LF, but it can never make a second line.
7. The reader still passes the whole ledger map to `Ledger` (no data change). Only the printing hides the refs in it.

### Open points

- seed-campaign's 5 lines (above) wait for your ruling.
- URLs with userinfo or a password query (a redis url, a JDBC url) are printed as written: by the start line `BootstrapRoad.describe` and by the records. The bootstrap should never carry one. If you want, the seed could hide the userinfo part.
- Unicode format characters (the bidi controls U+202A–U+202E and U+2066–U+2069, zero-width characters) are not escaped. They cannot break a line, but they can reorder what a terminal shows.

### For P2

1. ad-sphere's own lines that log the MAC or the session id should use `LogText.clean`. Better still, ad-sphere's ingress should check the MAC's shape at the door (hex and colons). Then no control character ever reaches a session id.
2. `PoolStats.refusedNoRoot` now also counts the sessions the machine refused NO_TENANT at their start. A lane that follows the two lines sees no change.

## P1-M

The architect's brief (`docs/agents/P1-M-brief.md`), built from P1-L's open points. It covers three things: one cleaner that reaches
seed-campaign, no password inside a url printed, and invisible characters escaped. On this branch, fast-forwarded by the architect to
routesphere-flow cb236c9 (his merge of P1-L). Additive: `api.LogText` keeps its signatures.

### Commits

| item | commit | what |
|---|---|---|
| 1 | 8ecc312 | A NEW bottom module, `seed-logtext`: one class, `com.telcobright.seed.logtext.LogText`, with its tests and no dependency of its own. It is in the root pom right after the BOM, and in the BOM; seed-campaign and seed-sessionflow depend on it. The cleaner moved there whole, and `api.LogText` is a thin delegate. Eight seed-campaign lines are cleaned: the five from P1-L's list and three found on the way. |
| 2 | 5168f14 | No password inside a url is printed: ONE helper, `LogText.maskPassword`, used wherever the seed prints a url. The url used to connect is untouched. |
| 3 | f2e0c7d | The whole Unicode format category (Cf) is escaped. A character above U+FFFF is written as its two UTF-16 escapes. |
| 4 | 778531f | Docs: the root README (the new module), and the sessionflow README's ingress section, class table and history line. |

### Numbers

- **Head 778531f**: the worktree from clean **635/0/0/3** (264 s, 20:00) and a git-archive clean copy of the head **635/0/0/3** (231 s,
  20:04), two holds back to back 20:00–20:08 (offline, no install; the box at load ~15); cb236c9 was 615/0/0/3.
- **20 new tests**: MaskPasswordTest 6, InvisibleCharactersTest 5 (seed-logtext); NoPasswordInAUrlIsPrintedTest 4 and the api delegate's
  LogTextTest 1 (sessionflow); NoForgedCampaignLogLinesTest 2 and NoForgedStoreLogLinesTest 2 (seed-campaign). P1-L's 8 cleaner tests
  moved to seed-logtext unchanged.
- **Red first**:
  - item 1: a task id with "\n" forged a line in DefaultCampaignService's failed step, JdbcCampaignStore's cut and QueuedCampaignStore's refused change, and a journal line's U+2028 and ESC went raw;
  - item 2: a stub that returned the url as written failed 5 of the 6 per-form tests and all 4 site tests;
  - item 3: 4 of 5 failed (letters, marks of a script and emoji stay as written, by design).
- **26 mutants, 26 red**:
  - item 1, 6: the delegate answering raw, and five campaign lines left raw;
  - item 2, 15: the userinfo, the parameters, case, ';', exact names only; the start line's redis and switch-db urls; `where()` and its default; `origin()` masked (the connect road); RootResources, SwitchDb, the ledger's words, LedgerSettings, the orchestrix start line. One spec failed to match the file and was fixed before its run; it was then red.
  - item 3, 5: Cf passing, the fast path reading chars, a supplementary escape's width, a supplementary written as one escape, and "only the brief's named characters".

### The campaign lines cleaned (item 1)

- `DefaultCampaignService`: the failed store step (the task id, the store's words).
- `JdbcCampaignStore`: the text cut to its column (the task id).
- `QueuedCampaignStore`:
  - the lost change and the refused change (the change's text and the store's words, at the 1000 cap);
  - the failed batch and the stop's last failure (the store's words quote the task id);
  - the writer's own fault (its stack cleaned).
- `ChangeJournal`: the unreadable line.
- The last three of these have no test of their own (a full queue with an unwritable journal; a stop with changes left; a writer fault). Their calls are the same as the tested ones.

### Where the seed prints a url (item 2)

- The start line: `BootstrapRoad.describe` (every root's switch-db and redis url) and the bootstrap's `where()` (`HttpBootstrapSource.where()`, `BootstrapSource`'s default). The bootstrap-config source's name and `HttpBootstrapSource`'s four refusals take `where()`.
- The records' text: `SwitchDb`, `RootResources` (its own toString now), the ledger's words, `LedgerSettings`, and `Deployment` through them.
- The orchestrix ledger's start line, its retry line and its three refusals, and the return road's no-return-road line.
- Not passed through: the Kafka bootstraps (`host:port` lists, which have no password form) and the doorbell's `host:port`.

### Decisions

1. **The bottom module.** It is `seed-logtext`, class `com.telcobright.seed.logtext.LogText`. `api.LogText` delegates every method, `maskPassword` included. sessionflow and switch-ledger keep importing `api.LogText`, so no churn.
2. **A NEW artifact.** Whatever carries the seed's jars to a consumer (a private m2, the bed's base) must carry `seed-logtext-2.0.0-SNAPSHOT` too, because seed-campaign and seed-sessionflow depend on it.
3. **A change's text uses the 1000 cap.** The lost change's line may be its only record.
4. **`maskPassword` is broad on names.** It masks any parameter whose name CONTAINS password, passwd, pwd or secret (sslpassword, client_secret, keyStorePassword), not only the three exact names. A userinfo with no ':' is printed as written.
5. **The whole Cf category is escaped.** The reason: the JDK keeps it current; it holds the brief's list and also what hides text the same way (the TAG characters, the invisible operators, the interlinear annotations, U+180E). The cost: ZWNJ/ZWJ in Bengali or in an emoji sequence, and a soft hyphen, show as escapes in a log line.

### Open points

- A userinfo that is only a user may be a bare token (`https://<token>@host`). It is printed as written.
- Variation selectors (U+FE00–U+FE0F, U+E0100–U+E01EF; category Mn) are not escaped. They neither reorder a line nor break it, but they can carry hidden bits.
- statewalk's and mem-ledger's own log lines belong to other libraries and are outside the seed.

### For P2

1. A build of ad-sphere against this seed needs `seed-logtext` beside the other seed jars.
2. ad-sphere can mask its own printed urls with `api.LogText.maskPassword`, and clean its MAC lines with `api.LogText.clean`.
