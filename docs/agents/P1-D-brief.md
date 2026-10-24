# P1-D — every lane states its rules; one supervisor class per lane; two records-road fixes (READY)

Written 2026-10-23 ~21:10 +06 by the architect. The owner's word of 2026-10-23: "go with recommendation" — decisions 3, 4, 9 and
10 of that day:
- **3** `delivered`, `refundPolicy`, `rerouteActionFor` and `cdrStatements` become abstract; a lane's facts must name its ingress.
- **4** a thin `AdSupervisor` / `CallSupervisor` / `SmsSupervisor` / `WifiSupervisor` class per lane, with no logic inside.
- **9** a parked records writer's memory is capped; past the cap the records wait on disk; nothing is lost.
- **10** the Kafka road's leftovers carry their root (one line, its own commit).

Why (the owner, 2026-10-23): "force the derived routesphere flows (ad, call, sms …) to override these methods" and "each flow shall
implement its own supervisor (CallSupervisor, AdSupervisor, SmsSupervisor) … with as much code re-use as possible". Today a new lane
can silently inherit the CALL's rules for delivery, refund and failover — an SMS or ad lane that forgets them is billed like a call.

## 0 · Where things are

- routesphere-seed branch `routesphere-flow` **fb33822**: P1 + P1-R (the records road) + the move to statewalk **4.1.0** (released
  locally, in `~/.m2`). Clean copy 461/0/0/3. Branch **`p1-rules`** from it, worktree `routesphere-seed/worktrees/p1-rules`.
- The base: `seed-sessionflow/src/main/java/com/telcobright/seed/sessionflow/api/RoutesphereFlow.java` (its class javadoc's table
  names the owner's steps (a)–(g)), `RoutesphereEngine.java`, `RoutesphereFacts.java`.
- Six classes extend the base: the samples `AdFlow`, `VoiceFlow`, `SmsFlow`, `WifiFlow` (`seed-sessionflow-samples/src/test/…/samples/`),
  `LabFlow` (`seed-sessionflow/src/test/…/base/`), `LedgerFlow` (`seed-switch-ledger/src/test/…/samples/`). Engines are built at
  `Lab.java:80`, `OneTablePerLaneTest:52`, `OneRecoveryRoadTest:40`, `CreditControlTest:261`, `FlowBed:49`.
- Signaling specs that send `SessionStart`: `AdPlayback`, `EslCall`, `SmsSubmit`, `WifiGateway` (samples), `LabCall` (base tests).

## 1 · Every lane states its rules (decision 3)

- Abstract: `delivered(C, Outcome)`, `refundPolicy(C)`, `rerouteActionFor(String cause)`, `cdrStatements(C, CdrEvent)`. Each
  javadoc says what every lane must decide and gives the call's answer as the example (delivered: the call — answered, the SMS —
  its delivery report, the ad — the view shown; refund: the call — `RefundPolicy.callsRule()`; reroute: a lane with one hop and no
  retry answers `RerouteAction.FAIL_TERMINAL` for every cause).
- The old `cdrStatements` body becomes a final helper a lane calls by name:
  `protected final List<RecordStatement> cdrTableRowOf(CdrEvent record)` = `CdrTableSql.statementsOf(record, storeOf(record))`
  (billing-core's `cdr` row in the tier's own schema: the probe of its key, then the INSERT). The seed's lanes answer it.
- The lanes say their rules explicitly: VoiceFlow, LabFlow, WifiFlow, LedgerFlow → `ctx.answered()` and `RefundPolicy.callsRule()`;
  AdFlow → FAIL_TERMINAL for every cause (never a second creative after the hand-over); WifiFlow and LedgerFlow → FAIL_TERMINAL.
  Behaviour must not change: every existing test stays green unchanged except where a test built a flow anonymously and now must
  answer the new abstract methods (say which).
- Unchanged on purpose (the owner's code-reuse goal): the money code (`reserveBalance`, `finalizeTier`, `settleOutcome`'s default,
  `identifyPartner`) stays shared with its defaults; `iterateResellerTenants` and `finishSession` stay final; the 13 statewalk
  calls stay final.
- A drift guard (like statewalk's `GuideDriftTest`): by reflection, the abstract methods of `RoutesphereFlow` are exactly
  {name, serviceGroup, identifyEntryPartner, routing, rateAtLevel, delivered, refundPolicy, rerouteActionFor, cdrStatements}, and
  the statewalk calls + `iterateResellerTenants` + `finishSession` are final. The class javadoc's table marks the forced ones.

## 2 · A lane's facts must name its ingress — refused at build (decision 3, second half)

statewalk's `SessionStart` carries any `SessionFacts`, so the compiler cannot force a lane's facts to be `RoutesphereFacts`. The
base makes it a BUILD error instead, plus a guard at run time:
- `identifySiblingLevelTenants(RoutesphereFacts facts)` (was `SessionFacts`). `onStart`: facts that are not `RoutesphereFacts` →
  refused `NO_TENANT`, with a history line that names the class. Keep `onStart` ≤ ~10 lines of named steps.
- `RoutesphereEngine.Builder.start()` refuses a signaling spec that shows no `SessionStart` sample whose facts are
  `RoutesphereFacts` — statewalk 4.1's `MachineSpec.Builder.upSample(…)` / `MachineSpec.upSamples()`. In words, e.g.
  "[AdSupervisor] the signaling machine 'AdPlayback' shows no SessionStart sample — add .upSample(new SessionStart(<the lane's
  RoutesphereFacts>)): the base finds the session's root by its facts' ingress", and "… its SessionStart sample carries <class>,
  not RoutesphereFacts: every session would be refused NO_TENANT".
- Every signaling spec in the seed adds its sample. A gain for free: with persistence on, statewalk 4.1's build check round-trips
  the sample, so a lane whose facts cannot be read back fails at build.
- Tests (red first): no sample → refused at start, in words; a sample with other facts → refused; facts that are not
  `RoutesphereFacts` at run time → NO_TENANT with the class named; every sample lane starts.

## 3 · One supervisor class per lane, no logic (decision 4)

statewalk's `SessionSupervisor` stays final (the 2026-10-05 ruling: the states and the money contract are the same in every lane).
The seed adds the named class per lane:
- `api/RoutesphereSupervisor<C extends RoutesphereContext>` (abstract): the lane's flow and its contexts supplier; `name()` =
  `flow.name()` (ONE name: statewalk's table and the log lines); `flow()`; `spec()` = `SessionSupervisor.spec(name(), contexts, flow)`.
  No hook, no state, no logic.
- `RoutesphereEngine.of(RoutesphereSupervisor<C>)` becomes the ONE door; `of(flow, contexts)` goes, so no lane can skip its class.
- The samples get `AdSupervisor`, `CallSupervisor`, `SmsSupervisor`, `WifiSupervisor`; the base tests get `LabSupervisor`. Each
  `public final class XSupervisor extends RoutesphereSupervisor<XContext>`, with a constructor `(XFlow flow)` (`XContext::new`) and
  one `(XFlow flow, Supplier<XContext> contexts)` for a bed that keeps its contexts. A test asserts each sample class's simple name
  = its flow's `name()`.
- `FlowBed` takes the lane's supervisor as a function of the contexts (`c -> new AdSupervisor(flow, c)`), so every scene runs the
  lane's own class. The engine's class javadoc example follows.

## 4 · A parked writer's memory is capped (decision 9)

Today (P1-R report §9 item 13) a parked writer keeps its whole queue in memory, unbounded — durable meanwhile only through the
journal of the sessions in the air (a STAND-IN record: LOST_AT_RESTART, charged as reserved) and the park sink (CDRs only).
- A cap in `RecordStores` (its builder): `parkedQueueCap`, sessions, default 10,000; ≤ 0 refused.
- Parked and at the cap: a session handed in goes to the writer's OVERFLOW on disk, not to memory. Said once per park (WARN with
  the root, the cap and the place), the count said again at the unpark.
- **Nothing is lost and the money does not change**: when the store takes batches again the writer drains its queue, then writes
  the overflow back in the order it came, through the same batch road (the once rule makes a record already written a no-op), each
  session's journal line marked done after its commit. The overflow holds the session's REAL records — every tier record's
  statements and summary entry and its SDR rows — never the journal's stand-in (the stand-in would charge "as reserved" instead of
  the real charge).
- The overflow is one file per root (JSON lines) in a directory the builder names — default: beside the journal of the sessions in
  the air; neither = refused at build, in words. A statement's parameters must read back with their types (String, Integer, Long,
  BigDecimal, LocalDateTime, Boolean, null): test each. A half line (a power cut) is skipped with one WARN, as the journal does.
- A process death while overflowed behaves as today (P1-R §9 item 12: the next start publishes the journal's stand-ins). Say in the
  report what an operator does with the overflow file then; if the start could cheaply write the overflow before the stand-ins,
  propose it — do not build it.
- Tests (red first): cap 3, the store down, 10 sessions → memory ≤ 3, overflow 7; the store back → all 10 written exactly once, in
  order, the money per tier = the real records, the journal lines done after their commits. Mutants: the overflow never read back;
  read back before the queue (order); the cap ignored (memory) — each red.

## 5 · The Kafka road's leftovers carry their root (decision 10)

`SessionCdr.java:176` publishes a leftover with `flow.kit().cdr().publish(callId, tiers)` — no root — and the per-root Kafka sink
refuses that ("names no root … NOT published", `KafkaCdrSink.java:61-63`): a dead process's leftovers never reach `cdr_<root>`.
Make it `publish(CdrAssembler.rootOf(tiers.get(0)), callId, tiers)`, as line 56 does for a live session. Its own commit; red first:
a leftover on `CdrSinks.kafkaPerRoot` (a `MockProducer`) reaches `cdr_<root>`.

## 6 · A doc gap (the architect's P1-R review)

`sdrStatements`' javadoc: write once by your own key (a probe, or `ON CONFLICT DO NOTHING`); a plain INSERT that meets a duplicate
rolls back the whole batch and parks the writer — and a session ended a second time (a restore from an older row) runs them again.

## 7 · Rules, tests, the proof

- EVERY mvn runs under the one Maven lock: `flock /home/mustafa/telcobright-projects/.one-maven.lock mvn -o -q …` (another agent
  builds on this PC). The normal `~/.m2` resolves everything offline (statewalk 4.1.0 is there); never `install` into `~/.m2`; if a
  private repository is ever needed, make it ONLY with `~/arch-drafts/tools/private-m2.sh <dir>`.
- Never push; no sudo, no systemctl; never `pkill -f`; never cd into another agent's worktree. A road that cannot reach its
  service is reported, not fixed.
- Commit per item (`P1-D <n>: …`). After EVERY item update `docs/agents/P1-D-state.md`; draft `docs/agents/P1-D-report.md` early
  (per item: the commit, the red seen, the mutants, the numbers as run/failed/errors/skipped).
- Each item: the red seen first, then green; mutants on the new guards (the abstract set, the engine's sample check, the cap road,
  a leftover's root), each seen red and restored with a fresh mtime (copyfile, not copy2) or `mvn clean` before a measured run.
- The whole reactor in the worktree, then on a git-archive clean copy of your head (offline, the normal `~/.m2`, no install).
- Top-level functions read as ≤ ~10 lines of named steps (the house style).

## 8 · Out of scope

- The MySQL span key (decision 8: a rotated tier's spans share one `UniqueBillId`) — its own round with billing-core, before voice moves.
- statewalk (its 4.2 round runs beside you), ad-sphere (P2 starts on your merged head), every other repository.
- No push, no `~/.m2` install.
