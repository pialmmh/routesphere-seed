# P1 · stream T — the testkit and the four samples (report)

**Status: done.** Every item of §H is built and green, on top of stream B up to **F3** (p1-base @ 3cb2882). The architect's follow-ups T10
(§12, the R-1 evidence) and T11 (F1's accessor in AdFlow) are done too. Nothing is in progress.
Branch `p1-samples` (worktree `routesphere-seed/worktrees/p1-samples`); the report is its last commit. Nothing pushed.

| | |
|---|---|
| the reactor, clean copy of 3bf4340 (the code of this branch's head), offline, alone | **430 / 0 / 0 / 7** (tests / failures / errors / skipped) |
| this stream's tests | 69 in `seed-sessionflow-samples` (4 of them the `@Disabled` R-1 proofs of §12) + 4 in the testkit (`seed-sessionflow`) |
| mutants run against the samples | 7 — all 7 red, every file restored byte-identical |
| consumer repos touched | none |

## 0 · The commits, in order

| commit | item |
|---|---|
| 8165371 | **T1** the testkit: reseller tiers of any depth, one tree per root, the CDR sink's billing dedup view |
| 9e1f5bb | **T2** module `seed-sessionflow-samples`: the bed, **AdPlayback** and **EslCall**, alone on statewalk 4.0.0 |
| c52fa56 | **T3** **WifiGateway**, **SmsSubmit**, the crash harness (`CrashingStore`), `EslCallRestoreTest` |
| d672d38 | merge p1-base @ **B0** (+ the module on 2.0.0-SNAPSHOT, the CDR sink records the root) |
| 3aefb40 | **T4** `World` (two roots, 1/2/3 tiers), `FlowBed`, **VoiceFlow** + 13 scenarios (C1–C23) |
| f9c7d4a | **T5** **AdFlow** + 8 scenarios |
| c5cf3d5 | **T6** **SmsFlow** + 7 scenarios, **WifiFlow** + 4 scenarios |
| fdda807 | merge p1-base @ **B4** (clean, nothing to change) |
| c092e2e | **T7** the task in the ad and SMS samples; WiFi: middle-tier refusal + crash mid-session |
| 3445acb | merge p1-base @ **B5** (clean, nothing to change) |
| efc69ef | **T8** voice: C4's entry checks (INVALID_DID, the SIP account's cap) and §D in a sample (the doorbell per root on seed-tenant's TenantTrees) |
| 080994e | merge p1-base @ **B7** (clean, nothing to change) |
| aa75d11 | the module in the seed's README |
| 232ed6a | merge p1-base @ **B8** (clean, nothing to change) |
| 80517a4 | the samples' bed waits for the restore before a test reads the states (the clean copy of 232ed6a caught the race) |
| 6f71283 | merge p1-base @ **B9** (clean, nothing to change) |
| 0d02cf7 | **T9** this report (the first version) |
| 16d3c28 | merge p1-base @ **B10–B11** (clean, nothing to change) |
| ac13bf8 | **T10a** R-1 evidence (a) and (b): the crash switch, the crashing ledger and CDR road, the per-second voice billing |
| 097e8e8 | **T10b** R-1 evidence (b2) and (c) |
| c0d5b39 | **T10c** §12 of this report |
| 49a5483 | merge p1-base @ **F3** (F1–F3 and B's report §9; clean, the samples as they were) |
| b2c4797 | **T11** AdFlow's step (d) routes by the base's `ctx.candidateBeingAdmitted()` (F1); AdContext's own copy dropped |
| 3bf4340 | **T12** C21 on the voice sample: the base's dry run (`simulate`) |
| (this commit) | **T13** this report brought up to date |

## 1 · Where the code is, and why a module

```
routesphere-seed/
  seed-sessionflow/            stream B's base — and the testkit package (testkit/), shipped in its jar for every product's tests
  seed-sessionflow-samples/    NEW — test code only, nothing ships (its jar is empty)
    bed/      Bed · ScriptedHandler · Script · BedContext · CrashingStore   (a machine alone under a minimal handler)
    scene/    World · FlowBed                                               (a sample on the base, end to end)
    ad/       AdPlayback (+ frames, decoder, channel) · AdFlow · AdContext · AdCandidate · AdQuota · AdSdr
    voice/    EslCall    (+ frames, decoder, channel) · VoiceFlow · VoiceContext
    wifi/     WifiGateway (+ …) · WifiFlow · WifiContext · WifiSdr
    sms/      SmsSubmit  (+ …) · SmsFlow · SmsContext
```

Why a module: the four machines had to be built and tested **before** B0, on statewalk 4.0.0, while `seed-sessionflow` was still on 3.3.0 —
one classpath cannot hold both. So the machines went into a module that depended on statewalk alone; after B0 the module takes the base
as a test dependency and the samples sit next to their machines. B0 deleted the 3.x samples from `seed-sessionflow/src/test`; nothing
of this stream lives there except the two testkit tests.

## 2 · The testkit (seed-sessionflow `testkit/`)

| class | what changed |
|---|---|
| `TenantTreeBuilder` | `reseller(id, name)` declares the partner (type RESELLER) in this tier **and** its own tier below, named by the house rule (`res_<id>` under a root, `<tier>_<id>` deeper: `res_44_7`); `up()`, `spec(db)`; a tier name declared twice in one builder is refused. Two roots = two builders (one tree per root, as prime-context serves them). |
| `RecordingCdrSink` | `published()` stays every publish as offered; **`records()`** = what billing keeps, one per (tenant, channelCallUuid); **`duplicates()`** counts the offers dropped (§L.1); `publish(root, …)` records the root — `Published.topic()` = `cdr_<root>`. |
| `InMemoryLedger`, `ManualClock` | unchanged: the ledger was already idempotent per reference, and the base names every reference `<session>#L<tier>` (`AdmissionChain.referenceOf`; written into the `LedgerPort` contract in B3) — exactly the (session, tier) key §L.1 asks for. |

## 3 · The four signaling machines — their dictionary tables

Every machine: IDLE first with `.timeout(0)`; an `up` on every transition and stay; every timed state handles `TimeoutEvent`; every live
state handles **both** `Reject` and `Stop` (statewalk's builder refuses any gap — building the registry is the first test). The first event
`isFirst()`: the kit takes the supervisor and the machine from their pools. The machine stamps the **ingress** on the facts. Each machine's
spec takes a facts factory, so it never named the base before B0; the lane facts records implement `RoutesphereFacts` since B0.

### 3.1 AdPlayback — the ad's ONE machine [assumes D1] (`ad/AdPlayback.java`)

The design's table (§6) plus the rows the 4.0 builder requires (marked +).

| from | frame / command | to | wire out (the HTTP answer) | up to the supervisor | the supervisor |
|---|---|---|---|---|---|
| IDLE | POST ad-sessions (first) | INIT | — (the request waits) | SessionStart(AdFacts: listen ip:port, mac, msisdn, zone, site, app, gw) | IDLE → INIT → ADMITTING → ROUTING |
| INIT | Connect(creative) ↓ | OFFERED | 200 AdSessionView (the hand-over) | SignalingProgress(OFFERED, creative) | CONNECTING |
| INIT | Reject ↓ / Stop ↓ (+) | TERMINATED | 204 X-Ad-Reason: cause | SignalingFailed(cause) | FAILED already |
| INIT + | timeout 5 s | TERMINATED | 204 X-Ad-Reason: NO_DECISION | SignalingFailed(NO_DECISION) | — |
| OFFERED | /shown | SHOWING | 200 | SignalingProgress(SHOWING, atMs) | stays CONNECTING |
| OFFERED, SHOWING | /abandoned | TERMINATED | 200 | SignalingFailed(ABANDONED) | FAILED (no re-route: CN-2) |
| OFFERED + | show timer 30 s | TERMINATED | — | SignalingFailed(NOT_SHOWN) | FAILED |
| OFFERED, SHOWING + | Reject ↓ / Stop ↓ | TERMINATED | — (the phone has the view) | SignalingFailed(cause) | FAILED already |
| SHOWING | /progress (stay) | SHOWING | 200 | SignalingProgress(BEACON, watched) | stays |
| SHOWING | /viewed | VIEWED | 200 AdVerdict | Established(atMs, creative, media kind, watched seconds in `sdp`) | CONNECTING → ACTIVE |
| SHOWING + | stall timer 120 s | TERMINATED | — | SignalingFailed(STALLED) | FAILED |
| VIEWED | /credited | TERMINATED | 200 | ServiceEnd(CREDITED, SUBSCRIBER) | ACTIVE → SUCCEEDED |
| VIEWED | credit window 10 min | TERMINATED | — | ServiceEnd(CREDIT_WINDOW_EXPIRED, TIMER) | ACTIVE → SUCCEEDED |
| VIEWED | Stop ↓ / Reject ↓ (+) | TERMINATED | — | ServiceEnd(cause, SUPERVISOR) | ACTIVE → SUCCEEDED (or FAILED already) |

`AdPlaybackTest` (7): the whole view, then a second view on the pooled machines with clean contexts · a refusal (204 with the cause) ·
no creative (NO_ROUTE) · an abandon (no re-route) · the show / stall / credit-window timers · a Stop in the credit window (settled) · the
supervisor's CONNECTING deadline reaching OFFERED as a Reject, ACTIVE's maximum reaching VIEWED as a Stop (settled on FAILED, never released).

### 3.2 EslCall — the ESL call (`voice/EslCall.java`)

| from | event / command | to | wire out (ESL) | up |
|---|---|---|---|---|
| IDLE | CHANNEL_PARK (first) | INIT | — (the leg waits, parked) | SessionStart(EslFacts: esl host:port, caller, called, source ip, SIP account) |
| INIT | Connect(EslTarget) ↓ | TRYING | `uuid_setvar` ×4 caller id, `target_ip`, `sofia_profile`; `uuid_transfer <uuid> <dest> XML <ctx>` (C10) | SignalingProgress(TRYING, route) |
| INIT | Reject ↓ / Stop ↓ | TERMINATED | `uuid_kill <uuid> <the cause's hangup word>` | SignalingFailed(cause, q850) |
| INIT | CHANNEL_HANGUP (the caller left) | TERMINATED | — | SignalingFailed(ORIGINATOR_CANCEL, 487) |
| INIT | timeout 5 s | TERMINATED | `uuid_kill … NORMAL_TEMPORARY_FAILURE` | SignalingFailed(NO_DECISION) |
| TRYING | CHANNEL_PROGRESS / _MEDIA | RINGING | — | SignalingProgress(RINGING / EARLY_MEDIA) |
| TRYING, RINGING | CHANNEL_ANSWER | ANSWERED | — | Established(party, codec, sdp) |
| TRYING, RINGING | bridge failed (EXECUTE_COMPLETE + cause) | **INIT** | — (the A-leg stays parked) | SignalingFailed(cause, q850) → the supervisor re-routes or rejects |
| TRYING, RINGING, ANSWERED | CHANNEL_BRIDGE (stay) | same | — | SignalingProgress(BRIDGED, other uuid) (C22) |
| TRYING, RINGING | CHANNEL_HANGUP of the A-leg | TERMINATED | `uuid_kill <other leg> ORIGINATOR_CANCEL` | SignalingFailed(ORIGINATOR_CANCEL, 487) |
| TRYING, RINGING | Reject ↓ / Stop ↓ | TERMINATED | `uuid_kill <uuid> <word>` | SignalingFailed(cause, q850) |
| TRYING | timeout 8 s (C11) | TERMINATED | `uuid_kill … RECOVERY_ON_TIMER_EXPIRE` | SignalingFailed(NO_PROGRESS) |
| RINGING | CHANNEL_PROGRESS (stay) | RINGING | — (the window is not re-armed) | SignalingProgress(…) |
| RINGING | timeout 60 s (C11) | TERMINATED | `uuid_kill … NO_ANSWER` | SignalingFailed(NO_ANSWER, 19) |
| ANSWERED | CHANNEL_HANGUP, either leg (C15) | DISCONNECTING | `uuid_kill <the other leg>`; the end built once (cause, CALLER / CALLEE) | SignalingProgress(DISCONNECTING) |
| ANSWERED | Stop ↓ / Reject ↓ | DISCONNECTING | `uuid_kill <uuid> <word>`; end (cause, SUPERVISOR) | SignalingProgress(DISCONNECTING, SUPERVISOR) |
| ANSWERED | timeout MAX_CALL | DISCONNECTING | `uuid_kill … ALLOTTED_TIMEOUT`; end (MAX_DURATION, TIMER) | SignalingProgress(DISCONNECTING, TIMER) |
| DISCONNECTING | CHANNEL_HANGUP_COMPLETE, or 5 s | TERMINATED | — | ServiceEnd(cause, endedBy) |
| DISCONNECTING | CHANNEL_HANGUP / Stop / Reject (stay) | DISCONNECTING | — | SignalingProgress(DISCONNECTING, …) |

`EslCallTest` (7) + `EslCallRestoreTest` (2): an answered call and the next call clean · a busy hop re-routed by a second `uuid_transfer`
on the same machine, then the last busy reaching the caller · RETRY_SAME · a refusal (CALL_REJECTED) · the no-answer deadline (no
re-route) · the caller's hangup while ringing · the budget cut and the machine's maximum · a node dying mid-call (one row, both machines,
the next node finishes it settled once) · a node dying between the settle and the final row (§7).

### 3.3 WifiGateway — the WiFi session (`wifi/WifiGateway.java`)

| from | event / command | to | wire out | up |
|---|---|---|---|---|
| IDLE | the captive login (first) | INIT | — | SessionStart(WifiFacts: vlan gateway:svlan, mac, msisdn, zone, site) |
| INIT | Connect(WifiGate) ↓ | AUTHORIZING | gateway: `open <mac> policy <p> on <gw>` | SignalingProgress(AUTHORIZING, gate) |
| INIT | Reject ↓ / Stop ↓ | TERMINATED | portal: `deny <mac> <cause>` | SignalingFailed(cause) |
| INIT | timeout 5 s | TERMINATED | portal: `deny … NO_DECISION` | SignalingFailed(NO_DECISION) |
| AUTHORIZING | gate opened | ONLINE | — | Established(atMs, mac, policy) |
| AUTHORIZING | gate refused | TERMINATED | portal: `deny … <cause>` | SignalingFailed(cause) — no re-route (ends the machine) |
| AUTHORIZING | device left / Reject ↓ / Stop ↓ | TERMINATED | gateway: `close`, portal: `deny` | SignalingFailed(…) |
| AUTHORIZING | timeout 5 s | TERMINATED | portal: `deny … GATEWAY_TIMEOUT` | SignalingFailed(GATEWAY_TIMEOUT) |
| ONLINE | usage report (stay) | ONLINE | — | SignalingProgress(USAGE, bytes) — ACTIVE ignores it (§10.4) |
| ONLINE | device left | CLOSING | gateway: `close <mac> <cause>`; end (cause, SUBSCRIBER) | SignalingProgress(CLOSING, SUBSCRIBER) |
| ONLINE | Stop ↓ / Reject ↓ | CLOSING | gateway: `close <mac> <cause>`; end (cause, SUPERVISOR) | SignalingProgress(CLOSING, SUPERVISOR) |
| ONLINE | the session's maximum (24 h) | CLOSING | gateway: `close … SESSION_TIMEOUT`; end (SESSION_TIMEOUT, TIMER) | SignalingProgress(CLOSING, TIMER) |
| CLOSING | gate closed (accounting stop), or 5 s | TERMINATED | — | ServiceEnd(cause, endedBy) |
| CLOSING | left / usage / Stop / Reject (stay) | CLOSING | — | SignalingProgress(…) |

`WifiGatewayTest` (5): login → open → online → usage → logout → closed, then the next session clean · a refusal (deny with the cause) ·
a refused gate (no re-route) · the window running out (Stop → close → settled) · the gateway's silence and the session's maximum.

### 3.4 SmsSubmit — the SMS (`sms/SmsSubmit.java`)

| from | event / command | to | wire out | up |
|---|---|---|---|---|
| IDLE | the client's request (first: HTTP body or Kafka record) | INIT | — | SessionStart(SmsFacts: listen ip:port or topic name, user, numbers, segments) |
| INIT | Connect(Smsc) ↓ | SUBMITTING | smsc: `submit_sm <smsc> <from>><to> segments=n` | SignalingProgress(SUBMITTING, smsc) |
| INIT | Reject ↓ / Stop ↓ | TERMINATED | client: `REJECTED <cause>` | SignalingFailed(cause) |
| INIT | timeout 5 s | TERMINATED | client: `REJECTED NO_DECISION` | SignalingFailed(NO_DECISION) |
| SUBMITTING | submit_sm_resp OK | SUBMITTED | client: `ACCEPTED <message id>` | **Established**(atMs, smsc/message id) — the SMS's answer |
| SUBMITTING | submit_sm_resp error | **INIT** | — (the failover is the supervisor's) | SignalingFailed(THROTTLED / INVALID_DESTINATION / …, command_status) |
| SUBMITTING | Reject ↓ / Stop ↓ | TERMINATED | client: `REJECTED <cause>` | SignalingFailed(cause) |
| SUBMITTING | timeout 10 s | TERMINATED | client: `FAILED SUBMIT_TIMEOUT` | SignalingFailed(SUBMIT_TIMEOUT) |
| SUBMITTED | deliver_sm (the receipt) | TERMINATED | smsc: `deliver_sm_resp`; client: `DLR <stat>` | ServiceEnd(DELIVERED / UNDELIVERED / EXPIRED / REJECTED, NETWORK) |
| SUBMITTED | Stop ↓ / Reject ↓ | TERMINATED | smsc: `cancel_sm <message id>` | ServiceEnd(cause, SUPERVISOR) |
| SUBMITTED | the receipt's wait (48 h) | TERMINATED | client: `DLR DLR_TIMEOUT` | ServiceEnd(DLR_TIMEOUT, TIMER) |

So an **accepted** message is always settled (D-A1) and the flow's refund rule decides its charge; a message **no SMSC accepted** is released.
`SmsSubmitTest` (7): delivered + the Kafka door · an undelivered receipt (settled, not released) · a THROTTLED failover to smsc-2 on the
same machine · a terminal refusal reaching the client · a refused admission · no submit answer / no receipt · a Stop while waiting (cancel_sm).

## 4 · The four samples on the base — what each application answers

Each sample is a `RoutesphereFlow` subclass: decisions only, no per-session field (config maps only), no protocol word.

| step | VoiceFlow | AdFlow | SmsFlow | WifiFlow |
|---|---|---|---|---|
| `name()` (= its own table, §L.4) | CallSupervisor | AdSupervisor | SmsSupervisor | WifiSupervisor |
| (a) the root | default: `esl host:port` rule | default: `listen ip:port` rule | default: `listen` or `topic` rule | default: `vlan gw:svlan` rule |
| `buildTask` | numbers, VOICE/ESL | the zone's ad rule (none → NO_RULE); the task (AD) | segments (0 → EMPTY_MESSAGE); the task (SMS) | the subscriber, the site |
| `selectCandidates` | default (SELF) | the rule's campaigns, then the free house ad | default | default |
| (c) `identifyEntryPartner` | by source ip, inside the root | the candidate's advertiser | by user, inside the root | the subscriber's partner [assumes D5] |
| `rateAtLevel` | one minute | the whole view | per segment × segments | one window (a minute) |
| `applyRootRules` | the digit filter (C5) | — | — | — |
| `confirmAdmission` | — | the campaign's quota | — | — |
| (d) `routing` | the root's dialplan by prefix | the creative of `ctx.candidateBeingAdmitted()` (F1) | the root's SMSCs in order | the site's gate |
| `rerouteActionFor` | the v1 table (C12) | default (none) | THROTTLED / QUEUE_FULL / SMSC_SYSTEM_ERROR | default (none) |
| renews (budget child) | by settings: one more minute | no | no | by settings: one more window |
| `delivered` / `settleOutcome` / `refundPolicy` | defaults (answered) | shown; FAILED / CONSUMED / OFFERED_NOT_CONSUMED; REFUND + unshown by config | DELIVERED; accepted-not-delivered = OFFERED_NOT_CONSUMED; refund by config | defaults |
| `billedUnits` | started minutes | null (the view) | null (the segments) | started minutes |
| `buildSdr` | — | AdSdr | — | WifiSdr |

## 5 · The scenarios every sample runs (§H)

| scenario | VoiceFlowTest | AdFlowTest | SmsFlowTest | WifiFlowTest |
|---|---|---|---|---|
| two roots, partner ids 9 / 44 / 701 in both | `two_roots_with_partner_701_in_both…` | `two_roots_the_same_advertiser_id…` | `two_roots_the_http_door_names_btcl…` | `two_roots_each_gateway_and_s_vlan…` |
| 1, 2 and 3 tiers (one record per tier, leaf first) | `one_two_and_three_tiers…` | `a_whole_view_on_one_two_and_three_tiers…` | `delivered_messages_on_one_two_and_three_tiers…` | `a_session_on_one_two_and_three_tiers…` |
| a door no rule names → NO_TENANT | `a_door_no_rule_names…` | `a_zone_no_rule_serves_and_a_door…` | — | — |
| the doorbell per root (§D) | `VoiceFlowDoorbellTest` (btcl reloaded only; the call in the air ends on its snapshot, the next call sees the new tree) | — | — | — |
| partner not found | `refusals_at_admission…` | an advertiser in no tier → the next candidate | `refusals_at_admission…` | `an_unknown_subscriber…` |
| no balance at a middle tier (every reserve back) | `refusals_at_admission…` | `no_balance_at_a_middle_tier…` | `refusals_at_admission…` | `no_balance_at_the_middle_tier_of_three…` |
| routing | `the_dialplan_of_the_root_routes_by_prefix…` (+ NO_ROUTE) | the creative of the candidate | the SMSCs in order | the site's gate |
| rerouting | `a_busy_hop_reroutes…`, `reroutes_stop_at_the_plans_cap…` | none after the hand-over (abandon: no re-route) | `a_throttled_smsc_fails_over…` | none (a refused gate) |
| credit | renewals + exhaustion; ACTIVE's maximum | the whole view; unshown charge / refund; shown-then-abandoned pays | refund on non-delivery by config; DLR_TIMEOUT | the windows + exhaustion |
| the finish (CDR per tier, task, SDR, pools) | CDR + settlements + pools | CDR + task + SDR + pools | CDR + task + pools | CDR + SDR + pools |
| a crash mid-session, restored with its reserves | `a_call_restored_from_the_store_mid_call…` + §7 | `a_view_restored_in_its_credit_window…` | `a_message_restored_while_it_waits…` | `a_session_restored_online…` |

## 6 · The call's C1–C23 — the regression table (routesphere `call-flow-base-pseudocode.md` §1)

| # | the step | the hook in the voice sample / the base | the test that covers it |
|---|---|---|---|
| C1 | IDENTIFY_ENTRY_PARTNER | `VoiceFlow.identifyEntryPartner` (source ip, inside the root) | `one_two_and_three_tiers…`; `refusals_at_admission…` (PARTNER_NOT_FOUND); `two_roots…` |
| C2 | the chain leaf → root | base `iterateResellerTenants` | `one_two_and_three_tiers…` (records 1 / 2 / 3, leaf first) |
| C3 | IDENTIFY_PARTNER[tier] | base `identifyPartner` (`res_<id>`) | `one_two_and_three_tiers…` (res_44 → btcl/44; res_44_7 → res_44/7) |
| C4 | status, authorization, the slot | base `checkPartner`; `VoiceFlow.authorizeBeforeSlot` (the DID); the base's slot; `VoiceFlow.authorize` (the SIP account's cap, `SipAccountSlots`) | `refusals…` (PARTNER_DEACTIVATED before any reserve); `a_partner_with_one_channel…` (CHANNEL_LIMIT_REACHED, the slot back at the end); `the_entry_tier_refuses_an_unknown_did…` (INVALID_DID before anything, RETAIL_CHANNEL_LIMIT_REACHED before any reserve, the account's channel back at the end) |
| C5 | DIGIT_FILTER[root] | `VoiceFlow.applyRootRules` | `refusals…` (DIGIT_FILTER_DENIED after the leaf's reserve, which goes back); the routing test (88 + number in the transfer) |
| C6 | RESERVE_BALANCE[tier] | `VoiceFlow.rateAtLevel` + base `reserveBalance` | `one_two_and_three_tiers…` (one minute per tier at its rate); `refusals…` (INSUFFICIENT_BALANCE at the middle tier) |
| C7 | compensate on refusal | base undo | `refusals…` (the leaf's release); the routing test (NO_ROUTE: both tiers back) |
| C8 | RESOLVE_ROUTE | `VoiceFlow.routing` | `the_dialplan_of_the_root_routes_by_prefix…` (4 hops, GP-trunk first; NO_ROUTE) |
| C9 | spawn the signaling with the route | the kit + `Connect(EslTarget)` | the routing test (the wire of the hop) |
| C10 | TRYING: setvar ×4, target, transfer | `EslCall` INIT → TRYING | the routing test; `EslCallTest.an_answered_call…` |
| C11 | the pre-answer deadlines | `EslCall` TRYING 8 s / RINGING 60 s | `the_ringing_deadline_of_the_esl_machine…`; `EslCallTest.the_no_answer_deadline…` |
| C12 | the re-route policy | `VoiceFlow.rerouteActionFor` + base `rerouteByTable` | `a_busy_hop_reroutes…retries_the_same_hop…` (no second reserve); `reroutes_stop_at_the_plans_cap…` |
| C13 | ACTIVE.entry | `VoiceFlow.onServiceStarted`; the budget child on SessionActive | `one_two_and_three_tiers…` (recording started); the renewal test |
| C14 | the periodic reserve, the tightest tier cuts | base budget child + `VoiceFlow.rateNextWindow` | `a_talking_call_renews_one_minute_per_period…` (Stop(BALANCE_EXHAUSTED) → uuid_kill) |
| C15 | hangup → DISCONNECTING → TERMINATED | `EslCall` ANSWERED / DISCONNECTING | `EslCallTest.an_answered_call…` (the other leg killed, the end built once) |
| C16 | SettleFinal: the duration | base `billedDuration` (fixed once) | `one_two_and_three_tiers…` (durationSec) |
| C17 | the settlement | `VoiceFlow.billedUnits` (started minutes) + base `finalizeTier` | `one_two_and_three_tiers…` (balances); the renewal test (the windows back) |
| C18 | BalanceClosed → SUCCESS / FAILED | base settle copies the settlements | `one_two_and_three_tiers…` (3 settlements). **Deviation**, see §10.3 |
| C19 | SUCCESS.entry: CDR per tier, the slot once | base `finishSession` | `one_two_and_three_tiers…`; `a_partner_with_one_channel…` |
| C20 | FAILED.entry | base release + `Reject` → `uuid_kill` | `refusals…` (every parked leg killed); `reroutes_stop_at_the_plans_cap…` (USER_BUSY to the caller, every reserve back). The no-balance announcement is not in the sample. |
| C21 | the simulator | the base's `simulate(ctx)` (B11) | `the_simulator_walks_the_tiers_with_their_rates…` (admitted with every tier's rate and the route; a closed partner refused; nothing reserved, no CDR, no slot) |
| C22 | CallBridged | `EslCall` CHANNEL_BRIDGE (stay) | `EslCallTest.an_answered_call…` (other uuid; BRIDGED) |
| C23 | ACTIVE's own deadline | statewalk `MAX_DURATION_REACHED` + `Stop` | `the_maximum_duration_stops_the_answered_call…` (settled, never released; the CDR's cause) |

## 7 · The crash harness and §L.1

`bed/CrashingStore` is one node's handle on a shared store. `crash()` cuts the node off now; `dieBeforeTheFinalRowOf(id)` cuts it off at the
moment the session's FINAL row is written — after the final state's synchronous settle and record. Proven twice:

- machine level (`EslCallRestoreTest`): the next node ends the session **again**;
- flow level (`VoiceFlowTest.a_crash_between_the_settle_and_the_final_row…`): the base asks the ledger to settle both tiers a second time
  (**4 asks, 2 moves**), the balance does not move, the CDR is offered twice and billing's view keeps **one record per tier** (2 dropped).

What the store keeps after such a crash is the session's last landed picture: the supervisor ACTIVE and its signaling machine either still
DISCONNECTING, or already TERMINATED (its own write landed just before). In the second case statewalk seats the supervisor **alone**, and
only ACTIVE's own deadline ends it — FAILED, MAX_DURATION_REACHED, the downtime billed as talk time (§L.3). That second end is harmless only
because the money calls and the CDR are idempotent. See §10.2.

## 8 · Mutants (the tests bite)

| mutant | red |
|---|---|
| M1 the testkit ledger settles a (session, tier) twice | 1 (VoiceFlowTest, the crash between the settle and the final row) |
| M2 the voice table forgets USER_BUSY → REROUTE | 2 (the busy re-route, the cap) |
| M3 the ad counts every view consumed | 1 (the unshown refund) |
| M4 the SMS counts every receipt a delivery | 2 (refund by config, DLR_TIMEOUT) |
| M5 the WiFi renews no window | 1 (the windows) |
| M6 the ESL machine ends on a failed bridge | 4 (every re-route of EslCallTest and VoiceFlowTest) |
| M7 AdPlayback reports the screen under another word | 3 (the whole view's SDR, shown-then-abandoned, the restored view) |

Each applied alone at c092e2e (the mutated code is unchanged since), the named test classes run, the file restored by `git checkout` and
checked byte-identical (`git diff --quiet`).

## 9 · The numbers

- Clean copy: `git archive 3bf4340 | tar -x` into a fresh directory, `mvn -o -q test`, nothing else running: **430 / 0 / 0 / 7**
  (the 7 skipped = the reactor's 3 as before + the 4 `@Disabled` R-1 proofs; the report's own commit adds this file only).
- Per module: seed-campaign 72/0/0/0 · seed-config 5/0/0/0 · seed-config-client 5/0/0/0 · seed-context 20/0/0/1 · seed-idgen 19/0/0/0 · seed-routing 33/0/0/2 · seed-sessionflow 143/0/0/0 (stream B's base tests + this stream's 4 testkit tests) · **seed-sessionflow-samples 69/0/0/4** · seed-switch-ledger 50/0/0/0 · seed-tenant 14/0/0/0.
  The samples per class: VoiceFlowTest 15 · VoiceFlowDoorbellTest 1 · EslCallTest 7 · EslCallRestoreTest 2 · RestoreLostUpTest 4 (all skipped: R-1) · AdFlowTest 8 · AdPlaybackTest 7 · SmsFlowTest 7 · SmsSubmitTest 7 · WifiFlowTest 6 · WifiGatewayTest 5.
- The earlier clean copies: 383/0/0/3 at c092e2e, 412/0/0/3 at aa75d11, 414/0/0/3 at 6f71283 (each green).
- Never two Maven builds at once: twice a run beside another Maven build — once my own, once stream B's in its own worktree — turned
  `OrchestrixLedgerTest.aReserveNeverWaitsLongerThanTheAdmissionHasLeft…` red (a timing test of stream B's area, 200 ms budget). Green in
  every run alone; since then every run waits until no other Maven process runs.
- The clean copy of 232ed6a showed ONE red: `SmsFlowTest.a_message_restored_while_it_waits…` read `IDLE/SUBMITTED` instead of
  `ACTIVE/SUBMITTED` right after the node was built. statewalk seats a restored session inside `build()`, but each machine's rehydrate
  runs as the first task on its own chain, so a read at once can still see the pooled supervisor in IDLE (frames are not affected: they
  queue behind the rehydrate). `FlowBed` now waits for the registry to be idle after a restore; the four flow tests ran 3× clean, then the
  reactor and the clean copy again (the numbers above).

## 10 · Findings for the base's owner and the architect

1. **B0's step (d) could not see the candidate being tried** — CLOSED. `route(ctx)` runs inside a candidate's walk, before `ctx.chosen`
   is set, and the base's cursor was package-private; AdFlow kept a copy of its own. B's F1 (c26ba99) added
   `RoutesphereContext.candidateBeingAdmitted()`; T11 (b2c4797) moved AdFlow onto it and dropped the copy.
2. **statewalk, after a crash between the signaling machine's final element and the supervisor's final row** (proved with money in §12,
   R-1): the restored supervisor sits
   in ACTIVE with no signaling machine; nothing sends `ServiceEnd`, and only ACTIVE's own deadline (`activeMaxSec`: hours for a call) ends it,
   as MAX_DURATION_REACHED, the downtime billed as talk time. The money is safe (idempotent per (session, tier)), but the second CDR carries
   another duration and cause; billing must keep the first (it does: by (tenant, uuid)). For the owner / statewalk: should a restore that
   finds "supervisor live, signaling machine final" end the session at once with that machine's last up? (§L.3 is the owner's decision.)
3. **C18 deviation (to rule in P3):** v2 goes SUCCESS only on NORMAL_CLEARING; the 4.0 supervisor goes SUCCEEDED on any `ServiceEnd`
   (BALANCE_EXHAUSTED, the machine's own MAX_DURATION). The CDR carries the real cause; the task follows `taskCompleted` (default
   SUCCEEDED). The voice lane may want `taskCompleted = NORMAL_CLEARING`.
4. **statewalk events carry no application detail:** the ad's `Established` puts the watched seconds in its `sdp` field; `ServiceEnd` has
   no detail, so the WiFi's final bytes stay in the machine (ACTIVE also ignores `SignalingProgress`, so usage reports never reach the flow).
   P4 must pick: the SDR from the machine side, or a detail field on the events (a statewalk change).
5. **NO_TENANT writes no CDR** (no tree to write it on — B counts it `cdrLost`). The samples assert only that nothing is reserved.
6. **The release road charges all or nothing.** A session never answered pays its WHOLE reserve when the refund rule charges its outcome
   (`chargeByTheRefundRule`); `billedUnits` is asked on the settle road only. Right for a per-view ad; for a per-SECOND ad shown and then
   abandoned it charges the whole view, not the seconds watched (the beacons do reach the flow in CONNECTING). For B / the owner.
7. **The C4 hooks run at every tier.** `authorizeBeforeSlot` and `authorize` are asked at each tier of the walk; a lane's entry-tier check
   must test `tier.isLeaf()` itself (the voice sample does).

## 11 · What is left

- **R-1 — the owner's ruling** on an up lost in a crash (§12): the four proofs stay `@Disabled` until then.
- The ad's per-second rate (`billedUnits` = watched seconds) and the SMS's segment-level receipts — hooks exist, the samples do not
  exercise them (and see §10.6).
- The doorbell per root runs in the voice sample only; the other three samples use the static `TenantLookup.of(btcl, ccl)`.
- A persisted lane WITH a budget child (§L.2 refuses a renewal faster than 60 s on a persisting registry, so such a test runs over a minute).
- The architect's merge of B then T, and the suite on the merge head in a clean copy.

## 12 · R-1 — an up lost in a crash: the money cases (T10, the architect's follow-up)

§10.2 showed the harmless case: a crash AFTER the final state's settle, where the ledger's idempotency absorbs the second end. T10 proves
its twins at the flow level: a crash BEFORE the settle, and a crash that loses the answer. There are four proofs in
`voice/RestoreLostUpTest`. Each asserts what the session SHOULD come to, and each is
`@Disabled("R-1: statewalk restore — an up lost in a crash; waits for the owner's ruling")`, so the suite stays green. Each was run
once with the annotation removed; the output is below. Nothing in statewalk or the base was changed.

**How the crash is made.** One switch (`bed/Crash`) is shared by the node's store (`CrashingStore`), its ledger
(`scene/CrashingLedger`) and its CDR road (`scene/CrashingCdrSink`). After it fires, nothing the dead node does reaches the world. A slow
ledger (`holdSettlesUntilTheCrash`) holds the supervisor's settle until the death. A flow held inside ACTIVE's entry (`HeldAtTheAnswer`)
keeps the supervisor's ACTIVE element from landing. To make the money visible, the voice sample bills by the second (C17's rule:
rate / 60 × seconds) at 60.00 a minute at the leaf (one a second) and 30.00 at the reseller.

| case | the row the store kept | restored as | what happens next, today | real | charged | CDR |
|---|---|---|---|---|---|---|
| **(a)** the caller hangs up; the node dies before the settle reaches the ledger | ACTIVE + EslCall TERMINATED | `ACTIVE/-`: the supervisor alone (its machine is final) | nothing sends the end again; ACTIVE's own deadline (6 s in the test) ends it, FAILED | talk 1.004 s | billed **6.001 s**: the leaf **6.00** (honest 1.00), the reseller **3.00** (honest 0.50) | MAX_DURATION_REACHED |
| **(b)** the far end answers; the node dies before the supervisor's ACTIVE lands; the caller talks on | CONNECTING + EslCall ANSWERED | `CONNECTING/ANSWERED` | CONNECTING's deadline (3 s) fails it as never answered: `Reject` down → **`uuid_kill c-r1 NORMAL_TEMPORARY_FAILURE`** (the talking call is cut) → both reserves **released** | talking 3.0 s, then cut | **0.00** | TIMEOUT_CONNECTING, no answer time |
| **(b2)** as (b), but the caller hangs up first | CONNECTING + EslCall ANSWERED | `CONNECTING/ANSWERED` | the restored machine's `ServiceEnd` reaches a supervisor in CONNECTING, which has no rule for it: **lost**; the deadline then releases | talk 0.531 s | **0.00** (honest 0.53) | TIMEOUT_CONNECTING, no answer time |
| **(c)** as (a), with the budget child (period 60 s §L.2, first renewal 2 s B8) | ACTIVE + EslCall TERMINATED + Budget FIRST_RENEWAL | `ACTIVE/-` + Budget FIRST_RENEWAL | the restored child **renews a window for the ended call** (one per tier: 60.00 + 30.00 held), then ACTIVE's deadline (10 s) | talk 1.007 s | billed **10.001 s**: the leaf **10.00** (honest 1.01) | MAX_DURATION_REACHED |

The red lines of the run (the numbers line of each test follows):

```
(a)  …billed_to_the_hangup_not_to_actives_maximum:102         ==> expected: <NORMAL_CLEARING> but was: <MAX_DURATION_REACHED>
(b)  …is_not_cut_and_is_charged_for_its_talk:146 an answered call is not cut by the switch ==> expected: <false> but was: <true>
(b2) …reaches_the_restored_supervisor_and_the_call_is_charged_for_its_talk:192 ==> expected: <NORMAL_CLEARING> but was: <TIMEOUT_CONNECTING>
(c)  …renews_no_window_for_a_call_that_already_ended:242 no window is renewed for a call that ended ==> expected: <0> but was: <2>
```

```
R-1(a) real talk 1.004 s (answer → hangup) · billed 6.001 s · the leaf paid 6.00 (honest: 1.00) · the reseller paid 3.00 (honest: 0.50) · the CDR's cause MAX_DURATION_REACHED · 1 CDR message(s) · the row at the crash: ACTIVE + EslCall TERMINATED · restored as ACTIVE/- · ended 5.0 s after the restore, 6.0 s after the answer · the ledger: [reserve res_44#701 60.00, reserve btcl#44 30.00, settle res_44#701 6.00, settle btcl#44 3.00]
R-1(b) answered, then talking for 3.0 s until the switch ended it · the row at the crash: CONNECTING + EslCall ANSWERED · restored as CONNECTING/ANSWERED · node B's wire: [uuid_kill c-r1 NORMAL_TEMPORARY_FAILURE] · the CDR's cause TIMEOUT_CONNECTING, answer time null, billed 0.0 s · the ledger: [reserve res_44#701 60.00, reserve btcl#44 30.00, release res_44#701 60.00, release btcl#44 30.00] · the leaf paid 0.00
R-1(b2) real talk 0.531 s (answer on node A → hangup on node B) · restored as CONNECTING/ANSWERED · after the hangup CONNECTING/- (the ServiceEnd reached a supervisor in CONNECTING) · node B's wire [] · the CDR's cause TIMEOUT_CONNECTING, answer time null, billed 0.0 s · the ledger: [reserve res_44#701 60.00, reserve btcl#44 30.00, release res_44#701 60.00, release btcl#44 30.00] · the leaf paid 0.00 (honest: 0.53)
R-1(c) real talk 1.007 s · the row at the crash: ACTIVE + EslCall TERMINATED + Budget FIRST_RENEWAL · restored as ACTIVE/- + Budget FIRST_RENEWAL · windows reserved after the hangup: 2 · billed 10.001 s, the CDR's cause MAX_DURATION_REACHED · the leaf paid 10.00 (honest: 1.01) · the ledger: [reserve res_44#701 60.00, reserve btcl#44 30.00, reserve res_44#701 60.00, reserve btcl#44 30.00, settle res_44#701 10.00, settle btcl#44 5.00]
```

The two runs (T10a, then all four) gave the same numbers to within a few milliseconds.

**What it means for money, at production timings.**
- **(a)** The billed time is "answer → ACTIVE's maximum", whatever the talk. With the base's default maximum (3600 s), a 1-second call is
  billed an hour — far beyond its reserve of one minute (plus renewals), so the overrun is debited (C17's negative return). Idempotency
  does not help: the first settle never reached the ledger.
- **(b) and (b2)** An answered call is charged nothing: every reserve is released, so the revenue is lost. In (b) the switch also kills a
  live call at CONNECTING's deadline (the base's default is 120 s).
- **(c)** The restored budget child renews every period for a call that ended — until ACTIVE's maximum (3600 s / 60 s ≈ 60 windows per
  tier). The windows come back at the final settle, but they are held all that time, so they can drain a reseller's balance and refuse
  its other calls. The settle then bills as in (a).
- **What the four share.** The signaling machine's last `up` (its `ServiceEnd`, or its `Established`) was published to the supervisor on
  the dead node. The supervisor's next element never landed. The restore re-seats each machine from its own last landed element, and the
  two disagree. statewalk does not re-send the lost up.

**Ways out, for the owner (nothing built — evidence only):**
1. statewalk: on restore, when the signaling machine's element is past its answer or final while the supervisor's is behind, re-publish
   the machine's last up. The EslCall's context still holds its `ServiceEnd` (`ended`); its `Established` would need keeping the same way.
   The supervisor then answers or ends as it would have.
2. Land the supervisor's own element BEFORE the money in the final entry (a "settling" picture). 4.0's final graph has no such state.
3. The lane, through §L.3's hook (`billedUnits` sees `restoredAtMs`): cap a restored session's billed time at what its machine saw. The
   supervisor's restored context does not carry the machine's end today; the machine's context does.
