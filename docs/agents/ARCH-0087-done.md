# ARCH-0087 — done: the session base on statewalk 3.3.0 (P1), the adopters proven

**From:** the ARCH-0087 agent (worktree `routesphere-seed/worktrees/statewalk-33`, branch `statewalk-33` off `origin/seed-context` 4e1fca4; the brief ed738b5) · **To:** the architect
**When:** 2026-10-16, 17:22 → 18:05 +06 (every time below is read from a log's `date` line or from `date` itself) · **Model:** Claude Fable 5.1

The owner's order, 2026-10-16: "fix the statewalk lib" → statewalk **3.3.0** (tag v3.3.0, a6879ca). This round moved the ONLY statewalk pin of the seed
reactor — `seed-sessionflow/pom.xml` `statewalk.version` — from 3.2.0 to 3.3.0, changed **no seed code**, kept persistence **OFF**, proved the pin by a
test that cannot compile at 3.2.0 (the mutant seen red), and ran the two consumers of seed-sessionflow — ad-sphere `main`, routesphere `callflow-v3-voice` —
from read-only `git archive` copies BEFORE and AFTER, against a FRESH private repository (`~/.m2/seed-sw33-repo`) holding this branch's build. Everything
offline, nothing on a box, nothing outside 127.0.0.1, no container; `~/.m2/repository`, `~/.m2/session-base-repo` and `~/.m2/arch-bed-repo` untouched
(read-only tails). The merge (`--no-ff` into `seed-context`) is yours; so is moving the shared `~/.m2/session-base-repo`.

## 1 · The commits on `statewalk-33` (oldest first; the branch head = the last line)

| item | commit | what |
|---|---|---|
| — | ed738b5 | the brief (yours) |
| 1 | **adc3ce0** `adc3ce088b273d2e82d631a3ea9e5f62672dcc70` | `seed-sessionflow/pom.xml`: `statewalk.version` 3.2.0 → **3.3.0**, the description sentence and an XML comment name 3.3.0 and why; `StatewalkPinTest` (api package, 2 tests) |
| 2 | **f2bc14a** `f2bc14a247ddd01299e14b9f6d2047e2ded66b7f` | `docs/agents/ARCH-0087-consumers.md` — the consumers' proof (the commands, the resolved jars, the numbers) |
| 3 | this commit — the branch head | this report |

**The pin commit = adc3ce0.** The WiFi architect's lock step and P2's `SEED=` take **the merge commit you make of it**, not adc3ce0 itself (your call: if you
merge `--no-ff`, the merge sha; the pinned tree is adc3ce0's either way).

## 2 · The reactor's numbers — run / failures / errors / skipped

Maven offline, head `~/.m2/seed33-repo`, **tail `~/.m2/repository` only** (your correction of 2026-10-16, see §5.2: the brief's `arch-bed-repo` tail shadowed the
H2-capable mem-ledger). The 3.2.0 column is the base's own state at 4e1fca4 (my proof test parked out of the tree); the 3.3.0 column is the tree of adc3ce0.

| module | 3.2.0 (item 0, `item0c-baseline-320-corrected.log`, 17:37:35–17:39:40) | 3.3.0 (item 1 run B, `item1-reactor-330-runB-corrected.log`, 17:39:46–17:41:51) |
|---|---|---|
| seed-bom | no tests | no tests |
| seed-config | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 |
| seed-config-client | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 |
| seed-context | 20 / 0 / 0 / 1 (KafkaDoorbellIT, gated) | 20 / 0 / 0 / 1 |
| seed-campaign | 67 / 0 / 0 / 0 | 67 / 0 / 0 / 0 |
| seed-routing | 33 / 0 / 0 / 2 | 33 / 0 / 0 / 2 |
| seed-sessionflow | 188 / 0 / 0 / 1 (KafkaCdrSinkIT, gated) | **190** / 0 / 0 / 1 — +2 = `StatewalkPinTest` |
| seed-tenant | 12 / 0 / 0 / 0 | 12 / 0 / 0 / 0 |
| seed-idgen | 19 / 0 / 0 / 0 | 19 / 0 / 0 / 0 |
| seed-switch-ledger | 49 / 0 / 0 / 0 | 49 / 0 / 0 / 0 |
| **reactor** | BUILD SUCCESS | BUILD SUCCESS |

Not one number moved but seed-sessionflow's +2. The resolved statewalk of the 3.3.0 runs is `~/.m2/repository/com/telcobright/statewalk/3.3.0/statewalk-3.3.0.jar`
(sha256 d8f7efe38c511c137596d2d8704ac9da50b5e7718a274b1ef74fa7ff51c6aa94, the one the brief names); `~/.m2/seed33-repo` held no file of its own (checked:
`find -type f` = 0 before the corrected runs — a chained tail is read through, not copied).

For the record, the runs on the brief's ORIGINAL tail (`arch-bed-repo,repository`) are in §5.

## 3 · The proof test and the mutant

`seed-sessionflow/src/test/java/com/telcobright/seed/sessionflow/api/StatewalkPinTest.java`:

1. `off_isTheWordAConfigKeyParses` — `PersistenceMode.parse("off")` is the same instance as `PersistenceMode.OFF`.
2. `off_buildsARegistryWithoutEverOpeningAStore` — `StatemachineRegistry.<Object>builder("statewalk-pin-proof").supervisor("proof", ProofSupervisor::new, 1)
   .threads(1).persistence(PersistenceMode.OFF, neverOpened).build()` with `neverOpened` a supplier that throws `AssertionError("a store must never be opened
   when persistence is off")`; the registry builds, names itself, holds no id, shuts down — the supplier was never asked. `ProofSupervisor extends
   Supervisor<Object>` with ONE state (`PROVING`, `.interim().timeoutStay(1, MINUTES)`); nothing of seed's own machines is touched.

`SessionFlowEngine.buildRegistry` still sets no persistence; `SessionFlowSupervisor` and `LevelBalanceTracker` override none of the new hooks
(`onChildRestored`, `adoptHistory`) — read, not changed.

**The mutant** (`item1-mutant-320.log`, 17:39:40–17:39:46): the ORIGINAL pom (`~/seed33-work/mutants/pom.xml.orig`, sha256 de8e8c7acdbec76d…, the 3.2.0 pin)
put back with the test in place, `-pl seed-sessionflow -am test-compile` → **BUILD FAILURE, COMPILATION ERROR**:
`StatewalkPinTest.java:[3,45] cannot find symbol … location: package com.telcobright.statewalk.persistence` (the import of `PersistenceMode`), then `[25,59]`,
`[25,20]`, `[36,26] cannot find symbol`. `PersistenceMode` does not exist in statewalk-3.2.0.jar (checked by `unzip -l`: the persistence package there holds
`PersistenceProvider`, `MachineSnapshot`, `SnapshotSerializer`, the in-memory/JDBC/Redis providers — no `PersistenceMode`). Then the 3.3.0 pom restored
(`cmp` with `mutants/pom.xml.330`: identical) → run B, the whole reactor, green (§2). The original stays on disk in `~/seed33-work/mutants/`.

## 4 · The consumers (item 2) — from `git archive`, never a working tree

Both consumers were taken as `git archive` of their `origin/main` (no fetch — the refs as this PC had them; the working trees never written):
**ad-sphere** `3f9cb73238ae4698201a5bea46c500e9c470144d` (its `pom.xml:43` `seed-sessionflow 1.0-SNAPSHOT`; 129 test classes), and **routesphere**
`11d81b2dc9d6e61b46ec4a2aedd4d98ee8f3a32d` — `callflow-v3-voice` + the parent `pom.xml` (its `pom.xml:34` `seed-sessionflow 1.0-SNAPSHOT`; 11 test classes),
into `~/seed33-work/consumers/`. Each consumer ran twice, offline, in a FRESH private repository per run, with the SAME tails per consumer (the brief's; your
correction left them as they were):

| run | head repository | tails (first wins) | the seed-sessionflow / statewalk it resolves |
|---|---|---|---|
| BEFORE | `~/.m2/seed33-voice-repo` / `~/.m2/seed33-ad-before-repo` | `arch-bed-repo, repository` | `~/.m2/arch-bed-repo/…/seed-sessionflow-1.0-SNAPSHOT.jar` (its pom: `statewalk.version` 3.2.0, built Oct 15 22:49) / `~/.m2/arch-bed-repo/…/statewalk-3.2.0.jar` |
| AFTER | `~/.m2/seed33-voice-after-repo` / `~/.m2/seed33-ad-after-repo` | **`seed-sw33-repo`**, `arch-bed-repo`, `repository` | `~/.m2/seed-sw33-repo/…/seed-sessionflow-1.0-SNAPSHOT.jar` (this branch at adc3ce0, installed 17:42:39–17:42:52 with `-DskipTests`, tail `repository` only; its pom: 3.3.0) / `~/.m2/repository/…/statewalk-3.3.0.jar` |

**How the resolved version is proven:** before each suite, the same offline `mvn … dependency:build-classpath -Dmdep.outputFile=…` on the archive's pom; the
jar PATHS above are copied from those files (`logs/voice-before-cp.txt`, `voice-after-cp.txt`, `adsphere-before-cp.txt`, `adsphere-after-cp.txt`). For the
voice AFTER the statewalk jar on that classpath was also opened: `unzip -l … | grep PersistenceMode` = 1 (the class 3.2.0 lacks). No test was added to a
consumer (their copies are read-only to this round).

| consumer | BEFORE (3.2.0) | AFTER (3.3.0) | new red |
|---|---|---|---|
| routesphere `callflow-v3-voice` (`mvn -o … test`) | **99 / 0 / 0 / 0** (17:33:08–17:34:19) | **99 / 0 / 0 / 0** (17:43:38–17:44:33) — the same ten classes, the same counts (V2SmokeMatrixTest 42, Call0004RulingsTest 17, V2RulingsTest 9, V2C12PerCauseTest 7, V3DeadlineRaceTest 6, V2TierOrderTest 5, V3DeadlineBoundaryRunsTest 4, VoiceCallRegistryTest 4, Call0003RulingsTest 3, VoiceRerouteTableTest 2) | none |
| ad-sphere, the Java suite (`mvn -o … test`: surefire's four executions — default-test, jvm-in-utc, pc-listens, no-ffmpeg) | **585 / 0 / 0 / 17** = default-test 574/0/0/17 + jvm-in-utc 4 + pc-listens 2 + no-ffmpeg 5 (17:42:43–17:48:13) | **585 / 0 / 0 / 17** = 574/0/0/17 + 4 + 2 + 5 (17:48:36–17:53:37) — **identical per class** (`diff` of the 129 class lines = empty); BUT its statewalk is still **3.2.0**, see §4.1 | none |

The 17 skipped in ad-sphere are the same 17 in both runs — their own gates (AdSphereSoakTest 1, GateScriptTest 10, TierAdRatingIndexTest 1, the two billing-core
and the summary-service DDL/wire copy tests 3, ChangelogTwoTest 1, SwitchSchemaScriptTest 1), not this round's. Every process of these runs bound 127.0.0.1
(ad-sphere's pc-listens JVM says so in its own log line) and ended with its run; `~/.m2/arch-bed-repo` and `~/.m2/repository` gained nothing (tails are read
through, not written: the heads are the only places a file landed, and `seed-sw33-repo` holds the 11 seed artifacts and nothing else).

### 4.1 · The finding: ad-sphere's statewalk stays 3.2.0 until P2 lifts it in ad-sphere's OWN resolution

The brief asked to prove the resolved statewalk is 3.3.0 in both consumers. For the voice it is (§4's table). **For ad-sphere it is NOT**, as its pom stands —
`dependency:tree -Dverbose -Dincludes=com.telcobright:statewalk` on the AFTER repository (`logs/adsphere-after-statewalk-tree.txt`):

```
com.telcobright:ad-sphere:jar:1.0.0
+- com.telcobright:sphere-kit:jar:1.0.3:compile
|  \- com.telcobright:statewalk:jar:3.2.0:compile
\- com.telcobright:seed-sessionflow:jar:1.0-SNAPSHOT:compile
   \- (com.telcobright:statewalk:jar:3.3.0:compile - omitted for conflict with 3.2.0)
```

`sphere-kit 1.0.3` (ad-sphere `pom.xml:37`, declared before `seed-sessionflow` at `:43`) pins `<statewalk.version>3.2.0</statewalk.version>`
(`sphere-kit-1.0.3.pom:19`, used at `:26–27`); both paths are depth 2, so Maven keeps the first declaration. ad-sphere's AFTER suite therefore ran
seed-sessionflow's 3.3.0-compiled classes on the **3.2.0 jar** — green because 3.3.0's API is additive and seed references none of its new symbols (that is
also why §2's numbers do not move) — but **the ad lane does not get the SW-T1 fixes from seed's pin alone.** Nothing was changed in ad-sphere (read-only
to this round, and the rule says do not fix the consumer). What P2 must add is in §6.

**Two diagnostics, so that P2 knows what it will get.** First I tried `-Dstatewalk.version=3.3.0` on the command line (no pom touched), believing Maven
interpolates a dependency's pom with the session's properties: it does NOT — the tree and the classpath stayed at 3.2.0 (`logs/adsphere-after2-statewalk-tree.txt`,
`adsphere-after2-cp.txt`); that run was stopped. Then the **DIAG copy**: `~/seed33-work/consumers/ad-sphere-DIAG/` = the archive minus `target/`, with EXACTLY ONE
added line in `<dependencyManagement>` — `<dependency><groupId>com.telcobright</groupId><artifactId>statewalk</artifactId><version>3.3.0</version></dependency>`
(the P2-shaped change; scratch-only, never committed, the archive itself untouched), a fresh head `~/.m2/seed33-ad-diag-repo`, the same AFTER tails
(`logs/item2-adsphere-DIAG-dm330.log`, tree `adsphere-DIAG-statewalk-tree.txt`, classpath `adsphere-DIAG-cp.txt`): **585 / 0 / 0 / 17** = 574/0/0/17 + 4 + 2 + 5 (17:58:09–18:04:11), the 129 classes **identical per class** to BEFORE and to the natural AFTER, with `~/.m2/repository/…/statewalk-3.3.0.jar` on the classpath (tree: sphere-kit's statewalk `version managed from 3.2.0` → 3.3.0; `unzip -l … | grep PersistenceMode` = 1). So ad-sphere `main` is green on the real 3.3.0 — P2 can move the kit/pin without fear of its Java suite.

The item-2 commit carries the same tables as `docs/agents/ARCH-0087-consumers.md`.

## 5 · Every red seen, in order

Every red of this round, oldest first; the standing rule for a red that happens once (alone five times, the count reported) was applied to the one that fits it.

| # | when | where | the red | what it was | state |
|---|---|---|---|---|---|
| 1 | 17:23:46–17:26:13, item 0 run 1 (ORIGINAL tail) | seed-sessionflow `OrchestrixLedgerTest.aReserveNeverWaitsLongerThanTheAdmissionHasLeft_evenWhenItsOwnTimeoutIsLonger` (`OrchestrixLedgerTest.java:220`) | expected the message to contain `200 ms the admission had left`, got `LedgerFault: the ledger is unreachable at http://127.0.0.1:33879: no connection within 500 ms` (`OrchestrixLedger.java:212`) | a timing race on 127.0.0.1 under the box's load (load avg ~5 on 8 cores; Kafka and several node processes of other sessions alive): the HTTP connect timeout (500 ms) fired before the 200 ms deadline the test gives. Pre-existing at 4e1fca4, not statewalk (OrchestrixLedger is plain HTTP) | **alone 5×: 5/5 green** (`item0-flaky-5x.log`, 17:27:18–17:27:55, 1.1–1.5 s each); green in every later whole-reactor run (4 of them). Not changed |
| 2 | 17:28:06–17:30:42, item 0 run 2 (ORIGINAL tail) | seed-switch-ledger: 49 run, **37 errors** in 7 classes (`CreditTest`, `WindowRenewalTest`, `ChainOnTheLedgerTest`, `MemLedgerPortTest`, `OrphanReaperTest`, `TestkitLedgerMatchesTheSwitchLedgerTest`, `TwoEntitiesTest`) | `IllegalStateException: MemLedger initialization failed` ← `Unsupported SQL engine 'H2': MemLedger speaks MySQL and PostgreSQL` at `MemLedgerBuilder.build(MemLedgerBuilder.java:283)` from `LedgerLab.start(LedgerLab.java:110)` | **the brief's tail order**, not the pin: `~/.m2/arch-bed-repo` holds mem-ledger 1.0.0-SNAPSHOT of the bed cut (Oct 15 22:48, sha256 851e15a07b6a…, MySQL/PostgreSQL only) and shadows `~/.m2/repository`'s (Oct 7 00:17, sha256 6921afcc3cdf…, still speaks H2); the switch ledger's `LedgerLab` is H2 in memory (`LedgerLab.java:50`). Identical 37 at 3.3.0 on that tail (run A). Proof of the resolution: `logs/switch-ledger-cp.txt` (`dependency:build-classpath`) | your correction: seed runs on tail `~/.m2/repository` only → **49/0/0/0 at both pins** (§2). Finding for a later round: **seed-switch-ledger's LedgerLab runs on H2; mem-ledger's bed build speaks MySQL and PostgreSQL only — a later round moves the lab to a real engine or pins an H2-capable mem-ledger explicitly; not this round's.** |
| 3 | 17:32:24–17:34:47, item 1 run A (ORIGINAL tail, 3.3.0) | MY new `StatewalkPinTest.off_buildsARegistryWithoutEverOpeningAStore` | `IllegalArgumentException: IDLE is reserved and auto-injected` (`StateMap.java:79`) | my one-state proof supervisor named its state `IDLE` — statewalk's own, injected into every map | the state renamed `PROVING` |
| 4 | between 17:34:47 and 17:37:35 (the test alone, corrected tail; that log has no date line — bounded by run A's end and the corrected sequence's start) | the same test | `IllegalStateException: State 'PROVING' is missing a mandatory timeout. Use .timeout(…) or .timeoutStay(…) on every state` (`StateMap.java:105`) | statewalk 3.x demands a timeout on every state | `.timeoutStay(1, MINUTES)` |
| 5 | the same window, the test alone (corrected tail) | the same test | `IllegalStateException: State 'PROVING' is missing a mandatory kind declaration. Call either .interim() or .finalState() on every state` (`StateMap.java:194`) | the same rule for the kind | `.interim()` → the test alone **2/0/0/0** (`item1-pintest-alone-330.log`), then run B's 190 (§2) |
| 6 | 17:39:40–17:39:46, the mutant | `StatewalkPinTest` at the 3.2.0 pin | `COMPILATION ERROR … [3,45] cannot find symbol, location: package com.telcobright.statewalk.persistence` (+ `[25,59]`, `[25,20]`, `[36,26]`) | **the red the brief asked for**: `PersistenceMode` exists only from 3.3.0 | the 3.3.0 pom restored, run B green |

Reds 3–5 are my own test's teething, each fixed in the test; they say something useful about 3.3.0's `StateMap` contract (`IDLE` reserved; a timeout and a
kind on every state) but touch no seed code. No test of seed's own, and no test of a consumer, went red at 3.3.0. **No new red in a consumer after the pin**
(§4) — the STOP condition never fired.

## 6 · What the next steps need

**The WiFi architect** (wifi-sphere `base/base.lock`, its own lock step after its B10 window): the seed commit to take is **the merge commit you make of
`statewalk-33`** (its tree = adc3ce0: the 3.3.0 pin + `StatewalkPinTest`; no other change). Until you merge, adc3ce0 on `origin/statewalk-33` is the sha
to read. The base's `SessionFlowEngine` sets no persistence; a lane that wants SAVE/RESUME passes its own `PersistenceMode` through its own config key —
nothing in seed changes for that.

**P2 — ad-sphere's `tools/cloud/cloud-base.sh` (ad-sphere's own round, after this merge):** today (origin/main 3f9cb73, lines 12–16) it pins
`STATEWALK=92e4dfa4973e7bbd02f59ca6cb160fbcb3fccfac` (tag v3.2.0, built from source) and `SEED=${SEED:-4e1fca4bc1d9089639a03c474b6d2b990fae5020}`. P2 moves
them to **`SEED=<the merge commit of statewalk-33 into seed-context>`** and **`STATEWALK=a6879ca…` (tag v3.3.0; the full sha from the statewalk repo)**, and
updates the two in-pom comments (`pom.xml:40`, `:44`) that name "the private repository's = routesphere-seed seed-context 4e1fca4". **And — the finding of
§4.1 — P2 must lift statewalk in ad-sphere's OWN resolution, or the bed jar keeps running 3.2.0 whatever seed pins:** either a sphere-kit built at
statewalk 3.3.0 (orchestrix-v2's `sphere-kit/pom.xml` `statewalk.version` → 3.3.0, a new kit version, and `cloud-base.sh`'s `KIT=` pin moved to that
commit; today `KIT=9d951180…` = sphere-kit 1.0.3 at 3.2.0), or ad-sphere declaring `com.telcobright:statewalk:3.3.0` itself (a direct dependency before
sphere-kit, or `dependencyManagement` — nearest wins; the second is the smaller change and the one `cloud-base.sh` needs least). Either way the
`STATEWALK=a6879ca` source build then produces the 3.3.0 artifact the tree asks for; with `STATEWALK` moved and sphere-kit still at 3.2.0 the offline bed
build would ask for a 3.2.0 that is no longer built — the two moves go together. ad-sphere's Java code needs no change: it ran AFTER on this build with the
numbers in §4 (and AFTER-2 with 3.3.0 actually on the classpath); its bed jar is then cut by `routesphere/tools/dev/ad-lane/cut-bed-jar.sh` on the
commit's pinned base (the "a bed jar is built on its pinned base" rule) with a fresh fingerprint. Not done here: the brief says P2 is not this round's.

**P3 — pn-sphere 3.1.1 → 3.3.0 (its own round):** pn-sphere's root `pom.xml:27` pins `<statewalk.version>3.1.1</statewalk.version>` (read-only look at
`~/telcobright-projects/pn-sphere`, HEAD f19fbb2; its description still says "the statewalk 3.1 supervisor"). P3 moves that one property to 3.3.0, rebuilds
`pn-machine` + `pn-app` offline against `~/.m2/repository`'s 3.3.0, and — because 3.1.1 → 3.3.0 crosses 3.2.0's and 3.3.0's `StateMap` contract (every
state needs a timeout AND `.interim()`/`.finalState()`, `IDLE` reserved — the three rules my own proof supervisor tripped on, §5 reds 3–5) — expects its
machines' `defineStates()` may need those declarations where 3.1.1 did not demand them. A proof test of the same shape as `StatewalkPinTest` fits there too.
Not touched here.

## 7 · What I could not run, and what is not this round's

- **Not run: the Kafka ITs** (`KafkaDoorbellIT`, `KafkaCdrSinkIT` — the 1 skipped in seed-context and seed-sessionflow; `AdCallOnPostgresAndKafkaIT` in
  ad-sphere) — gated behind `-Dseed.it=true` / their own flags, need a broker and a PostgreSQL; the brief says nothing on a box and no container, and this
  round changes nothing those tests cover.
- **Not run: ad-sphere's non-Java suites** (the BFF 743, the advertiser host 53, the walk 151 of Y-0045) — JavaScript/TypeScript, no statewalk in them; the
  brief names "its Java suite".
- **Not run on the brief's ORIGINAL seed tail for item 1's final numbers** — your correction moved the seed runs to `~/.m2/repository` only; the original-tail
  runs (3.2.0 run 2 and 3.3.0 run A) are kept in §5 red 2 for the record (identical 37 switch-ledger errors at both pins = the tail, not the pin).
- **Not done (not this round's):** any seed code change; P2's `cloud-base.sh` pins and the bed jar; P3 pn-sphere; wifi-sphere's `base/base.lock`; moving
  `~/.m2/session-base-repo` (yours, announced); turning persistence on anywhere; the devlog card; the merge.
- **A dependency that could not be resolved offline:** none — every seed and consumer run resolved on the named tails (`cp-exit=0` in each consumer log).
