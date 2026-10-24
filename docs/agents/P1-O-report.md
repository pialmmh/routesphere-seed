# P1-O · the route layer for identifyPartner, and no list scan on the hot path — report

DRAFT (written early, finished last). The brief: `docs/agents/P1-O-brief.md` (the architect's brief with the addendum's section A, which
wins where they differ). Branch `p1-follow` from routesphere-flow f7539a1 (P1-N merged; its clean copy 640/0/0/3). Every Maven run
offline and under the shared lock; nothing installed into `~/.m2`; nothing pushed. State, line by line: `docs/agents/P1-O-state.md`.

## Commits

| item | commit | what |
|---|---|---|
| 1 | ed1d5ce | One index per snapshot, never per session (A1/A2): `RootIndex` built by one walk at the snapshot's install, carried on seed-context's `Snapshot`; `RootTreeInstaller` counts `rootIndexBuilds`; served roots by maps. |

## Numbers

- Base f7539a1: 640/0/0/3 (the architect's gate).
- Item 1: the whole reactor from the worktree 650/0/0/3.

## Decisions

1. **A name is exact (A2).** `byPartnerName` and the name road of `partnerAbove` are one get on an exact key; before P1-O both matched
   without case (a loop). Two existing assertions of `IdentifyPartnerTest` moved to the exact rule.

## Open points

(to be finished)

## The rtc-domain jar

The seed compiles against `~/.m2/repository/com/telcobright/rtc-domain/1.0-SNAPSHOT/rtc-domain-1.0-SNAPSHOT.jar` (2026-10-07 00:18). It
matches the source read on routesphere main (`rtc-domain/src/main/java/…`): javap shows the same fields and getters of `DynamicContext`,
`Route`, `RouteMetadata`, `Partner`, `Tenant`, and `DynamicContextDeserializer.wireRouteMetadataBackRefs` + `wirePartner`; no commit touched
those files after 2026-10-06.
