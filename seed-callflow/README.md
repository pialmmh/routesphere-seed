# seed-callflow — the base call processing pipeline

One flow for every kind of traffic. A voice call, an SMS and an ad view are all a **Call**. They run the same
multi-tenant pipeline; each application says only its own steps.

Design: `pialmmh/routesphere` `docs/architecture/call-flow-base.md`. The CDR contract: `docs/architecture/ad-is-a-call.md` §4.

```
launch ─► PREPROCESSING ─► ADMITTING ─► ADMITTED ─► (RINGING) ─► ACTIVE ─► TEARING_DOWN ─► SUCCEEDED
 (pool)   tenant           per candidate:  signaling   progress     service    stop, SETTLE     │
          task             entry partner                                        every tier       ▼
          candidates       tenant chain ▲ leaf→root                                         one CDR message
                           check · slot · rate · RESERVE                                    a record per tier
                           route · confirm                          any refusal, deadline or kill ─► FAILED (same end)
```

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
    @Override protected EntryPartner identifyEntryPartner(AdCallContext ctx) { return entryOfPartner(ctx.payerId); }

    /** ADMITTING, at every tier: this tier's rate, and what admission reserves. Null = unrated. */
    @Override protected TierRate rateAtLevel(AdCallContext ctx, Tenant tier, Partner partner, int levelIndex) { ... }

    /** ADMITTED: spawn the children that carry the call on the wire. */
    @Override protected void startSignaling(AdCallContext ctx, CallMachine machine) { machine.spawnChild("AdView", new AdViewContext(ctx)); }
}
```

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
| | `identifyPartner` (above the leaf) | the parent's RESELLER partner of the child tenant | | | |
| | `checkPartner` | status ACTIVE | | | |
| | (channel slot) | the partner's cap, at the leaf — fixed | | | |
| | `authorize` | nothing more | DID, account cap | | |
| | `applyRootRules` (root only) | none | digit filter | | |
| | `isFree` | no | | | the house ad |
| | `rateAtLevel` | — (must say) | per minute, 1 minute | per part, all parts | per view, whole |
| | (reserve) | `LedgerPort.reserve` — fixed | | | |
| | `resolveRoute` (root) | none | dialplan | SMS routes | already routed |
| | `confirmAdmission` | nothing | | | claim the quota |
| ADMITTED | `startSignaling` | — (must say) | the ESL leg | the submit | the view |
| | `nextAttempt` | no retry | | next route | |
| ACTIVE | `onActive` | nothing | recording | ends at once | credit window |
| | `rateNextWindow` | no renewal | one more minute | | |
| end | `onTeardown` | nothing | hang up both legs | | |
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
- `engine.stats()`: size, live, machines built, launched, busy, ended, CDRs, owed, slots held.

## 5 · Money

Three verbs on `spi.LedgerPort`: **reserve** at admission, **settle** at the end, **release** when a chain is refused.

- A later tier's refusal gives the earlier tiers back.
- A ledger fault is `BILLING_SYSTEM_ERROR`. It is never shown as a balance cause.
- The settle rule (`chargeAtSettle`) runs **exactly once per tier, on every end path**. No path refunds by its own rule.
- A settlement the ledger did not take is logged as `OWED` with its reference. The CDR is still published and marked.
- Reserve references: `<call>#L<tier>`; a later candidate `<call>#<try>#L<tier>`; a renewal `…#W<n>`.

## 6 · The CDR

- The switch only publishes. It never writes a CDR or summary table.
- One Kafka message per call: key = the call id, value = the JSON array of `CdrEvent`, the leaf first.
- `CdrSinks.kafka(...)`: `acks=all`, idempotent. `CdrSinks.journal(...)`: one file per minute, one line per call.
- `CdrSinks.replay(file, sink)` sends a journal file again. billing-core takes a call once per tier.
- A call nobody was admitted for still sends one record, on the tenant it entered.

## 7 · Packages

```
api/           CallFlow · CallFlowSteps · CallFlowEngine · CallFlowContext · CallCause · CallState · CdrEvent · TierRate …
spi/           LedgerPort · CdrSink · TenantLookup            (the host implements these)
publishes/     Preprocessed · ReserveTick
dependencies/  CallFlowKit · CallFlowSettings · CdrSinks      (everything is handed in)
internal/      CallFlowSupervisor (the machine) · ChannelSlots · CdrAssembler · KafkaCdrSink · FileCdrJournal …
testkit/       InMemoryLedger · RecordingCdrSink · TenantTreeBuilder
```

## 8 · Tests

```bash
mvn -o test                       # the whole suite
mvn -o test -Dseed.it=true        # + the Kafka road over a real broker on 127.0.0.1:9092
```

## 9 · History

- 2026-09-29 — the first shape, ad-only: `RoutedSessionSupervisor`, `ChainAdmission`, `AdBillingPort`, `LevelCdrWriter` (the switch wrote its
  own CDR rows).
- 2026-10-03 — the base pipeline (`CallFlow`); ad-sphere moved onto it (`AdCallFlow`) and the ad-only classes were removed. The orchestrix
  ledger is now `Ledgers.orchestrix(...)`, an adapter of `LedgerPort`: reserve = road 16 on the partner's `billing_account_id`; what orchestrix
  cannot give back yet is written to an owed journal.
