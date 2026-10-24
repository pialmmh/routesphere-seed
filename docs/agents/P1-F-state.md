# P1-F · stream F — state

The six seed gaps the ad-sphere port (P2) found (P2-state.md, "Gaps for the seed"), fixed ADDITIVELY: new doors, every existing
signature kept, no behaviour P2 relies on changed. Branch `p1-follow` from routesphere-flow 723f160.

One line per item, newest last. Times are Dhaka (+06), each read with `date` before it was written.

- 2026-10-24 05:11 · START — worktree `p1-follow` at 723f160; the reactor there from clean **518/0/0/3** (offline, under the lock,
  129 s, 05:07–05:09). Order: item 1 (the build-time rule, docs) → 2 (the lane's sinks on a bootstrap kit) → 3 (the fan-out keeps the root)
  → 4 (machinesBuilt, busy) → 5 (PoolStats.sessions) → 6 (FakeKafka on kafka-clients 3.7.1 and 4.0.0). Running: item 1.
- 2026-10-24 05:17 · **item 1 done** — docs only, NO guard (no reliable, documented signal: SmallRye's ConfigSourceContext has no phase,
  the profile is prod at the build and the run; Quarkus's LaunchMode NORMAL = "a normal production build", the same word at run time and
  not visible to a plain SmallRye library; ExecutionMode.UNSET undocumented and UNSET in any plain JVM; the documented road is a Quarkus
  extension's RunTimeConfigBuilderBuildItem — a recommendation, not code). The rule in seed-bootstrap-config/README.md (the build's reading
  path, the two run-time locations — the box's config/application.yml in the unit's working directory, ordinal 265, or the unit's
  environment PRIME_CONTEXT_BOOTSTRAP_URL / PRIME_CONTEXT_TOKEN_ENV — never in the jar, nor in the build's own config/, .env or shell) and
  in the sessionflow README's bootstrap section; one javadoc bullet on BootstrapConfigSourceFactory; the ordinal table gains the box's file
  (265) and the jar's yml at 255. seed-bootstrap-config 8/0/0/0 (direct). Next: item 2.
- 2026-10-24 05:25 · **item 2 done** — `RoutesphereKit.toBuilder()`: a builder holding every part of the kit, so a kit made from the
  bootstrap takes the lane's own sinks before it is used (`fromBootstrap(source, app, makers).toBuilder().sdr(..).tasks(..).build()`);
  the trees, doors, ledger, CDR road, journal, clock, zone, settings and the road itself kept (the engine on the new kit starts the road
  and its close closes it); refused in words once an engine runs on the kit (`attachSessions` marks it). Red first (compile: no
  toBuilder); LaneSinksOnTheBootstrapTest 4/4 (every part the same object, a session's SDR in the lane's sink with its CDR on cdr_btcl
  and the engine's close closing the road's producer and listener, a kit by hand carried whole, a kit in use refused); 10 mutants, 10 red;
  seed-sessionflow 197/0/0/0 direct (193 + 4). Decision: ONE door (toBuilder) rather than fromBootstrap overloads with two sinks — it also
  takes P2's local CDR journal beside the root's road (P2's kitOnTheBootstrap in one chain). Next: item 3.
- 2026-10-24 05:33 · **item 3 done** — `FanOutCdrSink` (`CdrSinks.all`) overrides `publish(root, callId, tiers)` and hands the session's
  root to every sink (a root-less call stays root-less; one sink's failure still never stops the next, on both roads). Red first:
  CdrFanOutTest 3/3 red — among them a per-root Kafka sink behind the fan-out published NOTHING before (topic per root, no root given);
  green after; 5 mutants, 5 red; seed-sessionflow 200/0/0/0 direct. (One mutant run was cut by a shell timeout: the source was checked
  intact, the module recompiled, the five run again from the start.) Next: item 4.
- 2026-10-24 05:40 · item 1b (dc2a990; the time from git — first written as a guess, 05:47, later than the clock) — the run-time-only rule names the build's @QuarkusTests too (docs only).
- 2026-10-24 05:43 · **item 4 done** — counted where they happen. `machinesBuilt`: `PoolStats.machinesBuilt` is now the supervisors the
  registry's pool built (`poolStatistics(flow.name()).totalCreated()`, statewalk's own count — the base builds no machine);
  `FlowCounters.machinesBuilt` is kept so no reader breaks, @Deprecated(forRemoval) and never written, said in its javadoc (it has no
  place: nothing in the seed builds a machine). `busy`: a NEW session statewalk refuses because every machine is live
  (RejectedExecutionException "… refused: CAPACITY_EXCEEDED", internal/BusyAtTheDoor) is counted at the lane's channel's inbound (the
  registry holds internal/DoorChannel: the lane's channel, its gateway counted, everything else the lane's own) and at the NEW door
  `RoutesphereEngine.launch(id, firstFrame)`; `deliver` counts nothing — DECISION: P2 counts its own busy around deliver
  (AdDispatch.refusedAtTheDoor; AdCallStoryTest asserts busy == 1), so counting there would double it; P2 moves to launch() and drops
  its own increment. Red first: PoolCountersTest's two behaviour tests red on the old code (machinesBuilt 0 ≠ 2, busy 0 ≠ 1), the
  launch tests by compile; PoolCountersTest 4/4 + BusyAtTheDoorTest 3/3 green (3 runs); 16 mutants, 16 red; seed-sessionflow 207/0/0/0,
  samples 73/0/0/0 direct. Next: item 5.
- 2026-10-24 05:47 · **item 5 done** — `PoolStats.sessions` (the 11th number, last: `registry.activeIdCount()`, one per session id);
  `live` kept as it was (the registry's CELLS: two per session here, three with the budget child), both said in the javadoc; the ten-number
  constructor of 2.0 kept (ad-sphere's AdDispatch.stats() builds one), its `sessions` = `PoolStats.NOT_COUNTED` (-1). Red first
  (compile: no sessions()); PoolSessionsTest 2/2 (2 sessions = 4 cells, then 1; the ten numbers still build); 5 mutants, 5 red;
  seed-sessionflow 209/0/0/0 direct. The item-1b line above corrected to its git time (it was a guess). Next: item 6.
- 2026-10-24 05:50 · **item 6 done** (8f6bc3c) — `testkit.FakeKafka` builds its producer with `MockProducer(boolean, Partitioner,
  Serializer, Serializer)` (in 3.7.1 and 4.0.0; 4.0's javadoc: "Create a new mock producer with invented metadata the given autoComplete
  setting, partitioner and key\value serializers. Equivalent to MockProducer(Cluster.empty(), autoComplete, partitioner, keySerializer,
  valueSerializer)"), the partitioner null (an empty cluster: partition 0, no partitioner asked, in both); `MockConsumer(OffsetResetStrategy)`
  kept (deprecated in 4.0, present in both). Both jars are in ~/.m2 — proven OUTSIDE Maven on git-archive copies of the commits
  (scratch k4proof.sh: seed-sessionflow compiled with javac against one kafka-clients, its six FakeKafka test classes — 29 tests — run
  with one kafka-clients on the classpath):

  | code | compiled on | run on | result |
  |---|---|---|---|
  | 35081a6 (before) | 3.7.1 | 4.0.0 | **29 run, 19 failed**: NoSuchMethodError MockProducer.<init>(boolean, Serializer, Serializer) — P2's symptom |
  | 35081a6 (before) | 4.0.0 | — | **does not compile**: FakeKafka.java:47, the removed constructor |
  | 8f6bc3c | 3.7.1 | 3.7.1 | 29/0 |
  | 8f6bc3c | 3.7.1 | 4.0.0 | 29/0 (the seed's jar as ad-sphere runs it) |
  | 8f6bc3c | 4.0.0 | 4.0.0 | 29/0 |

  In the reactor: FakeKafkaOnKafka4Test reads FakeKafka's class file (red first: the removed descriptor was there), 1 mutant red;
  seed-sessionflow 210/0/0/0 direct. Not touched (named in the README): the seed's own KafkaCdrSinkPerRootTest, SessionsInTheAirTest and
  seed-tenant's DoorbellTest still build the three-argument MockProducer — they run on the seed's 3.7.1 only (left out of the 4.0
  compile above). Next: the whole reactor in the worktree, then a git-archive clean copy of the head (one lock hold).
- 2026-10-24 06:15 · **P1-F DONE** — the head 66ec564 from clean in the worktree **535/0/0/3** (153 s) and on a git-archive clean copy
  **535/0/0/3** (136 s), one lock hold 06:08–06:13 (offline, no install); base 723f160 518/0/0/3; +17 tests, all new, none touched or
  deleted; 37 mutants, 37 red. Report: `docs/agents/P1-F-report.md` — the P1-F report head is the commit that adds it. Next: P1-G (the
  architect's brief arrived 06:1x), on this branch, under a `## P1-G` heading below.

## P1-G

The architect's brief: `docs/agents/P1-G-brief.md` (a copy of ~/arch-drafts/launch/P1-G-seed-live-tenants-brief.md). On the same
branch after P1-F's report head 0d170db (the architect gates and merges that commit; P1-G goes on beside it). Items 1 → 7 in order.

- 2026-10-24 06:23 · START — from 0d170db (535/0/0/3 at its code head 66ec564). Read: PC-0022-done.md §4, §5, §9, §10 (framework-bootstrap
  8f8d922, by git show), P2-state.md gaps 7–10. Running: item 1 (a root not served yet is never half-served: pending + retried).
- 2026-10-24 06:38 · **P1-G item 1 done** — a root not served yet is never half-served. seed-tenant's `LiveTenantTrees.serve` undoes a
  failed serve (out of the directory again, the directory refreshed: no cell left to load it unserved; `LiveTrees.serve`'s javadoc says
  a lane's own trees undo the same). The road's `serveTheNewRoot` → `tryToServe(root)`: the bootstrap read again, the tree loaded, then
  the roads (made with the NEW deployment before it is switched), the doors, the topics; not yet → the root goes PENDING
  (`internal/PendingRoots`: one daemon thread `<app>-pending-roots` per road, made at the first pending root, stopped by the road's
  close) — said once (WARN, "not listed for <app> yet (its roots …)" or "prime-context does not serve its tree yet: <answer>"), tried every
  `config.routesphere.pending-root.retry-sec` (default 30), served → said once (INFO), given up after `pending-root.give-up-min`
  (default 120: "a restart, or a new tenant_created, serves it"); a later word tries at once (its identity leaves `seen`, as before).
  `api/PendingRootPace`, `Deployment.pendingRoot` (the 12-word constructor kept), `road.pendingRoots()` / `road.said()`. Red first:
  ServeOrNothingTest 2/2 red (link3 left in roots() with no tree; the next serve returned at once), PendingRootTest red on behaviour (the
  tree's refusal thrown out of the event; a not-listed root never tried again); green: ServeOrNothingTest 2, PendingRootTest 4; 11
  mutants, 11 red; direct: seed-sessionflow 214, seed-tenant 21, switch-ledger 61, samples 73 (all 0 failed). Test helper touched:
  BootstrapWorld (+ makers(Clock)). Next: item 2.
- 2026-10-24 06:45 · **P1-G item 2 done** — a root's tree never shows a tier its ledger was not handed. `spi/TreeGate` (the step a NEW tree
  takes before a session can see it) and `LiveTrees.gateEachNewTree(gate)` (a default that does nothing — a lane's own trees call the gate
  before their own swap); the road gives its gate at its open: `internal/TiersFirst` hands every tier of the new tree its ledger was not
  built over to that root's ledger FIRST (`tierAdded`), once — the event's road comes through the same `hand`, so the ring and the event
  together are ONE hand; the event still hands a tier the ledger was built over (its "nothing", the P1-E test kept unchanged); a ledger
  that throws keeps the new tree from being shown (the last stays, the next load tries again); a root with no ledger yet passes (its ledger
  is built over the tree it is made with: `builtOver`). seed-tenant: `TenantTreesBuilder.gate` — the loader passes every loaded tree
  through it before the cell swaps it in (the doorbell's ring, a reload, the backstop); `TreeGateHolder` (the road's gate, given after
  the trees are made); `LiveTenantTrees.gateEachNewTree`. DECISION: a ledger that SAYS its own failure (the switch ledger on the pinned
  mem-ledger, no addDatabase) lets the tree be shown, the tier's money refused until it loads — freezing the whole root's tree for one
  unloadable tier would stop every other change of that root. Red first: TiersBeforeTreesTest red on behaviour (a ring showed the tier,
  no hand; a refusing ledger did not keep the tree) — 4 green after; GateBeforeTheSwapTest by compile — 2 green; 11 mutants, 11 red;
  direct: seed-sessionflow 218, seed-tenant 23, switch-ledger 61, samples 73. Test helper touched: FakeLiveTrees (the gate on serve and
  reload). Next: item 3.
- 2026-10-24 06:48 · **P1-G item 3 done** — after a live tier's schema loads, `OwnedMemLedgerPort` merges the rows beside it — its own
  account at its PARENT (P2 gap 8) — by mem-ledger's `reloadNewEntities()` (on the pinned jar's API, a plain call; `internal/NewRowsOfATier`),
  under the same write gate; a reserve row the reload would bring back is a ghost (a settled reserve the write-behind has not deleted yet;
  STANDALONE: the port is the only writer) and is taken out at once — FOUND while reading the pinned bytecode (`mergeNewEntities` =
  putIfAbsent of a whole-table read): left in, the orphan reaper would give its money back twice. Cost (said in the class and the README):
  the pinned jar (2026-10-07) reads both tables of every tier schema WHOLE (no high-water mark — that is mem-ledger 0f04624, 2026-10-11,
  LATER than the pin: the brief's "by a high-water mark" holds from 0f04624 on, for the accounts; the reserve table stays whole). Only after
  the tier's schema loaded. Red first: ParentRowsOfANewTierTest's parent row red (null in the cache after tierAdded); 2 green; 4 mutants, 4
  red; switch-ledger 63/0/0/0 direct. Next: item 4.
- 2026-10-24 06:50 · **P1-G item 4 done** — prime-context's naming table copied BYTE-IDENTICAL (`git show 8f8d922:src/test/resources/
  bootstrap/naming-cases.tsv` → seed-naming `src/test/resources/bootstrap/naming-cases.tsv`, sha256 3dfb24f2414f593fd98b…fab, the brief's);
  `NamingCasesTest`: the table's sha256 pinned (a changed byte is red) and `TenantNames` held to all 23 cases (tierDb · parentOf · isTierOf,
  with the seed root btcl and without one) — **every case answered as prime-context's RootNaming: no red to report**. Red first (the table
  missing); 2 green; 4 mutants, 4 red (the seed's loose rule off, a leading zero allowed, the parent by the first underscore, one byte of
  the table changed); seed-naming 12/0/0/0 direct. Next: item 5.
- 2026-10-24 06:56 · **P1-G item 5 done** — `naming.seed-prefix` (the envelope's one addition, PC-0022 §9.1): the reader takes it beside
  `seed-root` (`Deployment.seedPrefix`, the 12-word constructor kept: null = the legacy `res_`); `TenantNames.withSeedRoot(seedRoot,
  seedPrefix)` + `seedPrefix()` (`withSeedRoot(seedRoot)` = `LEGACY_PREFIX`, unchanged); a seed root named without its prefix said once
  (INFO, once per process); a value that is not a name prefix (a lowercase letter, then lowercase letters, digits, underscores — the lab's
  prefix-as-a-tier `res_44` passes, as prime-context's RootNaming takes it) refused at the read in words naming the key. Red first (compile:
  no 2-word withSeedRoot / seedPrefix()); SeedPrefixTest 3, SeedPrefixFromTheBootstrapTest 3 green; 5 mutants, 5 red; direct: seed-naming
  15, seed-sessionflow 221, seed-tenant 23, switch-ledger 63, samples 73, bootstrap-config 8 (0 failed). Next: item 6 (the late config values).
- 2026-10-24 07:01 · **P1-G item 7 done (before item 6, whose Quarkus proof waits on the shared lock — P2's item12-check holds it; each item
  stands alone)** — the sessionflow README's bootstrap section, "Live tenants made safe": a `kind: root` message comes BEFORE the root is
  served (item 1's pending roots, its keys, its thread); a tree never shows a tier its ledger was not handed (item 2's gate; a lane's OWN
  LiveTrees must take it and call it before its swap — ad-sphere's AdTrees); the seed prefix (item 5) and the naming table (item 4); the
  deployment's tenant is the operator's, never `platform` (PC-0022 §9.7); the rules' ONE home `config.routesphere.identification.rules`
  (§9.5); `tenant_created`'s apps sorted, sent after the provisioning's commits; a root added live needs a listener the process already
  has (P2 gap 9, a window step); the pinned samples byte-identical — checked myself: deployment.json e1ca4f43a016ce4f, tenant-created.json
  63f396e263457824, both equal to prime-context 8f8d922's. Docs only. Next: item 6 (the probe waits for the lock).
- 2026-10-24 07:09 · **P1-G item 6 done** — WHY item 12's values come late, PROVEN on Quarkus 3.26.1 offline (a probe app, kept as
  `docs/agents/P1-G-quarkus-probe/`: a ConfigSourceFactory registered as ours is — META-INF/services, no @StaticInitSafe — serving
  probe.value; the jar says from-the-jar; @QuarkusTest 1/0 and the packaged jar, same answers): the factory is asked ONCE, at
  RUNTIME_INIT, never for the static-init config (ConfigGenerationBuildStep: the static builder takes only @StaticInitSafe factories; the
  run-time config is RuntimeConfigSetupBuildStep's first RUNTIME_INIT task, Config.createRunTimeConfig()). Seen: a plain @Startup bean and
  an @Observes StartupEvent reader → from-the-factory @RUNTIME_INIT; a @Startup bean a REST resource and a @ServerRequestFilter inject by
  its type → from-the-jar @STATIC_INIT (Quarkus REST builds them at static init), still from-the-jar at a later request; the same reached
  through Instance<> → from-the-factory @RUNTIME_INIT. DECISION: no code change — the factory stays not @StaticInitSafe (an HTTP fetch at
  a native image's BUILD, two asks per start); the rule and the proof in seed-bootstrap-config/README.md "When the values are visible",
  a pointer in the sessionflow README. For P2: reach AdSphereRuntime through Instance<> from its filters and resources (then configOver
  can go), or keep configOver. Ports used by the probe: 127.0.0.1:7795/7796, the jar stopped by PID. Next: the whole reactor + the clean
  copy, the report's P1-G section.
- 2026-10-24 07:14 · **P1-G DONE** — items 1–7; the head 0c26518 from clean in the worktree **557/0/0/3** (131 s) and on a git-archive clean
  copy **557/0/0/3** (128 s), one lock hold 07:09–07:13 (offline, no install); +22 tests, all new; 35 mutants, 35 red. The report's P1-G
  section: `docs/agents/P1-F-report.md`.

## P1-H

The coordinator's round (its message, 2026-10-24 ~07:16): a root's roads all-or-nothing (P1-G's open point 2, rated a money risk) and
two doc lines. On this branch from 9d97760 (the architect gates 9d97760).

- 2026-10-24 07:19 · START — from 9d97760 (557/0/0/3 at its code head 0c26518). Running: P1-H 1.
- 2026-10-24 07:36 · **P1-H item 1 done** — a root's roads are all-or-nothing. `serveItsRoads`: the ledger, the CDR road and the journal
  (each written down in `internal/RootRoadsMade` as opened), the topics, then the doors, the deployment LAST; any failure closes what
  THIS try opened — the ledger first (out of PerRootLedger, closed), then the CDR road (out, never closed: its producer is shared; a
  producer this try made for a Kafka no other root uses IS closed), then the journal —, takes the tree's serve back (`LiveTrees.unserve`,
  new default doing nothing; seed-tenant's trees and FakeLiveTrees take the root out) and returns the reason; the root goes pending.
  The guard: no second ledger for a root while one is open (`refuseASecondLedger`, said in an ERROR): a ledger whose close failed is
  kept as still open and closed first at the next try. The start too: a start refused half-way closes what it opened. Red first:
  RootsRoadsAllOrNothingTest red as the architect said — TWO open ledgers on the timer's road, the journal's exception thrown out of
  onTenantCreated on the event's road; 5 green after (the refused-start test written with the fix, its red shown by its mutant); 8
  mutants, 8 red; direct: seed-sessionflow 226, samples 73, seed-tenant 23, switch-ledger 63. Not covered by a test: the journal's undo
  after a later step (the topics, the doors) fails — no step after the journal fails in the fakes. Next: P1-H 2.
- 2026-10-24 07:36 · **P1-H item 2 done** — docs: the sessionflow README says a deployment must NOT set `pending-root` yet (prime-context's
  DeploymentShape refuses unknown keys; logged for a prime-context round; the defaults are the pace until then) and that a root's roads
  are all-or-nothing; ONE writer per root (STANDALONE) said where a second writer could be configured — the switch ledger's README (a
  second process on one root, or another ReplicationConfig.Mode: two write-behind consumers of the same money, the ghost guard blind),
  SwitchLedgerMaker's javadoc and its replicationMode line, and the sessionflow README (serve each root from ONE process). Next: P1-H 3.
- 2026-10-24 07:51 · **P1-H item 3 done** — the timing tests hold under load. Proof: an 8-thread CPU hog (scratch Hog.java, one busy thread per
  core, stopped by PID; load 6–13), each class 20 times, no recompiling between runs:

  | class | before (the old tests) | after |
  |---|---|---|
  | PendingRootTest | **16 of 20 runs red** — 20 of 80 tests: "Expecting empty but was: [link3]" (the gate's failure: served seen before pending cleared) and "Expected size: 1 but was: 0" (the give-up line not yet written when the root left pending) | **0 of 20** — 80 run, 0 failed |
  | OrchestrixLedgerTest | **1 of 20 runs red** — aTimeoutRetriesTheSameReferenceOnce: "timed out twice" (a quick answer later than the 300 ms read timeout) | **0 of 20** — 240 run, 0 failed |
  | DebounceGateTest (hardened too) | — | 0 of 10 under the hog, 40 run |

  Fixes: PendingRootTest waits on the WHOLE condition (served AND not pending), 30 s deadline, never a fixed sleep; PendingRoots says
  "given up" BEFORE the root leaves pending (a root no longer pending was said — the one product change). OrchestrixLedgerTest keeps
  three clocks apart: connect 30 s (a slow local accept never fires it), read 1 s (a quick answer stays under it), the slow answer 3 s
  (a late client timer still fires first); the repeats get 10 s of admission time; the "not its own 5 s" bound 4.8 s. Also hardened
  (cheap): RootsRoadsAllOrNothingTest and TenantCreatedLiveTest's waits 30 s; seed-config-client's DebounceGateTest (3 presence checks
  after fixed sleeps → waits for the fire) and DebounceGateCapTest (the trailing fire waited for). Listed, not changed (the report).
  Tests touched: PendingRootTest, OrchestrixLedgerTest (constants, one as() text, slowestAnswerMs 300 → READ_TIMEOUT_MS),
  RootsRoadsAllOrNothingTest, TenantCreatedLiveTest, DebounceGateTest, DebounceGateCapTest — assertions' meaning unchanged.
  seed-sessionflow 226/0/0/0, seed-config-client debounce 5/0 direct. Next: the whole reactor + the clean copy, the report's P1-H section.
