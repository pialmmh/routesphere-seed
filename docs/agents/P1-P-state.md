# P1-P · the per-campaign servable check, the pending-root words, two README notes — state

The brief: `~/arch-drafts/launch/P1-P-seed-followups-brief.md` (the architect, 2026-10-25 13:2x; follow-ups of the night). Branch `p1-follow`
at a1d4440 (= routesphere-flow: P1-O + the review fix; its clean copy 674/0/0/3). Rules: `~/arch-drafts/launch/RULES-2026-10-25.md` + the lane
brief §0/§A (statewalk 4.1.0 only). Items 1 → 3 in order.

One line per item, newest last. Times are Dhaka (+06), each read with `date` before it was written.

- 2026-10-25 13:26 · START — worktree `p1-follow` at a1d4440, clean. Read: the rules, the brief, the lane brief §0/§A/§K/§L, P1-O's report
  and state, seed-campaign (`CampaignService`, `DefaultCampaignService`, `PlacementRanker`, `TenantCampaigns`, `LiveCampaign`,
  `RunnableRule`, `FrequencyCap`, `Targeting`, `CampaignServiceTest`), seed-sessionflow's `BootstrapRoad`, `PendingRoots`, `LiveTrees`,
  `TreeGate`, `TiersFirst`, `PendingRootTest`, `BootstrapWorld`, `FakeLiveTrees`, seed-tenant's `LiveTenantTrees`, the README §4/§4.0/§4.2,
  `HotPathHasNoListScanTest` + the allow-list. Read-only at their source: ad-sphere 54d9947 `AdCandidates.Gate.theLibraryWouldServe` (the
  `rank(view)` per capped view), `AdTrees.serve` + `AdListeners.openForRoot` (ROOT_DOOR_UNBOUND, TwoRootsNeedDoors: thrown as plain
  RuntimeExceptions), `NoWalkPerRequestStoryTest.CountedRanking` (a test double that implements `CampaignService`); routesphere main
  `IdentifyEntryPartnerStep.matchRouteKey` (the bare key, then `key:<port seen>`, then `key:5060`). `~/.m2` holds no seed 2.0.0-SNAPSHOT.
  Running: item 1.
- 2026-10-25 13:38 · **item 1 done**: one campaign's servability without ranking the tenant. `CampaignService.servable(tenantId,
  campaignId, view)` → `Optional<Placement>`. It is a `default` that refuses (`UnsupportedOperationException`), because ad-sphere's test
  double `CountedRanking` implements the interface and must keep compiling. `DefaultCampaignService` answers it: one get of the tenant, then
  `PlacementRanker.servable`, which is one get of the campaign by id (`TenantCampaigns.get`). A view of another tenant is refused
  (`IllegalArgumentException`), because the claim takes the campaign of the VIEW's tenant. `PlacementRanker`: the loop body is extracted ONCE
  into the package-private `mayServe(tenant, live, view)` → `Optional<Hit>` (runnable by `RunnableRule`, specificity ≥ 0, `quotaLeft`, the
  per-device cap, creatives present; the row read once). `rank` asks it of every campaign, `servable` of one. The rotation step
  `placementOf(hit)` is the same for both: the next creative of the row the rule judged, rotation +1. `LiveCampaign.rotate(judged)` lost its
  own emptiness guard: it was a second copy of the fifth predicate and made that predicate's mutant equivalent (shown below). `rank`'s order
  reads the judged row. `TenantCampaigns.all()` counts its walks; `DefaultCampaignService.campaignWalks` (package-private) reads them for the
  proof. Red first by compile (no `servable`, no `campaignWalks`). Green: `ServableTest` 20 (the table, 16 rows on two services fed alike: rank
  and servable answer the same placement or none, for every predicate: open/specificity 2, paused, complete, expired, not started, schedule
  ended, outside the time band, another zone, a site not allowed, the one bought view live, two bought one live, capped device, another
  device, once under the cap, no creatives; a capped campaign → empty, the cap's device key case-blind; the rotation: a placement takes the
  next creative and rank goes on with the same rotation, two empty answers rotate nothing; servable rotates only its campaign while rank
  rotates every campaign it ranks; unknown campaign / tenant → empty, a view of another tenant refused) and `ServableNeverWalksTheTenantTest`
  1 (1 001 one-campaign checks over 50 campaigns: 0 walks; one rank: 1 walk). The existing `CampaignServiceTest` is untouched, 12/0. Mutants:
  10, all red, each restored with a fresh mtime (cp + touch, compared): servable's own copy of the rule without runnable (ServableTest 6
  rows), without the targeting (2 rows), without the quota (1 row), without the cap (3: a row, the capped test, the rotation test), without
  the creatives (1 row, an error: the rotation over no creative); servable rotating on an empty answer (the rotation test); answering
  without rotating (2); servable by ranking the tenant (the neighbour's rotation test, and the walk test); servable walking to the campaign
  without ranking (the walk test alone); no tenant check (1). The illustration "the old rotate guard back + servable without the creatives
  predicate" is GREEN, the equivalent mutant: that is why the guard went. The whole reactor from clean in the worktree: **695/0/0/3**
  (13:35–13:38; base 674, +21). Item 2's started files were stashed for that run. Next: item 2.
