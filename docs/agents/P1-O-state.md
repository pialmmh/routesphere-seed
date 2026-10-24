# P1-O · the route layer for identifyPartner, and no list scan on the hot path — state

The brief: `docs/agents/P1-O-brief.md` (a copy of `~/arch-drafts/launch/P1-O-seed-route-layer-brief.md` with the addendum's section A,
binding where it differs). Branch `p1-follow` from routesphere-flow f7539a1 (P1-N merged; its clean copy 640/0/0/3). Items 1 → 7 in order.

One line per item, newest last. Times are Dhaka (+06), each read with `date` before it was written.

- 2026-10-25 03:11 · START — worktree `p1-follow` at f7539a1, clean. Read: the rules, the brief, the addendum (A binding), the lane's
  brief §0/§A/§K, P1-F's state and report, the sessionflow and seed-tenant READMEs, the requirements (R2, R14 on routesphere main), the
  analysis (§3, §7, §9), routesphere-core's `PartnerIdentifier` and `IdentifyEntryPartnerStep`, rtc-domain's `DynamicContext`,
  `DynamicContextDeserializer`, `Route`, `RouteMetadata`, `Tenant`, and prime-context b7b9f29's `DataLoader` / `DynamicContext` (what the
  tree road serves). The rtc-domain jar the seed compiles against (`~/.m2/…/rtc-domain/1.0-SNAPSHOT`, 2026-10-07 00:18) matches the
  source read (javap: the same fields and getters; `wireRouteMetadataBackRefs` + `wirePartner` present; no commit to those files since
  2026-10-06). The Maven lock is held by the prime-context agent's lab run: code first. Running: item 1 (the index per snapshot).
- 2026-10-25 03:42 · **item 1 done** — one index per snapshot, never per session. seed-sessionflow: `api/RootIndex` (the A2 maps: byPartnerId,
  byPartnerName EXACT, byRouteKey from routeWisePartners + ipOrDomainWiseRoute as served, byRouteUsername → `api/RouteEntry(tier, metadata,
  route, partner)`, bySipAccount, byPrefixLength; `heldByTwo`), built by ONE walk `api/RootIndexBuild` (a route's partner = its tier's
  `partners.get(route.idPartner)`, never `route.getPartner()`; a user name's route = the row's back reference, else the tier's route that
  carries the row by id; a key two holders share — two tiers, or two partners of one tier — names nobody, ONE WARN per key; a route whose
  tier lacks its partner left out, said once); `RootTree` builds it in its constructor, `RootTree.of` package-private; the install step
  `api/RootTreeInstaller` (`install`, `installOnce` for a lookup with no install of its own, the counter `rootIndexBuilds`).
  seed-context: `Snapshot` carries `index` (5th component; the 4-word constructor kept), `spi/SnapshotIndexer`, the builder's
  `.indexer(…)`, the cell's swap builds the index first (a throw = a failed load, the last snapshot stays, no version taken). seed-tenant:
  `internal/RootInstall` (the indexer: RootTree + the D5 PartnerTiers, eagerly at every load; served ids by code and by root db → one get)
  + `InstalledRoot`; `TenantTrees.snapshot` = the installed instance; `rootOfCode`, `snapshot` by code/db name and `servingTheTreeOf` by maps
  (no walk of the served roots). `TenantLookup.rootIndexBuilds()` (default NOT_COUNTED), `TenantLookup.of` installs at its construction,
  the default `snapshot` installs each tree object once; `RoutesphereKit.rootIndexBuilds()`, `PoolStats.rootIndexBuilds` (13th number; the
  12-number constructor kept). Testkit: `TenantTreeBuilder.route(partner, key…)`, `legacyRouteCredentials`, `sipAccount`, `prefix` (the
  index's tests need served maps; the hashed `routeCredentials` comes with item 2's Secrets). Red first by compile (no RootIndex / counter);
  green: SnapshotIndexTest 2, RootIndexTest 6, IndexPerSnapshotTest 2, OneIndexPerSnapshotTest 1 (1 000 answered voice calls on btcl's one
  snapshot: 2 builds for 2 roots, every call took the same installed tree; a reload → 3). Mutants 4, 4 red: the per-session build in
  TenantTrees.snapshot (seed-tenant 2/2, samples 1/1), the first holder keeping a shared key (RootIndexTest 2/6), the cell ignoring the
  indexer (SnapshotIndexTest 2/2 + IndexPerSnapshotTest 1/2), the builder not wiring the indexer (IndexPerSnapshotTest 1/2 +
  OneIndexPerSnapshotTest 1/1); each restored with a fresh mtime. Existing test touched: IdentifyPartnerTest (A2: a name is EXACT — its two
  case-blind assertions moved; res_44_7's partner renamed `Twin`, a `twin` added). The whole reactor from the worktree **650/0/0/3**
  (03:29–03:33). Next: item 2 (the helpers in Lookups, Secrets, CREDENTIALS_REJECTED).
