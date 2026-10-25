# P1-P · the per-campaign servable check, the pending-root words, two README notes — report (DRAFT)

The brief: `~/arch-drafts/launch/P1-P-seed-followups-brief.md` (the architect, 2026-10-25 13:2x). Branch `p1-follow` from routesphere-flow
a1d4440 (P1-O + the review fix; its clean copy 674/0/0/3). Every Maven run offline and under the shared lock; nothing installed into `~/.m2`;
nothing pushed. State, line by line: `docs/agents/P1-P-state.md`.

## Commits

| item | commit | what |
|---|---|---|
| 1 | f2dd997 | **One campaign's servability without ranking the tenant.** `CampaignService.servable(tenantId, campaignId, view)` (a refusing `default`; `DefaultCampaignService` answers it); `PlacementRanker.mayServe`, the rule written ONCE (rank and servable both call it); `placementOf`, the one rotation step; `LiveCampaign.rotate(judged)` without its own guard; `TenantCampaigns.all()` counted. `ServableTest` 20, `ServableNeverWalksTheTenantTest` 1; 10 mutants red. |
| 2 | be8c15d | **The pending-root line names the real reason.** `BootstrapRoad.loadItsTree`: a `LiveTrees.LaneRefusal` (new, in the SPI) → "the lane `<app>` refused it: `<its words>`"; anything else → "prime-context does not serve its tree yet: `<its answer>`"; both cleaned for one line. `PendingRootTest` +3; 3 mutants red; README §4.2's new bullet. |
| 3 | (this commit) | **Two README notes** (sessionflow §4.0): `byIp` asks the port first with no `:5060` default, and why (against the live switch's order); legacy MySQL trees repeat partner ids, so `byPartnerId` names nobody for a repeated id (D5) — a lane identifies by route key / SIP account / credentials; `partnerAbove` is unaffected. The §9 history line. |

## Numbers

(to come)

## Decisions

(to come)

## Open points

(to come)
