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
- 2026-10-25 03:59 · **item 2 done** — the helpers in `Lookups` (each a get on the session's installed index; the flow's protected final
  delegates beside `byPartnerId`): `byRouteCredentials(ctx, user, password)` + `routeOfCredentials` (the matched `RouteEntry`: a lane names
  its caller by the route — the ad's B6 preprocessing) + `bySystemId` (the SMPP name); an unknown user name, a wrong, missing or hashed-
  as-password value → null and `ctx.identificationCause = CREDENTIALS_REJECTED` (one cause; `SessionCause.CREDENTIALS_REJECTED` new;
  `RoutesphereContext.identificationCause` new; the password is not kept, logged or put in a text). `byIp(ctx, ip)` the bare key, `byIp(ctx,
  ip, port)` ip:port then the bare ip, no :5060 default; `byDomain`; `bySipAccount`; `byPrefix` (one get per prefix length present);
  `routesOf(leaf)` from `partnerVsRoutes`. `AdmissionChain`: the cause cleared before each candidate's identifyPartner; a null leaf → the
  recorded cause, else PARTNER_NOT_FOUND. `internal/Secrets` (A4): `{pbkdf2}<it>$<salt>$<hash>` recomputed (PBKDF2WithHmacSHA256, 16-byte
  salt, 32-byte hash, 210 000 by default; the stored hash's length honoured, a count outside 1…5 000 000 or a part that does not parse
  verifies nothing), anything else the legacy plaintext by `MessageDigest.isEqual` on UTF-8 bytes; a blank stored value verifies nothing;
  `Secrets.hash(char[])` for writers. Testkit `routeCredentials(partner, routeName, user, password)` (B5: the hashed form, at
  TESTKIT_ITERATIONS = 1 000 for speed). Measured here: **PBKDF2 at 210 000 iterations verifies in ~91 ms** (hash 182 ms cold) — every
  request on a hashed row pays it (open point for the report). Red first by compile (no helpers / Secrets / cause); green: SecretsTest 4
  (the published PBKDF2-HMAC-SHA256 vectors c=1/2/4096; a value of this form computed with Python's hashlib, non-ASCII password, pinned),
  RouteLayerHelpersTest 5 (both forms, one cause for every refusal, ip:port first, no :5060 default, SIP, prefix, routesOf, the cause through
  a dry-run admission, each candidate its own cause, no password in a note). Mutants 5, 5 red (an unknown user name without the cause 2/5,
  the admission ignoring the cause 2/5, no reset per candidate 1/5, the bare ip first 1/5, a :5060 default 1/5), each restored with a fresh
  mtime; the plain-equals mutant is not provable (A4: by eye). The whole reactor **660/0/0/3** (03:55–03:58). Next: item 3 (the scans gone).
- 2026-10-25 04:15 · **item 3 done** — the scans gone. `partnerAbove` (and the public static `resellerPartnerOf`): the id at the end of the
  child's db name against the PARENT's `partners`; ONLY on a miss the parent's partner named EXACTLY as the child (its name, else its db
  name) — through the session's index (`RootIndex.partnerNamedIn`, the tier carries its snapshot's index: `Tier.entryIn`, package-private,
  handed down `ancestorChain`), or for a tier made by hand rtc-domain's `partnerNameWisePartners` of the parent; no loop, no "both answer"
  WARN any more (the name is not asked when the id answers). `entryNamed` → the index (item 1); `rootOfCode` → maps (item 1: TenantTrees
  by RootInstall's codes, ServedTrees by its own; the default `TenantLookup.snapshot` asks `rootOfCode`). seed-routing: `spi/PrefixTable`
  (the source's prefixes by called length, calling length and both; equal keys keep the first row, as the walk's max did) and
  `DialplanSource.prefixTableOf` (default: indexes what `prefixesOf` hands; a source that keeps its tables builds it once per load);
  `DialplanRouter.longest` = one get per length present, its three steps and trace words unchanged. New: `ArmedTree` (a copy of a tree whose
  every map refuses to be walked once armed) and NoWalkAfterInstallTest 2 (after the install, every helper answers and a session climbs three
  tiers by ids and a hand-named tier by its exact name — no walk). Existing test touched: ResellerPartnerRuleTest (A2/A7: the hand-named
  tier's partner is now `res_acme` exactly; `RES_BETA` stands for nothing). Red first: NoWalkAfterInstallTest red on f7539a1's climb (the
  mutant below is that climb). Mutants 3, 3 red: the unconditional name loop back (NoWalk 1/2 + ResellerPartnerRule 1/5), the per-session
  walk back in entryOf (NoWalk 2/2), the shortest prefix first (DialplanRouterTest 1/6); restored with a fresh mtime. The whole reactor
  **662/0/0/3** (04:11–04:15). Next: item 4 (HotPathHasNoListScanTest + the allowlist).
