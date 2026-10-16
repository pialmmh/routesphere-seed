# SA-A part 1 — the refund rule in the shared base (session-accounting round A, D1)

**To:** ONE local agent · repo `~/telcobright-projects/routesphere-seed`, worktree `worktrees/refund-policy`, branch `refund-policy`, cut from
`origin/seed-context` **fbf705b** · **From:** the routesphere architect · **Date:** 2026-10-16 21:54 +0600 · **The owner's go:** "execute and continue."
(2026-10-16, after the architect explained round A).

**Why:** the owner, 2026-10-16: ONE session-accounting library — reserve, settle, refund on mem-ledger — for the ad lane now and the
aggregator SMS later; "refund is optional and configured through config". The architect's survey (routesphere
`docs/architecture/session-accounting-library.md`) found the base already reserves (C6), renews (C14), settles (C16/C17) and gives back
(C7/C20) behind `LedgerPort`, and the switch ledger already answers a repeated reserve with the first. **One piece is missing: the refund
rule itself.** Today each reserve-priced lane re-implements "what does a session that did not consume pay" inside its own `chargeAtSettle`
override (ad-sphere `AdCallFlow.java:564-571`, keyed by `adsphere.billing.unshown`; this repository's own test sample `AdFlow.java:197`).
This part puts the rule in the base ONCE — two hooks read at settle time and one small policy type. The ad lane binds to it in part 2.

## 0 · Facts (read by the architect, 2026-10-16 ~22:00 +06)

- `seed-sessionflow/.../api/SessionFlowSteps.java:220-228` — the default `chargeAtSettle` is `ctx.answered() ? TierSettlement.reservedOf(level) : ZERO`.
  `api/SessionSettlement.settleLevel` (:38-47) applies it once per tier; **a RuntimeException there is the OWED path** (the tier's settlement
  is NOT taken, an ERROR is logged) — the new code must never throw because a lane returned null.
- Overrides of `chargeAtSettle` that stay WHOLE (priced by duration or rate, untouched by this round): voice `VoiceCallFlow.java:326`
  (routesphere `callflow-v3-voice`), WiFi `WifiSessionFlow.java:640` (wifi-sphere), this repository's test samples `VoiceFlow.java:148` and
  `seed-switch-ledger/.../samples/LedgerFlow.java:103`. The reserve-priced ones: ad-sphere `AdCallFlow.java:564` (part 2) and the sample
  `seed-sessionflow/src/test/.../samples/AdFlow.java:197` (item 2 here).
- **No name clash:** `settleOutcome`, `refundPolicy`, `SettleOutcome`, `RefundPolicy` appear nowhere in this repository, ad-sphere,
  wifi-sphere or callflow-v3-voice (the architect's grep).
- **`SessionFlowSettings` is NOT touched:** the WiFi lane builds it with the 7-argument constructor, the ad lane with the 8-argument one; a
  new component would churn every constructor for nothing. The policy is read **at settle time** through a hook — the ad lane reads its
  `props.get().isUnshownCharged()` at settle time today.
- **D3 (de-duplication on reserve) EXISTS:** `seed-switch-ledger/.../internal/MemLedgerPort.reserve` answers a repeated reference with the
  first (the `answered` map); `MemLedgerPortTest.aRepeatedReference_answersTheFirstResult_andDebitsOnce` (:69).
- This repository's reports live in `docs/agents/` (`ARCH-0087-done.md` is the latest: read it for the reactor and consumer commands).

## 1 · The design (additive; the default behaviour is byte-for-byte today's)

```
seed-sessionflow/.../dependencies/RefundPolicy.java
    public record RefundPolicy(Rule onFailure, Rule unshown)
    public enum Rule { CHARGE, REFUND;
        static Rule parse(String key, String word)   trim + lower-case (Locale.ROOT): "charge" | "refund"; anything else, null or blank →
            IllegalStateException(key + " must be 'charge' or 'refund', not '" + word + "'")      ← the ad lane's sentence today, word for word
    }
    static RefundPolicy callsRule()                  (REFUND, CHARGE) — the call's rule: a session that failed pays nothing
    static RefundPolicy of(Rule onFailure, Rule unshown)   a null refused (IllegalArgumentException, in words)
    boolean pays(SettleOutcome o)                    CONSUMED → true · OFFERED_NOT_CONSUMED → unshown == CHARGE · FAILED → onFailure == CHARGE

seed-sessionflow/.../api/SettleOutcome.java
    enum { CONSUMED, OFFERED_NOT_CONSUMED, FAILED }  one javadoc line each (OFFERED_NOT_CONSUMED: "the ad: handed to the phone, never shown")

seed-sessionflow/.../api/SessionFlowSteps.java
    protected SettleOutcome settleOutcome(C ctx)  { return ctx.answered() ? SettleOutcome.CONSUMED : SettleOutcome.FAILED; }
    protected RefundPolicy  refundPolicy(C ctx)   { return RefundPolicy.callsRule(); }      // read at settle time: a lane returns its config's policy
    protected BigDecimal chargeAtSettle(C ctx, LevelAdmission level)                         // the default, now policy-driven:
        policy  = refundPolicy(ctx)   — null → callsRule()
        outcome = settleOutcome(ctx)  — null → the default outcome above
        return policy.pays(outcome) ? TierSettlement.reservedOf(level) : BigDecimal.ZERO;
```
The javadoc of the three hooks says which lanes override `chargeAtSettle` whole (priced by duration or rate: voice, WiFi) and which answer
the two hooks instead (priced by the reserve: the ad now, the SMS later). Keep the top-level bodies short (house style: named steps).

## 2 · The items (one commit each; push `refund-policy` after each)

| # | Item | The proof |
|---|---|---|
| 0 | **Baseline:** the WHOLE reactor offline, `-Dmaven.repo.local=$HOME/.m2/sa-a-repo -Dmaven.repo.local.tail=$HOME/.m2/repository` — ONLY `~/.m2/repository` as the tail (`arch-bed-repo`'s mem-ledger speaks no H2 and breaks the switch-ledger lab; ARCH-0087) | the numbers per module in the notes |
| 1 | **D1 in the base, as §1** | `RefundPolicyTest` (seed-sessionflow): the `pays` table (3 outcomes × 4 policies); `parse` — both words, case and spaces, the refusal sentence for `free`, blank and null; `of` refuses a null; through the REAL settlement (reuse the samples' `Scene`): a flow on the DEFAULT hooks pays reserved when answered and 0 when not; a flow answering OFFERED_NOT_CONSUMED pays reserved under (REFUND, CHARGE) and 0 under (REFUND, REFUND); a flow with onFailure CHARGE pays reserved on FAILED; a lane returning a null policy or outcome settles by the defaults and never goes OWED. Every existing suite unchanged. Mutants: `pays`' FAILED branch inverted → red; the default `chargeAtSettle` back to `answered ? reserved : 0` (the policy ignored) → red; `parse` accepting any word → red |
| 2 | **The base's own sample `AdFlow` answers the hooks** instead of overriding `chargeAtSettle`: `settleOutcome` = answered ? CONSUMED : OFFERED_NOT_CONSUMED; `refundPolicy` = `of(REFUND, unshownViewIsCharged ? CHARGE : REFUND)` — the base's example of the pattern | every test that runs the sample unchanged green (the equivalence proof: the old override and the hooks answer alike); mutant: the sample's unshown mapping inverted → red |
| 3 | **D3 named, not built:** read `MemLedgerPortTest:69`; if it does not assert the account's LIVE balance after the repeat (only the repeated `Reservation`'s copy), add that assertion | mutant: the `answered` look-up in `MemLedgerPort.reserve` removed → red |
| 4 | **Install + the consumers:** install the reactor into the FRESH `~/.m2/sa-a-repo` (`-o install -DskipTests`); then from `git archive` copies, BEFORE (tails without `sa-a-repo`) and AFTER (`sa-a-repo` first): routesphere `callflow-v3-voice` (`origin/main`, with its parent pom; ARCH-0087: 99/0/0/0) and ad-sphere `origin/main` (its Java suite; tails `…,$HOME/.m2/arch-bed-repo,$HOME/.m2/repository` as in ARCH-0087; `GateScriptTest` skips in an archive — no jar — the same both times); the resolved `seed-sessionflow` jar path proven by `dependency:build-classpath` = `sa-a-repo` after. wifi-sphere: no build — the report names the grep for the WiFi architect | numbers before/after identical per class in both consumers; any new red = STOP and report |
| 5 | **Report** `docs/agents/SA-A-part1-done.md` | the commits, the numbers per module, the mutants, the consumers' numbers and resolved paths, what part 2 needs (the merge commit for ad-sphere's `SEED=`) | — |

**Not this round:** any lane (ad-sphere is part 2); `SessionFlowSettings`; `~/.m2/session-base-repo`; D2 (round B); the SMS (round C);
turning `refund` on anywhere.

## 3 · Rules that stand

Java 21 · Maven OFFLINE on the private `~/.m2/sa-a-repo` with the tails named; never write `~/.m2/repository`, `~/.m2/arch-bed-repo`,
`~/.m2/session-base-repo`, `~/.m2/seed-sw33-repo` · an api change builds the WHOLE reactor and greps the implementers · nothing on a box,
nothing outside 127.0.0.1 · mutants' originals at `~/sa-a-work/mutants/`, the red seen, restored (sha256 equal) · survive a stop:
`~/sa-a-work/notes.md` with a `State:` line after every item and before any long run; the report drafted after item 1 · one commit per item,
every commit after its test run; push only `refund-policy`; the merge is the architect's (`--no-ff` into `seed-context`) · absolute paths or
`git -C`; never `cd` into the main checkout · trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` · no devlog card · `date`
before any time · the end: the final message with the head, the numbers per module, the mutants, the consumers' numbers and resolved paths.
