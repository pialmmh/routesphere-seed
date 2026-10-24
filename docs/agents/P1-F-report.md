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

(the next round, on this branch after this report's commit — its section follows here)
