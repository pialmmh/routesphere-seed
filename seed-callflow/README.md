# seed-callflow — the base call processing pipeline

One flow for every kind of traffic. A voice call, an SMS and an ad view are all a **Call**. They run the same
multi-tenant pipeline; each application says only its own steps.

Design: `pialmmh/routesphere` `docs/architecture/call-flow-base.md`. The CDR contract: `docs/architecture/ad-is-a-call.md` §4.

```
launch ─► PREPROCESSING ─► ADMITTING ─► ADMITTED ─────────► ACTIVE ─► TEARING_DOWN ─► SUCCEEDED
 (pool)   tenant           per candidate:  signaling          service    stop, SETTLE     │
          task             entry partner   (progress = a stay:            every tier       ▼
          candidates       tenant chain ▲ leaf→root   ringing, early media)           one CDR message
                           check · slot · rate · RESERVE                                a record per tier
                           route · confirm                      any refusal, deadline or kill ─► FAILED (same end)
```

The graph is the library's session graph with the base's preprocessing in front. It is generic: no protocol word (ringing,
playing, submitting) is a state of it — those live in the application's signaling child and arrive as `SignalingProgress`, a
stay in ADMITTED whose first report is the PDD. ADMITTED's one deadline bounds the whole pre-answer phase.

## 1 · The three classes you meet

| Class | What it is |
|---|---|
| `api.CallFlow<C>` | **The base class.** The pipeline, fixed. You extend it once per application |
| `api.CallFlowSteps<C>` | The steps you may override, in the order a call meets them. `CallFlow` extends it |
| `api.CallFlowEngine<C>` | Runs the calls: owns the pool of machines, launches a machine per call |

`C` is your context: `class AdCallContext extends CallFlowContext`. A context is made new for every call. It is the
only place a call keeps state.

## 2 · Write an application

Six steps have no default. Every application says them:

```java
public final class AdCallFlow extends CallFlow<AdCallContext> {

    @Override public String name() { return "ad"; }
    @Override protected int serviceGroup(AdCallContext ctx) { return 30; }

    /** PREPROCESSING: the request becomes the call's task. Null = done, else the refusal cause. */
    @Override protected String buildTask(AdCallContext ctx) { ... }

    /** ADMITTING: the partner that pays at the leaf, and its tenant. */
    @Override protected EntryPartner identifyEntryPartner(AdCallContext ctx) { return entryOfPartner(ctx, ctx.payerId); }

    /** ADMITTING, at every tier: this tier's rate, and what admission reserves. Null = unrated. */
    @Override protected TierRate rateAtLevel(AdCallContext ctx, Tenant tier, Partner partner, int levelIndex) { ... }

    /** ADMITTED: spawn the children that carry the call on the wire. */
    @Override protected void startSignaling(AdCallContext ctx, CallMachine machine) { machine.spawnChild("AdView", new AdViewContext(ctx)); }
}
```

**A call names its tenant, and never leaves that tenant's tree.** `ctx.tenantName` is the ROOT of the call's tree (its database
name). One process may serve several trees, and a partner id is unique inside one tree only — every operator's root starts at
partner 1, every tree with a reseller 44 has a tier `res_44`. So:

- `TenantLookup` answers INSIDE a root: `root(rootDbName)`, `tenantOfPartner(rootDbName, partnerId)`, `tenantByDbName(rootDbName, dbName)`.
  There is no lookup across everything served. `TenantLookup.of(roots…)` keeps one partner map per root.
- `entryOfPartner(ctx, partnerId)` finds the partner inside the call's own tree; a call that names no tenant finds nobody.
- Whatever an application's `identifyEntryPartner` hands back, the base refuses a chain that ends at another root
  (`PARTNER_NOT_FOUND`, one ERROR naming both roots): nothing of the call is put on the other tree.

Start it:

```java
CallFlowKit kit = CallFlowKit.builder()
    .tenants(tenantLookup).ledger(ledger)
    .cdrSink(CdrSinks.all(CdrSinks.journal(dir, clock, zone), CdrSinks.kafka(bootstrap, "cdr_btcl", "ad-sphere")))
    .zone(zone).settings(settings).build();

CallFlowEngine<AdCallContext> engine = CallFlowEngine.of(new AdCallFlow(kit, ...))
    .child("AdView", () -> new AdView(...))
    .start();

LaunchResult door = engine.launch(ctx);        // ctx.sessionKey = the call id
engine.deliver(callId, new EvAdShown());       // events of the wire
```

Three complete small applications are in the tests: `samples/VoiceFlow`, `samples/SmsFlow`, `samples/AdFlow`.

## 3 · The steps, and who changes them

A step that refuses returns the cause. Null means "passed". A step keeps nothing in a field.

| When | Step | Default (the call switch's rule) | Voice | SMS | Ad |
|---|---|---|---|---|---|
| PREPROCESSING | `resolveTenant` | the named tenant must be served here | | | |
| | `buildTask` | — (must say) | numbers, source | parts, user | the inversion |
| | `selectCandidates` | one candidate: the call itself | | | rule → dialplan → campaigns |
| ADMITTING | `useCandidate` / `candidateCount` | one | | | per campaign and content |
| | `identifyEntryPartner` | — (must say) | by source IP / SIP account | by user | the advertiser |
| | `identifyPartner` (above the leaf) | the parent's partner that stands for the child tenant: the id the child's database name ends with (`res_233` → 233, `res_233_2` → 2), else the partner named as the child — the call switch's live rule; no partner type is asked | | | |
| | `checkPartner` | status ACTIVE | | | |
| | (channel slot) | the partner's cap, at the leaf — fixed | | | |
| | `authorize` | nothing more | DID, account cap | | |
| | `applyRootRules` (root only) | none | digit filter | | |
| | `isFree` | no | | | the house ad |
| | `rateAtLevel` | — (must say) | per minute, 1 minute | per part, all parts | per view, whole |
| | (reserve) | `LedgerPort.reserve`, inside the admission's budget — fixed | | | |
| | `resolveRoute` (root) | none | dialplan | SMS routes | already routed |
| | `confirmAdmission` | nothing | | | claim the quota |
| ADMITTED | `startSignaling` | — (must say) | the ESL leg, over the plan's hop | the submit | the view |
| | `rerouteActionFor` (a failure before the answer) | FAIL_TERMINAL | the v1 table (C12): busy, no answer, rejected, absent, timer → REROUTE; temporary failure, congestion → RETRY_SAME | | |
| | `nextAttempt` | the v1 ritual on `ctx.routePlan`: RETRY_SAME = the same hop again; REROUTE = the next hop while one is left and the attempts are under the plan's cap (3); the attempt is recorded first; the same reserve, nothing re-admitted | | its own: the next route | |
| ACTIVE | `onActive` | nothing | recording | ends at once | credit window |
| | `rateNextWindow` | no renewal | one more minute | | |
| end | `settlesAsync` | false: TEARING_DOWN settles inline | true: the balance child settles and answers | | |
| | `onTeardown` | nothing | hang up both legs | | |
| | `billedDuration` | the signaling's, else the time since the answer | from the hangup | | the seconds watched |
| | `chargeAtSettle` | answered: the reserve. Else nothing | rate × minutes | | the owner's "unshown" rule |
| | (settle, slot, CDR) | fixed | | | |
| | `fillCdr` | nothing more | codec, IPs | | group 30 facts |
| | `onEnded` | nothing | | | close the campaign task |

## 4 · The pool

- `settings.pool` machines. That number is also the most calls live at once.
- A call that finds the pool full is refused at the door: `LaunchResult.busy()`. It never waits. It has no CDR, only a counter.
- A machine carries nothing of a call. Its only field is the flow (final, shared). The registry refuses at start-up a
  pooled machine with a non-final field. So a returned machine has nothing to forget.
- On return the framework clears the context, ids and timers. The next call starts in PREPROCESSING with its own context.
- Every state has a deadline. A call no deadline ended is killed by `globalTimeoutSec` (`HUNG_MACHINE`).
- A deadline, a kill and a shutdown run the same end as a normal hangup: settle, free the slot, publish the CDR.
- The caller may leave at any time. A `ServiceEnd` that arrives while the call is still preprocessed or admitted ends it
  with that cause, as soon as the running step is over. An admission that had just reserved is settled like any other end.
- `engine.stats()`: size, live, machines built, launched, busy, ended, CDRs, owed, slots held.

## 5 · The admission's time

Admission has **one** deadline: the ADMITTING state's. Everything inside it fits, by construction.

```
ADMITTING deadline (5 s)
|<----------- the budget: candidates that pay ----------->|<- reserve ->|
|  try 1: tier 0, tier 1 ...   try 2 ...                  |  a free     |
|  every ledger call waits at most what is left           |  candidate  |
```

- `settings.admissionReserveMs` (default 500) is kept for a free candidate (the house ad) and for the answer.
  The budget is the deadline minus the reserve: `settings.admissionBudgetMs()`.
- Every reserve is asked with the time left: `LedgerPort.reserve(level, amount, reference, withinMs)`. A ledger that calls a
  remote road waits the smaller of its own timeout and `withinMs`. A ledger in the process ignores it.
- When no time is left, a candidate that pays is not started and a tier is not asked. The candidate gives back what it held.
  `ctx.budgetSpent` is set. If nobody is admitted, the cause is `ADMISSION_TIMEOUT`.
- A free candidate is never cut by the budget. It asks nothing of the ledger.
- An application's `useCandidate` may ask `paidTimeIsOver(ctx)` to skip a paying candidate early. `admissionTimeLeftMs(ctx)`
  says what is left.
- A dry run (`simulate`) has no budget.
- At start the engine says the budget, and how many slow answers of the ledger fit in it (`LedgerPort.slowestAnswerMs()`).

Why: a state's deadline does not interrupt a running step. A step that ran past the deadline was followed by the timeout,
and its good decision was thrown away. Now the step ends in time.

## 6 · Money

Three verbs on `spi.LedgerPort`: **reserve** at admission, **settle** at the end, **release** when a chain is refused.

- A later tier's refusal gives the earlier tiers back.
- A ledger fault is `BILLING_SYSTEM_ERROR`. It is never shown as a balance cause.
- The settle rule (`chargeAtSettle`) runs **exactly once per tier, on every end path**. No path refunds by its own rule.
- Who runs it: `settlesAsync()` false (the default, the ad) — TEARING_DOWN settles inline. True (the call switch) — a balance child
  (`internal.LevelBalanceTracker`, spawned at ADMITTED beside the signaling) holds the tiers for the whole call, renews them every
  reserve period while ACTIVE, settles them when TEARING_DOWN asks (`SettleRequest`) and answers `Settled` with the per-tier results.
  A re-route retires the signaling child only; the balance child and its reserve live on. A call that ends on another path (a
  deadline, a kill) is settled by the supervisor's end with the same rule, once.
- A settlement the ledger did not take is logged as `OWED` with its reference. The CDR is still published and marked.
- The orchestrix ledger (`Ledgers.orchestrix`) asks road 16 once, and once more with the same reference when it got no
  answer and time is left. Defaults: connect 500 ms, read 1,500 ms.
- Its journal has two kinds of line. `owed`: a reserve that must go back and the ledger has no road for it; the amount is
  certain. `unsure`: a reserve the ledger did not answer in time; the money may have moved after the switch stopped
  waiting, so an officer looks the reference up first.
- Reserve references: `<call>#L<tier>`; a later candidate `<call>#<try>#L<tier>`; a renewal `…#W<n>`.

## 7 · The CDR

- The switch only publishes. It never writes a CDR or summary table.
- One Kafka message per call: key = the call id, value = the JSON array of `CdrEvent`, the leaf first.
- `CdrSinks.kafka(...)`: `acks=all`, idempotent. `CdrSinks.journal(...)`: one file per minute, one line per call.
- `CdrSinks.replay(file, sink)` sends a journal file again. billing-core takes a call once per tier.
- A call nobody was admitted for still sends one record, on the tenant it entered.

## 8 · Packages

```
api/           CallFlow · CallFlowSteps · CallFlowEngine · CallFlowContext · CallCause · CallState · CdrEvent · TierRate …
spi/           LedgerPort · CdrSink · TenantLookup            (the host implements these)
publishes/     Preprocessed · BudgetStart                     (SignalingProgress/Done/Failed · ServiceEnd · SettleRequest · Settled are the library's)
dependencies/  CallFlowKit · CallFlowSettings · CdrSinks      (everything is handed in)
internal/      CallFlowSupervisor (the machine) · LevelBalanceTracker (the balance child) · ChannelSlots · CdrAssembler · KafkaCdrSink · FileCdrJournal …
testkit/       InMemoryLedger · RecordingCdrSink · TenantTreeBuilder
```

## 9 · Tests

```bash
mvn -o test                       # the whole suite
mvn -o test -Dseed.it=true        # + the Kafka road over a real broker on 127.0.0.1:9092
```

For a test of a deadline: `testkit.ManualClock` (a clock the test moves) with `InMemoryLedger.slowOn(tenant, partner, ms)`
and `timePassesBy(clock::advance)`. The slow ledger "takes" its time by moving the clock, so the test is exact and waits
for nothing. `CallFlowBudgetTest` is the example.

## 10 · History

- 2026-09-29 — the first shape, ad-only: `RoutedSessionSupervisor`, `ChainAdmission`, `AdBillingPort`, `LevelCdrWriter` (the switch wrote its
  own CDR rows).
- 2026-10-03 — the base pipeline (`CallFlow`); ad-sphere moved onto it (`AdCallFlow`) and the ad-only classes were removed. The orchestrix
  ledger is now `Ledgers.orchestrix(...)`, an adapter of `LedgerPort`: reserve = road 16 on the partner's `billing_account_id`; what orchestrix
  cannot give back yet is written to an owed journal.
- 2026-10-03, later — the admission's budget (§5): one deadline for the whole admission, every ledger call bounded by what is
  left, the `unsure` journal line, and a caller that leaves during preprocessing or admission ends the call.
