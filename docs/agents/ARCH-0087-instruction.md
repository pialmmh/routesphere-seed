# ARCH-0087 — the session base moves to statewalk 3.3.0 (P1), and its consumers prove it

**To:** ONE local agent · repo `~/telcobright-projects/routesphere-seed`, worktree `worktrees/statewalk-33`, branch `statewalk-33`, cut from
`origin/seed-context` (4e1fca4 at launch) · **From:** the routesphere architect · **Date:** DRAFT 2026-10-16 16:4x +06 —
launches AFTER SS-0006 (one agent at a time).

**Why:** the owner, 2026-10-16: "fix the statewalk lib" → statewalk **3.3.0** released (tag v3.3.0, commit a6879ca; GitHub Packages +
`~/.m2/repository`): the SW-T1 fixes (a chained transition saved as the state it reached; a request-ending action never touches the NEXT
borrower of a pooled machine; MySQL first-save/quarantine deadlocks retried; `SessionContext` serializable; a restored timer fires at its
original deadline) + persistence by config (`PersistenceMode off | save | resume`) + SW-T2 (a restored child shares the session history
again: `Supervisor.onChildRestored`, `HistoryCarrier.adoptHistory`, both default no-ops). The lanes still pin 3.2.0; the WiFi architect
takes seed's NEW commit in its own lock step (wifi-sphere `base/base.lock`) after its B10 window.

## 0 · What the architect read (2026-10-16)

- `seed-sessionflow/pom.xml:24` `<statewalk.version>3.2.0</statewalk.version>` — the ONLY statewalk pin in the seed reactor (seed-bom's
  `statewalk-v2` is the in-repo v2 library, not this). 3.3.0 is in `~/.m2/repository/com/telcobright/statewalk/3.3.0/` (jar sha256
  d8f7efe38c51…) and on GitHub Packages (`docs/consuming.md` of statewalk names it).
- The API is ADDITIVE: no seed code must change. `SessionFlowSupervisor extends Supervisor`, `LevelBalanceTracker extends Machine`; neither
  overrides the new hooks; `SessionFlowEngine.buildRegistry` sets no persistence (OFF). With persistence OFF the only behaviour change that
  reaches an adopter is SW-T1's D2 (a TRANSITION ACTION that ends its own request) — seed has none: its requests end from an entry action
  (`TEARING_DOWN` → `complete()`) and stay handlers (`failNow`), whose SW-T1 cases were persistence-only (checked by the architect 2026-10-16).
- The consumers of seed-sessionflow: ad-sphere (`pom.xml:43`, `1.0-SNAPSHOT` from a private repository; its BED jar is built by
  `tools/cloud/cloud-base.sh` from PINNED commits — `SEED=4e1fca4…`, `STATEWALK=92e4dfa… (v3.2.0)` built from SOURCE — that script's pin
  move is round P2, ad-sphere's own, after this merge); routesphere `callflow-v3-voice` (`pom.xml:34`, `1.0-SNAPSHOT`, chained read-only to
  `~/.m2/session-base-repo` per CALL-0005); wifi-sphere (its `base/base.lock`, the WiFi architect's lock step). `~/.m2/session-base-repo` is
  SHARED and read-only to agents: this round installs into a FRESH private repository and proves the consumers against it; moving the
  shared one is the architect's step after the merge, announced.

## 1 · The items (one commit each; push `statewalk-33` after each)

| # | Item | The rule | The proof |
|---|---|---|---|
| 0 | **Baseline** | The WHOLE seed reactor offline at 3.2.0 (`mvn -o -Dmaven.repo.local=$HOME/.m2/seed33-repo -Dmaven.repo.local.tail=$HOME/.m2/arch-bed-repo,$HOME/.m2/repository test`); numbers per module in the notes | — |
| 1 | **The pin** | `seed-sessionflow/pom.xml`: `statewalk.version` 3.2.0 → **3.3.0**; the pom's comment (line ~20) names 3.3.0 and why (one sentence: the SW-T1/SW-T2 fixes; persistence stays OFF here, a lane turns it on by its own config key). The WHOLE reactor offline — a dependency of the api module builds everything, never the module alone | A proof test in seed-sessionflow, `StatewalkPinTest`: `PersistenceMode.parse("off") == OFF` and `StatemachineRegistry.builder(...).persistence(PersistenceMode.OFF, () -> { throw new AssertionError("a store must never be opened when persistence is off"); })` builds — the 3.3.0 API is on the classpath and OFF opens nothing; `SessionFlowEngine`'s own suite green unchanged. Mutant: the pin back to 3.2.0 → the proof test does not compile (red) |
| 2 | **The consumers, against the new seed build** | Install the reactor's artifacts into the FRESH private repository `~/.m2/seed-sw33-repo` (`mvn -o install -DskipTests -Dmaven.repo.local=$HOME/.m2/seed-sw33-repo -Dmaven.repo.local.tail=…`); then, from `git archive` copies (never a working tree): **ad-sphere** `main` (`git -C ~/telcobright-projects/ad-sphere rev-parse origin/main` — read it) — its Java suite with `-Dmaven.repo.local=<fresh2> -Dmaven.repo.local.tail=$HOME/.m2/seed-sw33-repo,$HOME/.m2/arch-bed-repo,$HOME/.m2/repository`; **routesphere `callflow-v3-voice`** (`origin/main`) the same way (its parent pom from the routesphere archive). Before (the baseline tails without seed-sw33-repo) and after; prove the resolved statewalk is 3.3.0 in both (a test-scope class-presence check `Class.forName("com.telcobright.statewalk.persistence.PersistenceMode")` from the archive's test run, or the offline `dependency:list` output) | The numbers before/after per consumer; any new red = STOP and report (an adopter's behaviour changed) — do not fix the consumer here |
| 3 | **Report** `docs/exchange/ARCH-0087-done.md` (the folder seed's reports live in) | The pin commit; the reactor's numbers; the consumers' numbers before/after with the resolved versions; the mutant; what the WiFi architect needs (seed's commit for its lock step); what P2 (ad-sphere's `cloud-base.sh` pins: `SEED=<this merge>`, `STATEWALK=a6879ca`) and P3 (pn-sphere 3.1.1 → 3.3.0) must do | — |

**Not this round:** any seed code change; ad-sphere's `cloud-base.sh` pins or its bed jar (P2 — the architect, with
`routesphere/tools/dev/ad-lane/cut-bed-jar.sh` and its base fingerprint); pn-sphere; wifi-sphere; `~/.m2/session-base-repo`; turning
persistence on anywhere.

## 2 · Rules that stand

Java 21 · Maven OFFLINE on private repositories as named (tails read-only; never write `~/.m2/repository`, `~/.m2/session-base-repo`,
`~/.m2/arch-bed-repo`) · nothing on a box, nothing outside 127.0.0.1 · the mutant's original kept at `~/seed33-work/mutants/`, the red
seen, restored · survive a stop: `~/seed33-work/notes.md` with a `State:` line after every item, the report drafted after item 1 · one
commit per item, every commit after its test run; push only `statewalk-33`; the merge is the architect's (`--no-ff` into `seed-context`) ·
absolute paths or `git -C`; never `cd` into the main checkout · trailer: `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` · no
devlog card · the end: the final message with the pin commit, the numbers, the resolved versions in both consumers, the mutant.
