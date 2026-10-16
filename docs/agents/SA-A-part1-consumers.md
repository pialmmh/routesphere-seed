# SA-A part 1 item 4 — the consumers of seed-sessionflow against the refund-policy build

The two consumers of seed-sessionflow, each from ONE read-only `git archive` of ONE commit (the architect's clarification of 2026-10-16:
the same copy for the BEFORE and the AFTER run), run offline twice, each run in a FRESH private head repository:

- **BEFORE** — tails `~/.m2/arch-bed-repo, ~/.m2/repository` (no `sa-a-repo`): seed-sessionflow resolves from `~/.m2/arch-bed-repo`.
- **AFTER** — tails `~/.m2/sa-a-repo` FIRST, then `~/.m2/arch-bed-repo, ~/.m2/repository`: every seed module resolves from `~/.m2/sa-a-repo`
  = this branch at **64d801c**, installed 23:18:09–23:18:19 (`-o install -DskipTests`, tail `~/.m2/repository` only) into a `sa-a-repo` that
  did not exist before: the 11 seed artifacts (parent, bom and nine modules), nothing else.

The resolved jar is proven by each run's own offline `dependency:build-classpath` (the path copied from its output file), not by inference.
Each copy's `target/` was removed before each run, and one checksum over every other file of the copy was taken before and after each run:
equal everywhere, so both runs of a consumer compiled and ran the same bytes. The commands: `~/sa-a-work/consumer.sh <voice|ad> <before|after>`.

| | seed-sessionflow jar | sha256 | `RefundPolicy` / `SettleOutcome` in it | its pom's statewalk |
|---|---|---|---|---|
| BEFORE | `~/.m2/arch-bed-repo/com/telcobright/seed-sessionflow/1.0-SNAPSHOT/seed-sessionflow-1.0-SNAPSHOT.jar` (Oct 16 18:59) | `9047488ece4300a4…` | no (`unzip -l`: 0) | 3.3.0 |
| AFTER | `~/.m2/sa-a-repo/com/telcobright/seed-sessionflow/1.0-SNAPSHOT/seed-sessionflow-1.0-SNAPSHOT.jar` (Oct 16 23:18) | `85e5df6eee697a33…` | yes (`RefundPolicy`, `RefundPolicy$Rule`, `SettleOutcome`) | 3.3.0 |

| consumer | the copy | BEFORE | AFTER | per class |
|---|---|---|---|---|
| routesphere `callflow-v3-voice` (+ the parent `pom.xml`) | `01520466c6505aa1a6854fdeb319db5e978b8f98` (origin/main); tar sha256 `40fc9752…`; tree `b1381ae0…` (32 files) | **99 / 0 / 0 / 0**, 10 classes (23:19:11–23:20:19; head `~/.m2/sa-a-voice-before-repo`) | **99 / 0 / 0 / 0**, 10 classes (23:20:25–23:21:18; head `~/.m2/sa-a-voice-after-repo`) | identical (`diff` empty) |
| ad-sphere, the Java suite | `c7cb15d3f94cbde1e7ec9d85688d258432f401cb` (origin/main, ARCH-0089); tar sha256 `f66daea3…`; tree `a749e1de…` (1127 files) | **585 / 0 / 0 / 17** = default-test 574/0/0/17 + jvm-in-utc 4 + pc-listens 2 + no-ffmpeg 5, 129 classes (23:21:33–23:28:03; head `~/.m2/sa-a-ad-before-repo`) | **585 / 0 / 0 / 17** = 574/0/0/17 + 4 + 2 + 5, 129 classes (23:28:22–23:34:39; head `~/.m2/sa-a-ad-after-repo`) | identical (`diff` empty) |

The 17 skipped in ad-sphere are the same 17 both times, its own gates: AdSphereSoakTest 1, GateScriptTest 10 (no jar in an archive),
TierAdRatingIndexTest 1, BillingCoreDdlCopyTest 1, BillingCoresWireCopyTest 1, SummaryServiceDdlCopyTest 1 (no `-Dbilling.ddl`/`-Dbilling.src`
passed, as in ARCH-0087), ChangelogTwoTest 1, SwitchSchemaScriptTest 1.

statewalk on both sides of both consumers: `~/.m2/arch-bed-repo/com/telcobright/statewalk/3.3.0/statewalk-3.3.0.jar`; ad-sphere's sphere-kit:
`~/.m2/arch-bed-repo/com/telcobright/sphere-kit/1.0.3/sphere-kit-1.0.3.jar` (both unchanged by this round). Both lanes override
`chargeAtSettle` whole (VoiceCallFlow.java:326 at 01520466, AdCallFlow.java:564 at c7cb15d), so the new hooks are never asked there: the
AFTER runs prove the base's api change breaks neither the compile nor any behaviour of a lane.

**New red after the change: none** — the STOP condition never fired. The four head repositories hold 0 files after their runs; nothing was
written to `~/.m2/repository`, `~/.m2/arch-bed-repo`, `~/.m2/session-base-repo` or `~/.m2/seed-sw33-repo` (0 files modified since 22:50).

**One finding on the "nothing outside 127.0.0.1" rule:** ad-sphere's own `ListenerTenantRoadsTest` (since 8986035, 2026-10-11, ARCH-0078
item 4) boots the service with `quarkus.http.host=0.0.0.0` in its test profile ON PURPOSE (two loopback addresses at one port) —
`Listening on: http://0.0.0.0:35143` (BEFORE) and `:34897` (AFTER), for the seconds that test runs; every other app of the suite listened on
127.0.0.1. It was there at ARCH-0087's commit too (3f9cb73). Not changed here — ad-sphere is read-only to this round.
