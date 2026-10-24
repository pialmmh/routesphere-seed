# P2 — ad-sphere on RoutesphereFlow (DRAFT — waits for the owner)

Written 2026-10-22 ~05:55 +06 by the architect, unattended (the owner asleep). Not launched. Two questions block it (§0.1).
Source facts: an inventory of ad-sphere at origin/main 755d8c8 (file:line references below are at that commit, under
`src/main/java/com/telcobright/adsphere/`), the P1 reports (routesphere-seed `docs/agents/P1-B-report.md`, `P1-T-report.md`),
the design `routesphere-flow.html` §6–§8, and the morning page `p1-review.html`.

## 0 · Where things are

- **ad-sphere**: origin/main **755d8c8** (Java suite 636/0/0/5 per its merge message). Local main 63c3dcc is an ancestor of it
  (fast-forward only, 77 commits). No production data; test data may be wiped.
- **The base**: routesphere-seed branch `routesphere-flow` (dd0d011 + the P1-F tail), version **2.0.0-SNAPSHOT**, on
  **statewalk 4.0.0** (local tag v4.0.0, not pushed).
- **The template**: routesphere-seed `seed-sessionflow-samples/src/test/java/.../samples/ad/`: `AdFlow` (8 scenarios), the
  `AdPlayback` machine (7 tests), `AdFrames` / `AdChannel` (a fake HTTP wire).

### 0.1 · What blocks the launch

1. **sphere-kit links statewalk 3's `SdrRecord`, which statewalk 4.0.0 no longer has.** sphere-kit 1.0.3 (orchestrix-v2,
   seat B's) has 6 classes in its `sdr` package that use `com.telcobright.statewalk.session.SdrRecord`: `SdrSubmitter.submitAd`,
   `QueuedSdrWriter` (+ `$PendingAd`, `$PendingPayment`), `TransactionEnvelopes`, `fakes/CollectingSdrSubmitter`. ad-sphere's SDR
   road goes through them: the engine's `sdrSink` (`flow/internal/AdCallRegistry.java:280`) → `AdSphereManager.onRecord:460` →
   `submitAd:470` → `TenantRecords:89-92` → `QueuedSdrWriter`; `AdSphereRuntime:132` calls `TransactionEnvelopes.producer`. On
   statewalk 4.0.0 that road fails at link time (`NoClassDefFoundError`).
   Options: (1) **sphere-kit 1.0.4** owns its SDR record type (the SDR leaves statewalk, as 4.0 intended) — seat B's change, or
   ours with seat B's review; (2) ad-sphere writes its SDR through the base's own `SdrSink` and stops using sphere-kit's `sdr`
   package (no other seat touched, but the payment side keeps the old road); (3) a stand-in class in statewalk's package —
   not recommended. **Recommended: (1)**, as P2-0, before the port.
2. **The P1 decisions A1–A5** (`p1-review.html` §2). A1, the ad's unshown rule in `release`, is the lane's money rule.

## A · Branch, pins, the private repository

- Branch `p2-adflow` from origin/main 755d8c8, worktree `ad-sphere/worktrees/p2-adflow` (a second stream: `p2-tests`).
- The pins move together: `seed-*` 1.0-SNAPSHOT → **2.0.0-SNAPSHOT**; statewalk 3.3.0 → **4.0.0** (`pom.xml:32` and
  `tools/cloud/cloud-base.sh:13`, whose `STATEWALK=` cannot be set from the environment — edit it: v4.0.0 = 0b98032,
  `STATEWALK_VERSION=4.0.0`); `SEED=` the routesphere-flow head; `KIT=` the sphere-kit of §0.1 (1).
- Build the pinned base with cloud-base.sh into a **private repository** (`REPO=<scratch>/p2-repo`, the tail
  `~/.m2/repository` only; GIT_ROOTS = `~/telcobright-projects`, so the local commits are found: nothing is on GitHub yet).
  Never install into `~/.m2`.
- rtc-domain carries state-walk v1 (B excluded it in the seed): exclude it here too.
- **Prove the resolved versions** (dependency:tree) before and after: statewalk 4.0.0 only; seed 2.0.0-SNAPSHOT only.

## B · The port: `AdCallFlow` → `AdFlow extends RoutesphereFlow<AdContext>`

22 main files import the 3.x base. The gone classes: SessionFlow, SessionFlowContext, SessionFlowEngine, SessionFlowTimings,
SessionMachine, SessionState, LaunchResult, RequestFacts, SessionFlowKit, SessionFlowSettings. The rest keep their names.

| 3.x (`flow/api/AdCallFlow.java`) | target |
|---|---|
| `resolveTenant` 138 (null; the root comes from the HTTP filters) | (a) `identifySiblingLevelTenants` by `Ingress.listen(ip, port)`; `context/ListenerTenants` moves from `RequestFacts.listen` to `Ingress` |
| `selectCandidates` 200 + `candidateCount` 211 + `useCandidate` 220 | `selectCandidates → List<Candidate>`; the budget cut is the base's (`paidTimeIsOver`) |
| `identifyEntryPartner(ctx)` 292 | (c) `identifyEntryPartner(ctx, candidate)` — the inversion (the candidate's advertiser) |
| `rateAtLevel(ctx, Tenant, Partner, int)` 324 | `rateAtLevel(ctx, Tier, Partner)` (`TierAdRating`, per view or per second) |
| `confirmAdmission(ctx, StepMode)` 407 | `confirmAdmission(ctx, Candidate)`; **check `ctx.dryRun` before the quota** |
| — (no route step today) | (d) `route(ctx)`: `RoutePlan.one(creative of ctx.candidateBeingAdmitted())` |
| `rerouteActionFor(protocol, cause)` 544 | (f) `rerouteActionFor(cause)` = FAIL_TERMINAL (never a second creative after the hand-over) |
| `onActive(ctx, machine)` 533 (opens the credit window: `EvCreditWindowOpen`) | the window is AdPlayback's VIEWED state; `onServiceStarted` keeps the rest |
| `settlesAsync` 551 = false | gone: the final state settles, synchronously |
| `billedDuration` 598 = watched seconds | the same hook; `billedUnits` = watched seconds on a per-second rate, null (= the view) on a per-view rate |
| `timeoutCause` 612 (ADMITTED → NOT_SHOWN) | AdPlayback's own timers say NOT_SHOWN / STALLED / CREDIT_WINDOW_EXPIRED |
| `defineRoutes` 511, `startSignaling` 522 | gone: the engine builder registers AdPlayback |
| `taskCompleted` 602, `taskCause` 606, `cdrCause` 617, `payerWhenUnknown` 621, `fillCdr` 627, `onEnded` 638, `buildSdr` 645 | the same hooks, taking `Outcome` |

**Where the ad's answer is — the owner rules (p1-review.html P2-2):** today `AdView.shown()` sets the CDR's answer time
("shown is the call's answer") and a view shown and then abandoned ends **SUCCEEDED** with its task FAILED (`AdCallFlow.java:602`
javadoc). The design page and T's sample put `Established` at `/viewed`, so that view ends **FAILED** and pays its whole reserve
through the release road. Recommended: (b) `Established` at `/shown` — ACTIVE covers the screen and the credit window,
`/viewed` is a progress report (`completed`, the watched seconds), every shown view settles on its watched seconds, outcomes
as today; a view handed over and never shown stays on the release road under the unshown rule. §H's regression table shows
every difference that remains.

## C · AdPlayback: one signaling machine [assumes A5 = D1]

AdView (REQUESTED → SHOWING → VIEWED / ABANDONED), AdStream (WAIT_PLAY → PLAYING / STALLED / CLOSED_UNUSED) and AdCredit
(WAIT_VIEW → OPEN → CREDITED / EXPIRED / CLOSED_UNUSED) become ONE `AdPlayback` (IDLE · INIT · OFFERED · SHOWING · VIEWED ·
TERMINATED), from T's sample and the design's §6 table: IDLE first, an `up` on every transition, Reject and Stop in every live
state. `noteInTheAir` (AdView:98,113) moves with it. The machine's events stay ad-sphere's (`flow/AdEvents`).

## D · The HTTP channel adapter

The roads of `AdSessionResource` (`/api/v1/public/wifi/ad-sessions`): start, shown, progress, viewed, credited, abandoned,
verdict. Each road becomes one frame with the session id → `RoutesphereEngine.deliver(sessionId, delivery)`; the road's answer
(200 AdSessionView, 204 X-Ad-Reason, 200 AdVerdict) comes from the machine's wire-out. The waits move as they are:
`awaitDecision` → `awaitSettled` + `stateOf`; `awaitCreditWindow` → the machine's VIEWED; `contextOf` → `sessionOf`;
`forceEnd` (a `ServiceEnd` posted by hand) → a `Stop` down. `AdCallRegistry.hasEnded:143-147` drops DEFERRED and
`pastAdmitting:150-153` drops PREPROCESSING (statewalk 4.0 has neither).

## E · Recovery: the journal [assumes A4]

The journal of views in the air stays (`SessionJournals.file(<cdr.dir>/views-in-the-air.jsonl)`, `AdSphereRuntime:186-192`);
`handOver` (AdCallRegistry:137) and `publishWhatWasLeftInTheAir` (now in `RoutesphereEngine`'s constructor) as today.
statewalk persistence stays OFF (P1-F F3 refuses the two together).

## F · Money: the rules do not change

The whole view (or the seconds) reserved at each tier's own rate; the quota after every tier; the unshown rule by config, in
`release` [assumes A1]; every money call idempotent per (session, tier). The dry run (the admin routing preview,
`AdRoutingAdminResource:406` → `simulate`) reserves nothing and claims no quota.

## H · Tests

- **Baseline first**: the Java suite on a clean copy of 755d8c8 before any change (expect 636/0/0/5).
- 38 of 169 test files import the 3.x base; 37 test classes reach the session (the inventory's list). Port them; a test that
  pinned a 3.x shape follows the new shape and the commit says which.
- **The ad regression table** (like the call's C1–C23): for every ad scenario of today's story tests — the session outcome,
  the task state and cause, the CDR's fields per tier, the SDR, the money per tier — before (755d8c8) and after. Equal, or
  the difference named and ruled (the shown-and-abandoned view above is the first).
- ITs (`AdCallOnPostgresAndKafkaIT`, `ChainStoryIT`) only in a lab the stream starts and removes — never 127.0.0.1:7433, never
  the LXD MySQL. A road that cannot reach its service is reported, not fixed.
- Mutants on the money rules and the roads; the clean copy of the head; the architect's suite on the merge head.

## I · Out of scope

No bed, no deploy, no push. Layer 1 (the task row minted before any machine, the runner) as it is. The TS side
(ad-manager) untouched: no road changes its shape. No other seat's repository without the owner's word (sphere-kit = §0.1).

## J · Streams (when launched)

- **P2-0** (first, short): sphere-kit 1.0.4 per §0.1, or the owner's other option.
- **A (the port)**: §A, §B, §C, §D, §E, §F — commit `P2 A0:` (the pins + the new classes compiling, the old ones deleted) early.
- **T (the tests)**: the baseline, the port of the test files on top of A0, the regression table, the ITs in a lab.
- The architect merges A, then T, runs the suite on the merge head in a clean copy, and reports.
