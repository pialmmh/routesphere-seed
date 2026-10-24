# P1 — RoutesphereFlow: the one base flow, on statewalk 4.0.0 (DRAFT)

Status: READY 2026-10-22 (statewalk 4.0.0 released locally, §K). Launched on the owner's word "continue with the plan when testing done … continue unattended". Still pending:
1. (done) statewalk 4.0.0;
2. the owner's rulings on the design page's decisions D1–D7 (`routesphere-flow.html` §9). This draft assumes the recommended options; each assumption is marked **[assumes Dn]**.

The owner's order (2026-10-21, verbatim core): "refactor and organize the following applications … to maximize code-reuse, with based lib/class/interface approach … migrate to statewalk v4.0 … base class shall have the logic to load multi-tenant, multi-level reseller based lifecycle part, dynamic context loading through prime-context, and kafka notification of config changing through kafka … identifySiblingLevelTenants … iterate through all multi level reseller tenants … identityPartner() … route() … performCreditControl() (reserveBalance and finalizeTier; refund when the service is not delivered) … handleRerouting() … finishSession() (tear down, write cdr, return + reset statemachines to the registry pool) … we will first refactor the ad-sphere flow, the base flow shall be called routesphereFlow."

The design: `~/arch-drafts/launch/session-supervisor/routesphere-flow.html` (served at http://127.0.0.1:7299/routesphere-flow.html). Read it first, completely.

## 0 · Where things are

| what | where | state |
|---|---|---|
| the base today | `routesphere-seed/seed-sessionflow` (branch `seed-context` @ 1297f1d = the SEED ad-sphere pins) | `SessionFlow<C> extends SessionFlowSteps<C>` + its own graph `SessionFlowSupervisor` (PREPROCESSING → ADMITTING → ADMITTED → ACTIVE → TEARING_DOWN → SUCCEEDED/FAILED/DEFERRED) on statewalk 3.3.0 |
| what to keep from it | `AdmissionChain` (candidates, the tier walk, undo, the time budget), `resellerPartnerOf`, `SessionSettlement`, `SessionCdr`, `SessionTaskClose`, `ChannelSlots`, `OrchestrixLedger`, `CdrSinks` / `KafkaCdrSink`, the journals, `RefundPolicy` / `SettleOutcome`, `RoutePlan` / `RerouteAction`, `TenantResolver` / `TenantResolvers` / `IdentificationRules` | the logic stays; it is re-wired under the new base |
| the per-root context | `seed-tenant` (`TenantTrees`, `PrimeContextTreeSource`, `KafkaDoorbell`), `seed-context` (`TenantContextsImpl` reloads ONE tenant per topic), `seed-config-client` (`KafkaTopicsDoorbellSource`) | already per root |
| the library | statewalk 4.0.0 (`SessionSupervisor` final graph IDLE · INIT · ADMITTING · ROUTING · CONNECTING · ACTIVE · SUCCEEDED / FAILED; `SessionHandler`; `.signaling(spec)` with mandatory `up`; the kit; persistence) | releasing after the fix round |
| consumers | ad-sphere (`AdCallFlow`), routesphere/callflow-v3-voice (`VoiceCallFlow`), wifi-sphere (`WifiSessionFlow`) | NOT touched in P1 — they stay on seed 1.0 until P2 / P3 / P4 |

## A · Branch, version, pins

- New branch `routesphere-flow` from `seed-context` @ 1297f1d (first: report how `master` and `seed-context` differ; the work goes on top of the pinned SEED).
- The seed's version moves to **2.0.0-SNAPSHOT** (an incompatible API); consumers on 1.0-SNAPSHOT / 1297f1d are unaffected.
- statewalk pinned to **4.0.0** in the seed's parent and `seed-bom`; `StatewalkPinTest` updated; statewalk-v2 / state-walk v1 stay out of the seed.
- Module name **kept `seed-sessionflow`** [assumes D6]; the class is `RoutesphereFlow`.

## B · The base: `RoutesphereFlow<C extends RoutesphereContext> implements SessionHandler<C>`

The library calls twelve handler methods at fixed points; the base implements every one of them FINAL and asks the application through the owner's methods. Shape and names exactly as in the design page §2.

| library call (state) | the base does | the owner's method(s) it calls |
|---|---|---|
| `onStart` (IDLE → INIT) | keep the facts; find the root; take the root's snapshot; build the task | **(a) `identifySiblingLevelTenants(facts)`**, `buildTask` |
| `prepare` (INIT) | select the candidates; spawn the budget child when the service renews | `selectCandidates`, `renewsBudget` |
| `admit` (ADMITTING) | per candidate: the payer's tier, the tier walk leaf → root, per tier: check, authorize, root rules, rate + reserve; the last gate; a refused candidate gives every reserve back | **(c) `identifyEntryPartner`, `identifyPartner`**, **(b) `iterateResellerTenants`**, `checkPartner`, `authorize*`, `applyRootRules`, **(e) `reserveBalance`** (→ `rateAtLevel`), `confirmAdmission` |
| `nextHop` (ROUTING) | the current hop of the plan | **(d) `routing`** |
| `retry` (CONNECTING, the guard) | by cause: RETRY_SAME / REROUTE (advance the plan under `maxAttempts`) / FAIL_TERMINAL; never re-admits, never re-reserves | **(f) `handleRerouting`** (→ `rerouteActionFor`) |
| `onEstablished`, `onActive` (ACTIVE) | keep the answer; start the budget window (G-1) | `onServiceStarted` |
| `settle` (final) | per tier: delivered → charge rate × billed units, the rest back; not delivered → the refund rule | **(e) `finalizeTier`** (→ `billedUnits`, `delivered`, `settleOutcome`, `refundPolicy`) |
| `release` (final, never answered) | every reserve back | — |
| `record` (both finals) | teardown, CDR per tier, the task close, the SDR, the app's close — then the registry resets every machine and returns it to its pool | **(g) `finishSession`** (→ `onTeardown`, `cdrPerTier`, `buildSdr`, `onEnded`) |

- The old phases map: PREPROCESSING (RESOLVE_TENANT, BUILD_TASK, SELECT_CANDIDATES) → `onStart` + `prepare`; ADMITTING → `admit`; RESOLVE_ROUTE → `nextHop`; ADMITTED (signal, progress, rerouting) → the library's CONNECTING (the base never starts signaling itself: `Connect` is sent by the library); TEARING_DOWN + `SettleRequest` / `Settled` → the synchronous `settle` in the final state (the owner's ruling: no TEARING_DOWN; a local ledger or a durable outbox, never a remote call in the final entry); DEFERRED → report every use first (statewalk 4.0 has no DEFERRED).
- Delete: `SessionFlowSupervisor`, `SessionState`, `Preprocessed`, the 3.x events; `SessionFlowEngine` is rebuilt on the 4.0 registry builder (`.supervisor(SessionSupervisor.spec(name, ctx, flow), pool).signaling(spec, pool).child(budget, pool).channel(…)`).
- One field on the base: `RoutesphereKit kit` (pool rule). Every per-session value lives in `RoutesphereContext extends SessionContext` (the root, the tiers with their reserves and settlements, the chosen candidate, the plan, the task) — Jackson-serializable (persistence).
- Top-level functions read as named steps (house rule, CLAUDE.md "Top-level function readability").

## C · (a) identifySiblingLevelTenants — the root first, per protocol

- `RoutesphereFacts implements SessionFacts` carries `Ingress(kind, match)`: `listen <ip>:<port>` (HTTP), `esl <host>:<port>` (FreeSWITCH), `vlan <gateway>:<svlan>` (WiFi), `topic <name>` (Kafka). The signaling machine stamps it.
- Default implementation: `kit.resolver().tenantOf(facts.ingress())` over `TenantResolvers.ofRules(IdentificationRules.fromBootstrap(…))`. No match → the session fails `NO_TENANT`; never a default root (operator model §5).
- From that point every lookup is scoped to that root's tree: the entry partner, the partner map (per root, never global), the tier walk (`AdmissionChain.leavesTheCallsOwnTree` stays), rates, the CDR topic `cdr_<root>`.
- Tests: two roots with overlapping partner ids; a request per root; no lookup crosses roots; no match refused; a rule naming an unknown root refused at load.

## D · The dynamic context and the doorbell, per root

- `kit.tenants()` = seed-tenant's `TenantTrees`: one immutable snapshot per root from `POST /get-specific-tenant-root?root=<root>`; the doorbell `config_event_loader_<root>` reloads THAT root only (debounced); a session keeps the snapshot it started with.
- The registry of partners (today a flat global registry in routesphere-core) is per root in the base.
- Tests: a doorbell for root A reloads A and not B; a session started before the reload finishes on its old snapshot; a new session sees the new one.

## E · (e) performCreditControl — one ledger port

- `LedgerPort` (reserve · settle · release) is the ONLY money road [assumes D3]; the base never names a ledger.
- `reserveBalance` per tier at admission (today's `AdmissionChain` rate + reserve); the budget child renews per tier in ACTIVE.
- The budget child = today's `LevelBalanceTracker` as a 4.0 `MachineSpec` (IDLE first with `.timeout(0)`; HOLDING → FIRST_RENEWAL → RENEWING → WINDING_DOWN; publishes `BudgetExhausted`; the final-window cut); spawned in `prepare`, started at ACTIVE through G-1, reset with the session.
- `finalizeTier` per tier in the final state: delivered → charged = rate × billed units, the rest returned (negative = extra debit, the call's rule); not delivered → `refundPolicy` (the ad's `unshown`, the SMS's refund-on-non-delivery by config).
- Every answered session is settled, never released (D-A1).

## F · (f) handleRerouting

- `rerouteByTable`: RETRY_SAME → the same hop; REROUTE → `RoutePlan.advance()` while attempts < `maxAttempts`; FAIL_TERMINAL → no. The library sends the next `Connect`.
- The call's v1 table (CALL_REJECTED, USER_BUSY, NO_ANSWER, SUBSCRIBER_ABSENT, RECOVERY_ON_TIMER_EXPIRE → REROUTE; NORMAL_TEMPORARY_FAILURE, SWITCH_CONGESTION → RETRY_SAME; else FAIL_TERMINAL) is the voice sample's `rerouteActionFor`.

## G · (g) finishSession

`onTeardown` → the CDR per tier (one message, one record per tier, leaf first, `cdr_<root>`) → the task close → the SDR → `onEnded`. Then the registry's ritual resets the supervisor, the signaling machine and the budget child to IDLE and returns them to their pools.

## H · The testkit and the four samples (acceptance)

- The seed testkit on 4.0: `TenantTreeBuilder` (roots and reseller tiers), `InMemoryLedger`, `RecordingCdrSink`, `ManualClock`, a fake channel + decoder per sample.
- Four samples, each a full `RoutesphereFlow` subclass with its own signaling `MachineSpec`: **AdFlow** (one `AdPlayback` machine [assumes D1]), **VoiceFlow** (an ESL-like machine), **WifiFlow** (gateway events; the subscriber as a partner [assumes D5 — sample only]), **SmsFlow** (submit → delivery report; refund on non-delivery by config).
- Scenarios every sample runs: two roots; 1, 2 and 3 tiers; partner not found; no balance at a middle tier (every reserve back); routing; rerouting (voice: REROUTE by cause → a second hop; RETRY_SAME); credit (voice: renewals and exhaustion → Stop; ad: whole view and the unshown rule; SMS: refund on non-delivery; WiFi: windows); the finish (CDR records per tier, the task, the SDR, every pool back to its start); a crash mid-session restored from the store with its reserves.
- The call's C1–C23 (routesphere `docs/architecture/call-flow-base-pseudocode.md` §1) as a regression table in the report: each row → the hook and the test of the voice sample that covers it.

## I · Out of scope

No consumer moves (ad-sphere is P2, voice P3, WiFi P4, SMS P5); routesphere-core untouched; no deploy; no push; no bed.

## J · Streams

Two streams, each in its own worktree under `routesphere-seed/worktrees/`:
- **B (base)**: §A, §B (the API skeleton committed FIRST as `B0` so T can build against it), §C, §D, §E, §F, §G.
- **T (testkit + samples)**: §H on top of `B0`, rebased on B as it lands; the C1–C23 table.
The architect merges B, then T, runs the suite on the merge head in a clean copy, and reports.

## K · statewalk 4.0.0 — what the base builds on (released locally 2026-10-22, tag v4.0.0)

The fix round (F1–F20, report `statewalk/docs/tests/SW-V4-F-report.md`) closed every item this brief depended on:
- **D-A1** — `SessionSupervisor.close`: an ANSWERED session is settled on its real service time whatever its outcome; when it ended with no `ServiceEnd` (ACTIVE's deadline `MAX_DURATION_REACHED`, the lifetime cap `TIMEOUT_GLOBAL`, a forced failover) the base sends `Stop(cause)` first; a never-answered session gets `Reject(cause)` and `release`. An answer crossing the end counts (the registry keeps a published `Established`; the close waits ≤ 100 ms for the signaling machine's road in progress).
- **The builder refuses** a `.signaling(...)` spec whose live states do not all handle BOTH `Reject` and `Stop` (plus the `up` on every transition and a `TimeoutEvent` rule on timed states). Every sample's signaling machine must comply.
- **G-1** — `SessionActive(long atMs, Established answer)` goes to every live helper child at ACTIVE's entry, before `onActive`: the budget child starts metering on it (no handler code needed).
- **CN-2** — a retry is granted only while the session's signaling machine lives; otherwise FAILED at once with the real cause.
- One default table per lane = the REGISTERED supervisor name (`SessionSupervisor.spec("AdSupervisor", …)` → table `AdSupervisor`).
- The restart walk sends on the wire (held until `Channel.start`, then flushed in order) and reaches its registry; a final row's failed write is retried.
- Open, for the owner: E-F1 (MariaDB 64 KB `TEXT` cap on the session row → keep contexts small, §L.6), E-F2, multi-node maintenance on one table.

## L · What the statewalk 4.0 testing taught, binding on the base (added 2026-10-22, from the E/S/M reports)

1. **Money calls are idempotent per (session id, tier).** Persistence is async (the owner's rule), so a process that dies after the final state's synchronous settle/record but before the final row lands restores the session from an older row and ends it again (S: 5 sessions on SQLite out of 4,800; E: about one per engine per four SIGKILLs, once settling a call twice with a longer talk time). `reserve`, `settle`, `release` on the `LedgerPort` and the CDR's key carry the session id and the tier; a second `settle` or `release` of the same (session, tier) is a no-op; the CDR sink dedups by the same key. Test it with a crash between `settle` and the final row.
2. **The budget child starts on `SessionActive`** (fix-round item G-1), never earlier; its renewal period is at least the call's minute — every renewal tick rewrites the whole session row (S §10.2: a 25 ms period saturated the CPU).
3. **The restored talk time** spans the downtime when the BYE was lost during it (E §9.2: the downtime is billed as talk time). The base leaves the rule to the application through a hook (`billedUnits` sees `restoredAtMs`); the call's answer comes in P3 (reconcile with FreeSWITCH on restore). Flag it in the report as the owner's business decision.
4. **One table per lane.** Each application registers its own supervisor name (`AdSupervisor`, `CallSupervisor`, …) so each lane gets its own table (fix-round D-M1/D-E4); the maintenance hour runs on ONE node per table; the docs and the samples use a POOLED `DataSource` (E §9.5).
5. **The session contract after the fix round:** an answered session is settled and its wire torn down whatever its outcome; a never-answered one is released (D-A1). Every live state of every sample's signaling machine handles both `Reject` and `Stop` (the builder refuses it otherwise).
6. **A row over 64 KB fails on MariaDB** (E-F1, the owner decides `LONGTEXT`): keep `RoutesphereContext` small — ids and amounts, never the whole tenant snapshot (the snapshot is re-read from the root by its version on restore).
