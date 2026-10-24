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
