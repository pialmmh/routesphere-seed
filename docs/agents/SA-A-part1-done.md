# SA-A part 1 — done: the refund rule in the shared base (session-accounting round A, D1)

**From:** the SA-A part-1 agent (worktree `routesphere-seed/worktrees/refund-policy`, branch `refund-policy` off `origin/seed-context` fbf705b; the brief 380caea)
· **To:** the architect · **When:** 2026-10-16, 22:50 → 23:36 +06 (every time below is read from a log's `date` line or from `date` itself) · **Model:** Claude Opus 5.5

The owner's word (2026-10-16): ONE session-accounting library; "refund is optional and configured through config". This round put the refund
rule into the base ONCE — `RefundPolicy` + `SettleOutcome` and two hooks read at settle time behind the default `chargeAtSettle` — turned the
base's own sample `AdFlow` onto the hooks as the equivalence proof, named D3's proof with two mutants, and ran both consumers of
seed-sessionflow from one `git archive` each, BEFORE and AFTER, against a fresh private repository holding this branch. `SessionFlowSettings`
is untouched; the default behaviour is today's; no lane was changed; refund is turned on nowhere. Everything offline, nothing on a box,
nothing outside 127.0.0.1 but one bind of ad-sphere's OWN suite (`ListenerTenantRoadsTest`, 0.0.0.0 by design, §7); `~/.m2/repository`, `~/.m2/arch-bed-repo`, `~/.m2/session-base-repo`, `~/.m2/seed-sw33-repo` untouched (read-only
tails). The merge (`--no-ff` into `seed-context`) is yours.

## 1 · The commits on `refund-policy` (oldest first; the branch head = the last line)

| item | commit | what |
|---|---|---|
| — | 380caea | the brief (yours) |
| 0 | — (nothing to commit) | the baseline: the whole reactor offline, §2 |
| 1 | **0ab7169** `0ab716913756ba29a5ba4f009b0194f80fbe1e6e` | D1: `dependencies/RefundPolicy` (+ `Rule`, `parse`), `api/SettleOutcome`, the two hooks and the policy-driven default `chargeAtSettle` in `api/SessionFlowSteps`; `dependencies/RefundPolicyTest` (20 tests) |
| 2 | **64d801c** `64d801c56b6e0609249d49ddabea4a3aeb9903a2` | the sample `AdFlow` answers the two hooks instead of overriding `chargeAtSettle` |
| 3 | — (nothing to add) | D3 named: `MemLedgerPortTest:82` already asserts the live balance after the repeat; two mutants red (§4) |
| 4 | **cc8e847** `cc8e847547edc78eefaf67ce38f3ab144e8de35a` | `docs/agents/SA-A-part1-consumers.md` — the consumers' proof (§5) |
| 5 | this commit — the branch head | this report |

**The code commit = 64d801c** (items 4–5 add documents only). Part 2's `SEED=` takes **the merge commit you make of `refund-policy`**.

## 2 · The reactor's numbers — run / failures / errors / skipped

Maven offline, head `~/.m2/sa-a-repo`, tail `~/.m2/repository` ONLY, `-Dmaven.test.failure.ignore=true` so every module reports; the box was
shared with your ad89 review build (load 3–8 on 8 cores).

| module | BEFORE: item 0, 380caea (`item0-baseline.log`, 22:53:57–22:56:51) | item 1, 0ab7169's tree (`item1-reactor.log`, 23:08:19–23:10:30) | AFTER: item 2, 64d801c's tree (`item2-reactor.log`, 23:13:18–23:15:28) |
|---|---|---|---|
| seed-bom | no tests | no tests | no tests |
| seed-config | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 |
| seed-config-client | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 |
| seed-context | 20 / 0 / 0 / 1 (KafkaDoorbellIT, gated) | 20 / 0 / 0 / 1 | 20 / 0 / 0 / 1 |
| seed-campaign | 67 / 0 / 0 / 0 | 67 / 0 / 0 / 0 | 67 / 0 / 0 / 0 |
| seed-routing | 33 / 0 / 0 / 2 | 33 / 0 / 0 / 2 | 33 / 0 / 0 / 2 |
| seed-sessionflow | 190 / 0 / 0 / 1 (KafkaCdrSinkIT, gated) | **210** / 0 / 0 / 1 | **210** / 0 / 0 / 1 |
| seed-tenant | 12 / 0 / 0 / 0 | 12 / 0 / 0 / 0 | 12 / 0 / 0 / 0 |
| seed-idgen | 19 / 0 / 0 / 0 | 19 / 0 / 0 / 0 | 19 / 0 / 0 / 0 |
| seed-switch-ledger | 49 / 0 / 0 / 0 | 49 / 0 / 0 / 0 | 49 / 0 / 0 / 0 |
| **reactor** | **400 / 0 / 0 / 4**, BUILD SUCCESS | 420 / 0 / 0 / 4, BUILD SUCCESS | **420 / 0 / 0 / 4**, BUILD SUCCESS |

Item 0 = ARCH-0087's 3.3.0 column exactly. **Per class** (the surefire line of every class, `logs/*.perclass`): item 1 differs from item 0 by
ONE line — the new `RefundPolicyTest 20/0/0/0`; item 2 is identical to item 1 in all 63 classes. No existing suite moved by one test.
`~/.m2/sa-a-repo` held 0 files through items 0–3 (a tail is read through, not copied) and the 11 seed artifacts after item 4's install.

## 3 · The design as built (brief §1), and the choices inside it

```
dependencies/RefundPolicy.java   public record RefundPolicy(Rule onFailure, Rule unshown)
    public enum Rule { CHARGE, REFUND;  public static Rule parse(String key, String word) }
        null → "", trim, toLowerCase(Locale.ROOT); "charge" | "refund"; else
        IllegalStateException(key + " must be 'charge' or 'refund', not '" + word + "'")   ← AdSphereSettings.java:43-46 at c7cb15d, word for word
    compact constructor: a null rule → IllegalArgumentException in words (so of() AND new refuse it)
    public static RefundPolicy callsRule()   = (REFUND, CHARGE), one constant
    public static RefundPolicy of(Rule, Rule)
    public boolean pays(SettleOutcome)       CONSUMED → true · OFFERED_NOT_CONSUMED → unshown == CHARGE · FAILED → onFailure == CHARGE
api/SettleOutcome.java           enum { CONSUMED, OFFERED_NOT_CONSUMED ("The ad: handed to the phone, never shown."), FAILED }
api/SessionFlowSteps.java
    protected BigDecimal chargeAtSettle(C ctx, LevelAdmission level) {
        boolean pays = policyAtSettle(ctx).pays(outcomeAtSettle(ctx));
        return pays ? TierSettlement.reservedOf(level) : BigDecimal.ZERO;
    }
    protected SettleOutcome settleOutcome(C ctx) { return callsOutcome(ctx); }             // answered ? CONSUMED : FAILED
    protected RefundPolicy  refundPolicy(C ctx)  { return RefundPolicy.callsRule(); }
    private policyAtSettle / outcomeAtSettle: a null → the default; private callsOutcome (non-virtual: the null fallback is the base's
    default even when a lane overrides settleOutcome)
```

- **Today's default, byte for byte:** (REFUND, CHARGE) with answered → CONSUMED, else FAILED returns `TierSettlement.reservedOf(level)` when
  answered and `BigDecimal.ZERO` when not — the same objects on the same condition as the old one-liner. Mutant M2 (the old one-liner put
  back) leaves the default-hooks test GREEN and every existing suite unchanged; only the tests of the NEW behaviour go red.
- **The access modifiers:** brief §1 writes `parse`, `callsRule`, `of`, `pays` without one; they are `public` — the sample `AdFlow` (package
  `samples`) and the ad lane (another repository, part 2) call them.
- **A null falls back; a throw does not.** A lane's hook that returns null settles by the default (never OWED — the brief's rule); a hook that
  THROWS is not swallowed: it is the OWED path, exactly as a throwing `chargeAtSettle` override is today (the javadoc says so). Swallowing a
  throw would settle real money by a rule the lane did not choose; OWED keeps the reserve open and names it in one ERROR line.
- **The javadoc** of `chargeAtSettle` names the two kinds of lane (priced by duration or rate — the voice call, the WiFi session — override it
  whole and the hooks are never asked; priced by the reserve — the ad now, the SMS later — answer the hooks); each hook says "null = the
  default" and "asked only by the default `chargeAtSettle`".
- **The hooks are asked once per TIER** (the base settles tier by tier, as the old override was), at settle time. A lane whose config can
  reload between two tiers' settlements could settle them under two policies — the same property today's ad override has (it reads
  `props.get()` per tier); not a regression. A lane that wants one decision per session can fix it on its context and answer from there.
- `SessionFlowSettings` untouched; no new dependency; `dependencies → api` already existed (`SessionFlowSettings`, `TenantResolvers`).

**The tests** — `seed-sessionflow/src/test/java/com/telcobright/seed/sessionflow/dependencies/RefundPolicyTest.java` (20):
the `pays` table (4 policies × 3 outcomes, one parameterized row per policy); `callsRule()` = (REFUND, CHARGE); `of` refuses each null with
its sentence; `parse` — `charge`/`refund` in any case with spaces and tabs around (9 rows), and the refusal sentence for `free`, `""`, `"   "`,
`null` with the key `adsphere.billing.unshown` (literal sentences, not rebuilt), plus another key to show the key is the lane's; and four
tests through the REAL settlement on the samples' `Scene` (`preprocess` → LIVE `admission` — both tiers reserve, 0.50 at res_44, 0.40 at btcl —
→ answered or not → `settle`), reading the ledger: a lane on the DEFAULT hooks pays the reserve when answered and 0 when not; OFFERED_NOT_CONSUMED
pays under (REFUND, CHARGE) and 0 under (REFUND, REFUND); onFailure CHARGE pays on FAILED; a lane returning a null policy, a null outcome or
both settles by the defaults with every tier TAKEN (`closed`), 10 settles reaching the ledger, no reserve left open. The test lane is a small
`SessionFlow` inside the test: the samples `AdFlow` and `SmsFlow` are `final` and `VoiceFlow` overrides `chargeAtSettle` whole.

## 4 · The mutants — every rule added, broken once by hand

Every original saved first in `~/sa-a-work/mutants/` (`<name>-<file>.orig`), the rule broken by one exact replacement, the test run (alone in its
module, `-pl <module> -am`), the original put back and compared (`cmp` identical; sha256 `RefundPolicy.java` a4696e71aac44e47…,
`SessionFlowSteps.java` 4209a54b0da023b9…, `AdFlow.java` de5a7461b27560fa…, `MemLedgerPort.java` 1cce80286759aef6…), then green again: the
whole reactor of §2 ran on the restored trees after M1–M9b (item 1) and after M10 (item 2); after M11/M11b, `MemLedgerPortTest` alone, 10/0/0/0
(`item3-green-again.log`) — the restored file is byte-identical to the one item 2's whole reactor ran. Logs: `~/sa-a-work/logs/item1-mutant-M1..M3.log`, `item-mutant-<name>.log`.

| # | the rule broken | red |
|---|---|---|
| **M1** (brief) | `pays`: FAILED → `onFailure != CHARGE` | **7** of 20: the table's 4 rows, the default-hooks lane, the onFailure-CHARGE lane, the null-answer lane |
| **M2** (brief) | the default `chargeAtSettle` back to `answered ? reserved : 0` (the policy ignored) | **3** of 20: the offered-and-unshown lane, the onFailure-CHARGE lane, the null-answer lane (the default-hooks lane stays green: M2 is today's code) |
| **M3** (brief) | `parse` accepting any word (`default -> CHARGE`) | **1** of 20: the refusal sentences |
| M4 | the null-policy fallback removed | 1: the null-answer lane — OWED (`NullPointerException … RefundPolicy.pays`, `closed=false`, ERROR "OWED: the settlement of tier 0 (res_44) … was NOT taken") |
| M5 | the null-outcome fallback removed | 1: the null-answer lane — OWED (`NullPointerException … outcome is null`, `closed=false`) |
| M6a / M6b | `of` accepting a null onFailure / unshown | 1 each: `aPolicyNeedsBothRules` |
| M7 | `pays`: OFFERED_NOT_CONSUMED → `unshown != CHARGE` | 6: the table's 4 rows, the offered lane, the null-answer lane |
| M7b | `pays`: CONSUMED → false | 6: the table's 4 rows, the default-hooks lane, the null-answer lane |
| M8 / M8b | `callsRule()` = (REFUND, REFUND) / (CHARGE, CHARGE) | 2 / 3: the callsRule test, the null-answer lane (+ the default-hooks lane for M8b) |
| M9 / M9b | the default outcome: unanswered → OFFERED_NOT_CONSUMED / always FAILED | 2 each: the default-hooks lane, the null-answer lane |
| M3b / M3c | `parse` without the lower-case / without the trim | 5 / 3 errors: the case rows / the space rows |
| **M10** (brief, item 2) | the sample's unshown mapping inverted (`unshownViewIsCharged ? REFUND : CHARGE`) | **3** of seed-sessionflow's 209 (`*Test`): `SessionTaskCloseTest.anAdmittedViewNeverShown_underTheCallsRule_closesItsTaskFailed_andFree`, `SessionTaskCloseTest.anAdmittedViewTheScreenNeverShowed_itsWindowRunsOut_closesItsTaskFailed_withWhatTheSettlementKept`, `SessionFlowLifecycleTest.anAdmittedViewNeverShown_keepsItsChargeByTheOwnersRule_orPaysNothingByTheCallsRule` |
| **M11** (brief, item 3) | the `answered` look-up in `MemLedgerPort.reserve` removed | 1 of `MemLedgerPortTest`'s 10: `aRepeatedReference_answersTheFirstResult_andDebitsOnce` at :76 (`repeated` false — the second reserve went through and debited) |
| M11b | the repeat answered from the first result BUT the money held again under the lock | 1: the same test at **:82** — `[debited once] expected: 90 but was: 80.000000`: the live-balance line is the one that catches a double debit hidden behind a correct-looking repeated answer |

## 5 · The consumers (item 4) — from one `git archive` each, never a working tree

The full proof is `docs/agents/SA-A-part1-consumers.md` (cc8e847). In short: this branch at 64d801c installed into a FRESH `~/.m2/sa-a-repo`
(23:18:09–23:18:19, `-o install -DskipTests`, tail `~/.m2/repository` only: the 11 seed artifacts, nothing else); each consumer taken as ONE
`git archive` of ONE commit (your clarification), the same copy for both runs (`target/` removed before each run; one checksum over every
other file equal before and after each run); each run offline in a FRESH private head repository; the resolved jar read from each run's own
`dependency:build-classpath`.

| consumer (the commit) | BEFORE — tails `arch-bed-repo, repository` | AFTER — tails `sa-a-repo`, `arch-bed-repo`, `repository` | per class |
|---|---|---|---|
| routesphere `callflow-v3-voice` + parent pom (**01520466**, origin/main) | **99 / 0 / 0 / 0**, 10 classes (23:19:11–23:20:19) | **99 / 0 / 0 / 0**, 10 classes (23:20:25–23:21:18) | identical |
| ad-sphere, the Java suite (**c7cb15d**, origin/main) | **585 / 0 / 0 / 17** = default-test 574/0/0/17 + jvm-in-utc 4 + pc-listens 2 + no-ffmpeg 5, 129 classes (23:21:33–23:28:03) | **585 / 0 / 0 / 17**, the same four executions, 129 classes (23:28:22–23:34:39) | identical |

**The resolved seed-sessionflow** — BEFORE: `~/.m2/arch-bed-repo/com/telcobright/seed-sessionflow/1.0-SNAPSHOT/seed-sessionflow-1.0-SNAPSHOT.jar`
(sha256 9047488ece4300a4…, Oct 16 18:59, its pom at statewalk 3.3.0, no `RefundPolicy`/`SettleOutcome` in it); AFTER:
**`~/.m2/sa-a-repo/com/telcobright/seed-sessionflow/1.0-SNAPSHOT/seed-sessionflow-1.0-SNAPSHOT.jar`** (sha256 85e5df6eee697a33…, holding
`RefundPolicy`, `RefundPolicy$Rule`, `SettleOutcome`) — and in ad-sphere's AFTER every seed module came from `sa-a-repo` (seed-context,
-config-client, -config, -campaign, -routing, -sessionflow, -tenant). statewalk `arch-bed-repo`'s 3.3.0 and sphere-kit `arch-bed-repo`'s 1.0.3
on both sides. The 17 skipped in ad-sphere are the same both times, its own gates (AdSphereSoakTest 1, GateScriptTest 10, TierAdRatingIndexTest 1,
the billing-core and summary-service copy tests 3, ChangelogTwoTest 1, SwitchSchemaScriptTest 1). Both lanes override `chargeAtSettle` whole,
so the hooks are never asked there: the AFTER runs prove the api change breaks neither a lane's compile nor its behaviour. **No new red.**

## 6 · The clash grep and the implementers (an api change: read only, nothing built but the consumers of §5)

`\b(settleOutcome|refundPolicy|SettleOutcome|RefundPolicy)\b`, case-sensitive, by `git grep` at the refs this round used, and once over the
files on disk (22:5x, every `*.java` under ad-sphere, wifi, routesphere/callflow-v3-voice and routesphere-seed, worktrees included):

| repository | ref | result |
|---|---|---|
| routesphere-seed | fbf705b (the base) | none |
| ad-sphere | c7cb15d (origin/main; 3fafba7 and local main also read at 22:5x) | two DOC lines only — `docs/ad-as-call/exchange/Y/ARCH-0086-instruction.md:12`, `Y-INDEX.md:105` (the plan naming the base's RefundPolicy); no code |
| wifi (`wifi-sphere/`) | 08bdaed1 (master = origin/master; b0982910 when first read at 22:5x) | none |
| routesphere (`callflow-v3-voice/`) | 01520466 (origin/main) | none |
| on disk | the four trees above, worktrees included | none in any `*.java` |

**The implementers** of `SessionFlow` on disk (`extends SessionFlow<`): `AdCallFlow` (ad-sphere and its worktrees), `VoiceCallFlow`
(callflow-v3-voice), `WifiSessionFlow` (wifi-sphere and its worktrees), and this repository's samples `AdFlow`, `SmsFlow`, `VoiceFlow`,
`LedgerFlow` (seed-switch-ledger); the only subclasses of those are of `VoiceFlow` (`TierOrderTest.OrderedVoice`,
`SessionFlowHeldTierTest.SteppedVoice`, an anonymous one in `EntryInOwnTreeTest`). **Who overrides `chargeAtSettle` whole** (the hooks are
never asked there): `VoiceCallFlow.java:326` (01520466), `WifiSessionFlow.java:658` (08bdaed1), `AdCallFlow.java:564` (c7cb15d — part 2 moves
it onto the hooks), the samples `VoiceFlow.java:148` and `LedgerFlow.java:103`. **On the hooks:** the sample `AdFlow` (item 2). **On the
defaults:** the sample `SmsFlow`.

**For the WiFi architect** (no build here, as the brief says) — the grep to run on the ref the lock step takes:

```
git -C ~/telcobright-projects/wifi grep -n -w -E 'settleOutcome|refundPolicy|SettleOutcome|RefundPolicy' <ref> -- wifi-sphere   # expect nothing (nothing at 08bdaed1)
git -C ~/telcobright-projects/wifi grep -n 'protected BigDecimal chargeAtSettle' <ref> -- wifi-sphere/src/main                # expect WifiSessionFlow's whole override (:658 at 08bdaed1)
```

A method of that name with another return type would stop compiling against the new base; one with the same signature would become a hook
that is never asked while `chargeAtSettle` stays overridden whole. Neither exists today: `base/base.lock` can take the merge commit with no
WiFi change.

## 7 · Every red seen

Only the mutants' (§4), each one meant. No existing suite went red at any step — not in the reactor (§2), not in a consumer (§5) — so the
STOP condition never fired. No red that happened once and needed the five-run rule: the known timing-flaky
`OrchestrixLedgerTest:220` (ARCH-0087 red 1) stayed green in all three whole-reactor runs.

**Not a red, but the rule "nothing outside 127.0.0.1":** ad-sphere's own `ListenerTenantRoadsTest` (since 8986035, 2026-10-11, ARCH-0078 item
4) boots the service with `quarkus.http.host=0.0.0.0` in its test profile ON PURPOSE (two loopback addresses at one port) — `Listening on:
http://0.0.0.0:35143` in the BEFORE run, `:34897` in the AFTER run, for the seconds that test runs; every other app of the suite listened on
127.0.0.1. It was there at ARCH-0087's commit too (3f9cb73; that report says every process bound 127.0.0.1). Not changed (ad-sphere is
read-only to this round) — a later ad-sphere round could bind the two loopback addresses by name. No process of this round is left alive.

## 8 · What part 2 needs (ad-sphere, after your merge)

1. **`SEED=`** — `tools/cloud/cloud-base.sh:16` at c7cb15d is `SEED=${SEED:-fbf705bd283f8acb2824294ac155cd50ced7cae8}`; it moves to **the merge
   commit you make of `refund-policy` into `seed-context`** (the code tree = 64d801c). The in-pom comment (`pom.xml:46`) names fbf705b too.
2. **`AdCallFlow`** (c7cb15d, `AdCallFlow.java:552-571`): drop the `chargeAtSettle` override and answer the two hooks from the SAME facts the
   override reads today (`shownAtMs`, not the base's `answered()`), in the same order:
   ```
   settleOutcome(ctx): ctx.neverOffered() → FAILED · ctx.shownAtMs > 0 → CONSUMED · else → OFFERED_NOT_CONSUMED
   refundPolicy(ctx):  RefundPolicy.of(Rule.REFUND, props.get().isUnshownCharged() ? Rule.CHARGE : Rule.REFUND)   // onFailure fixed REFUND (R1-5)
   ```
   Equal to today's three branches for every input: never offered → 0 (FAILED under REFUND); shown → the reserve (CONSUMED); offered and never
   shown → the reserve when `unshown` charges, else 0 (OFFERED_NOT_CONSUMED).
3. **The parser** — `AdSphereSettings.unshownCharged(word)` (c7cb15d `:41-47`) → `RefundPolicy.Rule.parse("adsphere.billing.unshown", word)`:
   the refusal sentence is the same, word for word (`RefundPolicyTest` asserts it with that very key, for `free`, blank and null). The key and
   its default `charge` stay; `AdSphereProperties` may keep its boolean or carry the `Rule`.
4. **The proof** — as item 2 here: ad-sphere's own Java suite unchanged per class (the equivalence proof), and a mutant that inverts the
   unshown mapping, red.

## 9 · What the brief got wrong, or what moved under it

1. **Item 3's premise.** `MemLedgerPortTest:69` already asserts the account's LIVE balance after the repeat: `:82`
   `lab.cachedBalance("res_2", 11)` = 90 "debited once" reads `MemLedger.getEntity(schema, ACCOUNT, id)` — the very row `MemLedgerPort` reads
   under its lock (`Books.liveAccount`) — not only the repeated `Reservation`'s copy (`:77-81`). So nothing was added and item 3 has no
   commit; M11b shows `:82` is the line that catches a double debit behind a correct-looking repeated answer.
2. **§1's pseudo-code has no access modifiers** on `parse`, `callsRule`, `of`, `pays`; they had to be `public` (the sample `AdFlow` lives in
   another package, the ad lane in another repository).
3. **Line numbers that moved:** `WifiSessionFlow.java:640` is `:658` at wifi 08bdaed1 (the wifi repository advanced during this round);
   `SessionFlowSteps.java:220-228` is `:221-228` (cosmetic).
4. **ad-sphere moved during the round** (3fafba7 → c7cb15d) — handled by your clarification: one archive, c7cb15d, both runs.
5. **Not named in §0:** the sample `SmsFlow` overrides neither `chargeAtSettle` nor the hooks — it is the base's example of a lane on the
   DEFAULTS. With item 2 the samples now show all three shapes: `VoiceFlow` (whole override), `AdFlow` (the two hooks), `SmsFlow` (the defaults).
6. **ad-sphere's Java suite is not all-loopback** — see §7: `ListenerTenantRoadsTest` binds 0.0.0.0 by design (the brief's rule could not hold
   for "its Java suite" as written; it held for every other test app).
7. **The design doc** (`session-accounting-library.md` §5, the paragraph "Read 2026-10-16") still calls the hook `SessionOutcome`; the code,
   the brief and the doc's last paragraph say `SettleOutcome` — one word for the doc's owner.

## 10 · Not run, and not this round's

- **Not run:** the Kafka ITs (`KafkaDoorbellIT`, `KafkaCdrSinkIT` — gated, the 1 skipped in seed-context and in seed-sessionflow); ad-sphere's
  non-Java suites (the brief names its Java suite); ad-sphere's copy tests that need `-Dbilling.ddl`/`-Dbilling.src` (not passed, as in
  ARCH-0087 — skipped the same both times); wifi-sphere (no build, §6).
- **Not done (not this round's):** any lane (ad-sphere is part 2); `SessionFlowSettings`; `~/.m2/session-base-repo`; D2 (round B); the SMS
  (round C); turning `refund` on anywhere; the devlog card; the merge.
- **A dependency that could not be resolved offline:** none.
