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
