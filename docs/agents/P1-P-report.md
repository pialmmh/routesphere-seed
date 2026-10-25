# P1-P · the per-campaign servable check, the pending-root words, two README notes — report

The brief: `~/arch-drafts/launch/P1-P-seed-followups-brief.md` (the architect, 2026-10-25 13:2x; the night's follow-ups). Branch `p1-follow` from
routesphere-flow a1d4440 (P1-O + the review fix; its clean copy 674/0/0/3). Every Maven run was offline and under the shared lock. Nothing was
installed into `~/.m2` and nothing was pushed. State, line by line: `docs/agents/P1-P-state.md`.

## Commits

| item | commit | what |
|---|---|---|
| 1 | f2dd997 | **One campaign's servability without ranking the tenant.** `CampaignService.servable(tenantId, campaignId, view)` → `Optional<Placement>`: a refusing `default` on the interface; `DefaultCampaignService` answers it. `PlacementRanker.mayServe` holds the rule, written ONCE; `rank` and `servable` both call it. `placementOf` is the one rotation step. `LiveCampaign.rotate(judged)` lost its own guard. `TenantCampaigns.all()` counts its walks. `ServableTest` 20, `ServableNeverWalksTheTenantTest` 1; 10 mutants red. |
| 2 | be8c15d | **The pending-root line names the real reason.** `BootstrapRoad.loadItsTree`: a `LiveTrees.LaneRefusal` (new in the SPI) gives "the lane `<app>` refused it: `<its words>`"; anything else gives "prime-context does not serve its tree yet: `<its answer>`". Both are cleaned to one line. `PendingRootTest` +3; 3 mutants red; README §4.2's new bullet. |
| 3 | 5971206 | **Two README notes** (sessionflow §4.0): `byIp` asks the port first with no `:5060` default, and why (against the live switch's order); legacy MySQL trees repeat partner ids, so `byPartnerId` names nobody for a repeated id (D5). A lane identifies by route key, SIP account or credentials; `partnerAbove` is unaffected. The §9 history line. |

## Numbers

- **Base a1d4440**: 674/0/0/3 (run/failed/errors/skipped; the architect's clean copy).
- **On the way** (the worktree's whole reactor from clean): item 1 **695/0/0/3**, item 2 **698/0/0/3**.
- **Head 5971206** (every item; the report's commit adds docs only), in ONE lock hold 13:56:03–14:01:10: the worktree from clean
  **698/0/0/3** (2:36 min), and a git-archive clean copy of 5971206 **698/0/0/3** (2:28 min). Offline, no install: `~/.m2` holds no seed
  2.0.0-SNAPSHOT.
- **statewalk 4.1.0 only** (`dependency:tree` on the clean copy): seed-sessionflow directly; seed-bootstrap-config, seed-tenant and
  seed-switch-ledger through seed-sessionflow; seed-sessionflow-samples in test scope. No state-walk v1, no statewalk-v2.
- **The named proofs, on the clean copy**: the R-1 proofs `RestoreLostUpTest` 4/0/0/0; `HotPathHasNoListScanTest` 2/0/0/0;
  `StatewalkPinTest` 2/0/0/0; `EslCallRestoreTest` 2/0/0/0; `ServableTest` 20/0/0/0; `ServableNeverWalksTheTenantTest` 1/0/0/0;
  `CampaignServiceTest` 12/0/0/0 (unchanged); `PendingRootTest` 7/0/0/0. The worktree's run reads the same.
- **+24 tests, all new**: ServableTest 20 (16 table rows + 4), ServableNeverWalksTheTenantTest 1, PendingRootTest +3.
- **Existing tests touched**: none changed. The test fake `FakeLiveTrees` gained `laneRefuses` (a root → the lane's words).
- **Red first**: item 1 by compile (no `servable`, no `campaignWalks`). Item 2 ON BEHAVIOUR: with the type and the tests in place and the road
  unchanged, PendingRootTest went 7/2, and the line blamed prime-context for `ROOT_DOOR_UNBOUND` — the bug itself. Item 3 is docs only.
- **Mutants: 13, 13 red**, each restored with a fresh mtime (cp + touch, then compared with the saved original):
  - item 1 (10): servable's own copy of the rule without runnable (6 table rows), without the targeting (2), without the quota (1),
    without the cap (3: a row, the capped test, the rotation test), without the creatives (1 row, an error: a rotation over no creative);
    servable rotating on an empty answer; answering without rotating (2); servable by ranking the tenant (the neighbour's rotation test
    and the walk test); servable walking to the campaign without ranking (the walk test alone); no tenant check.
  - item 2 (3): no classification (the 2 lane tests); every failure blamed on the lane (the tree-fetch test and P1-G's own pending test);
    the lane's words not cleaned.
  - One illustration, not counted: the OLD `rotate()` guard put back + servable without the creatives predicate is GREEN, the equivalent
    mutant (decision 4).

## Decisions (defendable defaults)

1. **`servable` is a refusing `default` on the interface.** ad-sphere's test double `NoWalkPerRequestStoryTest.CountedRanking` implements
   `CampaignService`, so an abstract method would break its test compile at the seed bump. A default that ranked the tenant would be the
   walk itself and would rotate every campaign — the opposite of the item. So the default throws `UnsupportedOperationException` with its
   reason, and `DefaultCampaignService` answers.
2. **The `tenantId` argument must be the view's own tenant**, or the call is refused (`IllegalArgumentException`, both tenants named). The
   claim (`takeOne`) finds the campaign under the VIEW's tenant, and campaign ids repeat across tenants: a placement answered from another
   tenant's campaign could be claimed on the wrong campaign.
3. **The rule is the loop body extracted once** (`mayServe`, package-private, the brief's five predicates in the ranker's order), and the
   rotation is one step (`placementOf`). The row is read ONCE, so every predicate and the placement see the same row. Before, the placement
   re-read `live.row` after the sort: a reload in between could pair the new row (say, now Paused), or the new row's creative, with the old
   row's judgement. `rank`'s order reads the judged row too, so the comparator stays stable under a concurrent reload. Only that race changes.
4. **`LiveCampaign.rotate(judged)` lost its own emptiness guard.** With the guard, "servable without the creatives predicate" answered empty
   anyway: an equivalent mutant (the illustration is green). The guard was a second copy of the fifth predicate, and it hid that predicate.
   Now the rule is the only test, and a rule that forgot it fails loudly (an `ArithmeticException` in the rotation), not silently.
5. **The no-walk proof counts `TenantCampaigns.all()`** (a `LongAdder`, read by the package-private `DefaultCampaignService.campaignWalks`).
   `HotPathHasNoListScanTest` does not scan seed-campaign: its scope is identification (seed-sessionflow, seed-tenant, seed-routing), and its
   pattern would not see `tenant.all()` (not one of its five names). So servable's "a get by id, no walk" is proven by its own test (1 001
   checks over 50 campaigns: 0 walks; one rank: 1). The guard stays green on the head; item 2 adds no walk in its modules (a try/catch).
6. **Item 2's contract is a TYPE, not words.** The road cannot tell a lane's refusal from a failed fetch on its own: a lane's own LiveTrees
   (ad-sphere's `AdTrees`) throws plain RuntimeExceptions for both. So the lane marks its own refusals with `LiveTrees.LaneRefusal` (a nested
   RuntimeException, the house form of `LedgerPort.LedgerRefusal`). Anything untyped stays the tree's failure: the seed's own trees' failures
   and a lane's tree-load failures ARE fetch failures, so the old words stay right for them. A refusal must be thrown as it is: a wrapped one
   reads as the tree's (no cause-chain search — a simple contract, said in the javadoc and the README).
7. **Both reasons are cleaned** (`LogText.failureText`: one line, control characters escaped, at most 1 000 characters): a lane's words
   could carry a line break and forge a line of their own.

## Open points

1. **ad-sphere adopts both** (P2's own round; nothing in ad-sphere was touched):
   - its gate's `theLibraryWouldServe(c)` (`AdCandidates.Gate`, 54d9947) → `campaigns.servable(tenantId, c.id(), view).isPresent()`, once per
     capped campaign. Its `CountedRanking` double should override `servable` (delegate and count); the default refuses.
   - `AdTrees.serve` should throw `LiveTrees.LaneRefusal` for its own refusals — `ROOT_DOOR_UNBOUND` (`AdListeners.openForRoot`) and
     `TwoRootsNeedDoors.require` — as they are. Until then its pending line still says "prime-context does not serve its tree yet:
     ROOT_DOOR_UNBOUND: …".
2. **The rotation is a side effect of the check**, as the brief rules: an answered placement moves the campaign's rotation on by one. Today the
   gate's `rank(view)` moves EVERY servable campaign's rotation once per capped view; `servable` moves only the campaign asked, and only when
   it answers. If the ad lane wants a check that never rotates, that is another method (not built).
3. seed-campaign has no README: `servable` is documented in `CampaignService`'s javadoc and in the sessionflow README's history line.

## What P2 (ad-sphere) must know

1. `CampaignService.servable(tenantId, campaignId, view)`: the same answer as `rank(view)` for that campaign, one get by id, no walk. Pass the
   view's own tenant. It rotates like `rank` on a placement, never on an empty answer.
2. `LiveTrees.LaneRefusal(words)`: throw it from the lane's own `LiveTrees.serve` for a refusal of its own. The road's pending line then says
   "the lane ad-sphere refused it: <words>".
