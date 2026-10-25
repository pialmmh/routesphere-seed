# P1-O · the route layer for identifyPartner, and no list scan on the hot path — report

The brief: `docs/agents/P1-O-brief.md` (the architect's brief with the addendum's section A, which wins where they differ; B5 for the
testkit). Branch `p1-follow` from routesphere-flow f7539a1 (P1-N merged; its clean copy 640/0/0/3). Every Maven run offline and under the
shared lock (queued behind the other agents' holds, never broken); nothing installed into `~/.m2`; nothing pushed. State, line by line:
`docs/agents/P1-O-state.md`.

## Commits

| item | commit | what |
|---|---|---|
| 1 | ed1d5ce | **One index per snapshot, never per session** (A1/A2). `api/RootIndex` — byPartnerId · byPartnerName (exact) · byRouteKey (routeWisePartners + ipOrDomainWiseRoute, as served) · byRouteUsername → `RouteEntry(tier, metadata, route, partner)` · bySipAccount · byPrefixLength · heldByTwo — built by ONE walk (`RootIndexBuild`) in `RootTree`'s constructor; `RootTree.of` package-private; the install step `RootTreeInstaller` counts `rootIndexBuilds`. seed-context: `Snapshot` carries its `index`, `SnapshotIndexer`, the cell's swap builds it first. seed-tenant: `RootInstall` installs every loaded tree eagerly; `TenantTrees.snapshot` = the installed instance; served roots by code and db name in maps. `TenantLookup.rootIndexBuilds`, `TenantLookup.of` installs at its construction, the default `snapshot` installs each tree object once; `RoutesphereKit.rootIndexBuilds`, `PoolStats.rootIndexBuilds`. |
| 1b | e64e307 | **The index on the SERVED tree** (prime-context's PC-0025 finding): `ServedTreeRoundTripTest` — a testkit tree serialized as prime-context serves it (a row's route `@JsonIgnore`), read back by `Trees.parse`, indexed: credentials, address and SIP account still name the right tier and the tier's own partner. No main-code change (see decision 13). |
| 2 | c640437 | **The helpers in `Lookups`** (A3–A6): `byRouteCredentials` / `routeOfCredentials` / `bySystemId` (one cause `CREDENTIALS_REJECTED`), `byIp(ip)` · `byIp(ip, port)` (the port first, no `:5060` default), `byDomain`, `bySipAccount`, `byPrefix`, `routesOf`; the flow's final delegates; `AdmissionChain` refuses a null leaf with the recorded cause; `internal/Secrets` (PBKDF2 `{pbkdf2}…` or the legacy plaintext, constant time); testkit `routeCredentials` (B5, hashed). |
| 2b | 31ae409 | **Route credentials are MACHINE secrets** (the architect's A4′, amending A4 after review): `{sha256}<salt>$<SHA-256(salt ‖ secret)>` written (one SHA-256 per check), `newMachineSecret()` / `hashMachineSecret()` for the writers, `{pbkdf2}` verified but written by nothing, one decoy compare for an unknown user name; the testkit writes `{sha256}`. |
| 3 | 7c7f438 | **The scans gone** (A7): `partnerAbove` / `resellerPartnerOf` = the id against the parent's partners, and only on a miss the EXACT name through the session's index (the tier carries it); seed-routing's `PrefixTable` + `DialplanSource.prefixTableOf`, the router's longest match by per-length gets. `ArmedTree` + `NoWalkAfterInstallTest`. |
| 4 | 513dc30 | **The guard** (A8 b): `HotPathHasNoListScanTest` + `docs/agents/hot-path-allowlist.txt` (37 entries, each with its reason). |
| 5 | a6b84cd | **The samples use the helpers**: voice `byIp(sourceIp, port)` then `bySipAccount`; the SMS HTTP door `byRouteCredentials` (a refusal → 401), the Kafka door `byPartnerId` (the campaign names it); the World's trees carry the served routes, SIP account and credentials. |
| 6 | 35c726e | **The tree road sends the bootstrap's bearer** (A9). |
| 7 | 998d670 | **Docs**: the sessionflow README §4.0, the seed-tenant README, R2's names, this report, the state file. |

## Numbers

- Base f7539a1: 640/0/0/3 (the architect's gate).
- Item 1: the whole reactor from the worktree 650/0/0/3; item 2: 660/0/0/3; item 3: 662/0/0/3.
- (the final head's numbers, worktree and clean copy, below when run)

## Decisions (defendable defaults, the owner asleep)

1. **A name is EXACT (A2).** `byPartnerName` and the climb's name road are one get on the exact name; before P1-O both matched without
   case, by a loop. Moved to the rule: two assertions of `IdentifyPartnerTest` and one of `ResellerPartnerRuleTest` (named in the commits).
   If the owner wants case-blind names back, a folded key gives it with one get and no loop.
2. **A key two holders share names nobody — for every map.** A2 says "seen in two tiers"; the index also treats a key that names two
   different partners of ONE tier (a route key whose two served maps disagree, a name two partners of one tier share) as nobody — the
   fail-closed reading, and what `byPartnerName` did before.
3. **The counter lives on the install step, the kit reads it.** A1 asks for "a LongAdder on the kit"; the trees are made and started before
   the kit exists (the bootstrap road and every hand-made kit), so the LongAdder is the install step's (`RootTreeInstaller`) and
   `RoutesphereKit.rootIndexBuilds()` / `PoolStats.rootIndexBuilds` read it — the same number: roots × loads.
4. **The index is carried on seed-context's `Snapshot` generically** (`SnapshotIndexer`, `Snapshot.index()` as `Object` with a typed
   `index(Class)`): seed-context knows no `RootTree` (it is seed-sessionflow's, and seed-sessionflow does not depend on seed-context).
5. **A lane's own lookup** (ad-sphere's `AdTenantLookup` uses the default `TenantLookup.snapshot`) gets each tree object installed ONCE
   (`installOnce`, keyed by the root's name and the tree's identity) — lazily, at the first session of a new tree: the default has no
   install step to be eager in. seed-tenant's trees and `TenantLookup.of` install eagerly.
6. **A user name's route without its back reference** is found among the tier's routes by the row's id (`partnerVsRoutes`, `idVsRoute`,
   `ipOrDomainWiseRoute`) — prime-context serves `route_metadata.route` as `@JsonIgnore`; the index does not depend on the deserializer's
   wiring. Checked for the brief's item 2: `Trees.parse` goes through rtc-domain's `DynamicContextDeserializer`, which already re-wires
   `route.partner` to the SAME instance as `partners` for the routes of `partnerVsRoutes`, `ipOrDomainWiseRoute` and the user names'
   routes (`wireRouteMetadataBackRefs` + `wirePartner`) — nothing added; the index reads `partners.get(route.idPartner)` anyway (A2).
7. **A4′ (the architect's amendment after review): machine secrets, a fast salted hash, a decoy.** A route's credentials are secrets the
   system generates (`Secrets.newMachineSecret()`: 32 random bytes, base64url) and stores as `{sha256}<salt>$<SHA-256(salt ‖ secret)>`
   (`hashMachineSecret`); one check costs microseconds (measured below). `{pbkdf2}` stays verifiable but has no writer any more: item 2's
   public `Secrets.hash(char[])` is gone (unpushed; P2f should not have used it), its form only a package-private `pbkdf2(…)` for the
   compatibility vectors. An unknown user name does one decoy `{sha256}` compare (`compareWithNobody`, counted `decoyCompares`) — the same
   work as a wrong secret.
8. **The testkit writes the `{sha256}` form** (`routeCredentials(partner, routeName, user, secret)`); `legacyRouteCredentials` a plaintext
   row; `routeCredentialsStoredAs` any value a test pins (the `{pbkdf2}` compatibility row in `RouteLayerHelpersTest`).
9. **seed-routing's prefix index is the source's to keep.** `DialplanSource.prefixTableOf` builds a `PrefixTable` from what `prefixesOf`
   hands, per request, unless the source keeps one per load; the router itself does gets only. ad-sphere's indexed source hands only the rows
   that can match, so its per-request table is a handful of rows.
10. **The guard's pattern is A8's with the five names matched without case** — as written (lower case) it would not see `getPartners()`, so
    not the climb's old loop; `for` followed by any spaces.
11. **The 1 000-session proof counts per root.** `OneIndexPerSnapshotTest` serves btcl AND ccl (the World's identification rules name
    both; a kit refuses a rule naming an unserved root), so the trees read 2 builds after the load (one per root), still 2 after 1 000 calls
    on btcl's snapshot, 3 after btcl's reload — A8's "1 then 2" per root. seed-tenant's `IndexPerSnapshotTest` reads the same on the trees
    alone.
12. **The voice sample's park carries the source port** (`EslFrame.park(…, sourcePort, …)`, `EslFacts.sourcePort`; 0 = not captured →
    the bare ip): A5's "the voice lane passes the port it saw". The SMS sample's password is `@JsonIgnore` in its facts (never in the
    persisted session row) and out of every record's text.
13. **The served-tree finding needed a test, not a fix.** Item 1's `RootIndexBuild` already resolved a user name's row to its route by the
    row's id among the tier's routes (`partnerVsRoutes`, `idVsRoute`, `ipOrDomainWiseRoute`, built once per tier when the tier has user
    names) and skipped an unserved row with one WARN; the testkit's rows never carried a back reference, so every credentials test already
    ran that road. rtc-domain's deserializer (in the seed's jar) also wires a row's route from `partnerVsRoutes` on parse. So
    `ServedTreeRoundTripTest` is GREEN on the head before it — not red as the finding expected; it is red under the mutant that keeps only the
    back reference (2 of 3, with `RouteLayerHelpersTest` 3 of 5), and its fourth wire (routes only in `idVsRoute`) proves the index's own
    road: the parsed row has no route there. If prime-context's rig still finds no partner, its tree likely serves `usernameVsRouteMetaData`
    without the ROUTES group (no route of the row is on the wire at all) — then the row is left out with a WARN naming it, and the fix is
    prime-context's load profile (PC-0025 C1).

## Open points

1. **Closed by A4′** — the per-request PBKDF2 (98 ms a check here) and the unknown-user timing: the written form is `{sha256}` (1.9 µs p50),
   and an unknown user name does the same one SHA-256 against a decoy. Rows already stored as `{pbkdf2}` (none written by the seed) still
   cost ~0.1 s a check until rewritten.
2. **The legacy plaintext rows** (the SMS lane's `route_metadata.extra2`) stay plaintext at rest — compared in constant time, but readable
   by anyone who reads the table; moving them to `{sha256}` is the SMS lane's own round (the rotation means new secrets for its clients).
3. **prime-context** must serve the route maps to the lanes that use them: its `THE_AD_LANE` load profile lacks `ROUTE_METADATA` and
   `IP_ROUTES` (PC-0025 item 2), and it may close its tree roads behind the service bearer now that the seed sends it (PC-0025 item 3).
4. **ad-sphere** (P2f): `AdTenantLookup` should answer `snapshot(root)` with `TenantTrees.snapshot(root)` (the installed instance) instead
   of the default; `RootTree.of` is package-private now (ad-sphere does not call it); `routeOfCredentials` gives the ad lane's B6 step the
   route (its name names the `ad_caller` row) and the partner (the OUT-partner).
5. **The live switch's port order differs**: routesphere-core's `matchRouteKey` tries the bare ip FIRST, then `ip:port`, then `:5060`; the
   seed follows A5 (the port first, no default). A trunk served at both the bare ip and `ip:port` for two partners answers differently.

## What P2 (ad-sphere) must know

1. `byRouteCredentials(ctx, user, secret)` / `routeOfCredentials(…)` (the route itself: `routeName()`, `partner()`) — a refusal sets
   `ctx.identificationCause = CREDENTIALS_REJECTED` (cleared by the base before each candidate's `identifyPartner`); an unknown user name does
   one decoy compare.
2. `TenantTreeBuilder.routeCredentials(partnerId, routeName, user, secret)` writes the `{sha256}` form; `legacyRouteCredentials(…)` the
   plaintext row (B5's second branch); `routeCredentialsStoredAs(…)` a pinned value.
3. The officer road's writer (B4′): `Secrets.newMachineSecret()` (returned once, never logged) and `extra2 = Secrets.hashMachineSecret(secret)`
   (seed-sessionflow `internal/Secrets`); item 2's `Secrets.hash(char[])` no longer exists.
4. `PoolStats` has a 13th number; the 10-, 11- and 12-number constructors stay.

## The rtc-domain jar

The seed compiles against `~/.m2/repository/com/telcobright/rtc-domain/1.0-SNAPSHOT/rtc-domain-1.0-SNAPSHOT.jar` (2026-10-07 00:18). It
matches the source read on routesphere main (`rtc-domain/src/main/java/…`): javap shows the same fields and getters of `DynamicContext`,
`Route`, `RouteMetadata`, `Partner`, `Tenant`, and `DynamicContextDeserializer.wireRouteMetadataBackRefs` + `wirePartner`; no commit touched
those files after 2026-10-06.
