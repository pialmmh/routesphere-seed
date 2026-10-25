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
- 2026-10-25 04:30 · **item 4 done** — the guard: `HotPathHasNoListScanTest` (seed-sessionflow) scans the MAIN sources of seed-sessionflow,
  seed-tenant and seed-routing with A8's pattern — the five names matched WITHOUT case (so `getPartners()`, `getChildren()`, `getRoutes()`
  count: as written, lower case only, it would not see the climb's old `for (Partner p : parent.getContext().getPartners().values())`) and
  `for` followed by any spaces — comments, strings and text blocks blanked, each walk owned by its `Class#method` (a brace-tracking scan:
  classes nested as Outer.Inner, `<field>` for an initializer, a record's compact constructor, a lambda belongs to its method); every walk
  must stand in `docs/agents/hot-path-allowlist.txt` (`Class#method — reason`, `Class#*` for the index builder; 37 entries: the install,
  a session's own few lines, the start/close/stats roads, seed-routing's request copy, route-group weights and policy compile/admin, the
  testkit); an entry that names no walk any more is red too. A second test pins the scanner on a fixture (a field, a method, a compact
  constructor, a lambda; a comment and a string not counted). Red first: the guard red with an empty allow-list (52 walks listed). Mutants
  3, 3 red: a new walk in `Lookups.byPartnerId`, the climb's old loop back (seen by the guard alone), a stale allow-list entry; restored
  with a fresh mtime. Next: item 5 (the samples use the helpers).
- 2026-10-25 05:44 · **item 5 done** — the samples find their partners through the served route maps (no private map): the World's trees
  carry them (`route(partner, key…)`: 10.0.0.9 → 9, 10.0.0.7 → 701, 10.0.0.17:7023 → 701 at that port only, 10.0.0.2/.3/.4, 10.0.0.8 →
  801, 10.0.0.5 → NewCo 705 in the reloaded tree; ccl's 10.0.0.9 and 10.0.0.7 in its own tree; `sipAccount(801, "sip-walton")`;
  `routeCredentials` for the SMS users `direct` and `unilever` (hashed), `legacyRouteCredentials` for `walton` (plaintext row);
  `World.SMS_PASSWORDS`). Voice: `VoiceFlow.identifyPartner` = `byIp(ip, port)` when the park carried its port (else the bare ip), then
  `bySipAccount`; the park carries `sourcePort` (EslFrame / ChannelPark / EslFacts — the old 6-word `park` and 7-word facts kept, port 0);
  the `partnerBySource` map and every constructor's map gone. SMS: the HTTP door `byRouteCredentials(user, password)` (`SmsFrame.http`
  carries the password; `SmsRequest` / `SmsFacts` the password — `@JsonIgnore`, never in their text — and the Kafka door's campaign
  `partnerId`), the Kafka door `byPartnerId` (the campaign names it); the machine answers a refused credential `401 CREDENTIALS_REJECTED`
  (every other refusal `REJECTED <cause>`); `SmsFlow.of` without the users' map. WiFi and the ad sample unchanged. Tests: VoiceFlowTest
  +1 (ip:port first, the bare ip for an unnamed port, no :5060 default, the SIP account), SmsFlowTest +1 (a wrong password and none →
  CREDENTIALS_REJECTED + 401, nothing reserved; the legacy row admitted through three tiers; the route named); moved to the new road:
  SmsFlowTest's unknown user (now CREDENTIALS_REJECTED + 401 — the item's acceptance) and every sample's construction. Mutants 4, 4 red:
  voice ignoring the port (VoiceFlowTest 1/16), voice without the SIP fallback (1/16), the HTTP door by the user name alone (SmsFlowTest
  2/8), no 401 (SmsFlowTest 2/8); restored with a fresh mtime.
- 2026-10-25 05:44 · **item 6 done** — the tree road sends the bootstrap's bearer (A9): `BootstrapSource.bearer()` (default: none),
  `HttpBootstrapSource.bearer()` = the SAME variable the envelope is asked with, read at every ask (a rotated token taken; null when empty);
  `PrimeContextTrees` (the deployed trees) hands it to `PrimeContextTreeSource`, which sends `Authorization: Bearer …` whenever there is a
  token and nothing when there is none; the test road (`PrimeContextTrees(Function, doorbell)`) unchanged. TreeRoadBearerTest 2 (a JDK
  HttpServer on 127.0.0.1 as prime-context; no platform Kafka, no doorbell): the token on the first ask and the rotated one on the reload;
  no token → no header; a 401's refusal never prints the token. Mutants 2, 2 red: the tree road without the bearer (2/2), the bearer read
  once (1/2); restored with a fresh mtime.
- 2026-10-25 05:44 · **item 7 done** — docs: the sessionflow README's new §4.0 "Identification — the partner through its route, one get each"
  (the owner's words; the index per snapshot and its maps with their helpers; a route's partner; a key two holders share; route
  credentials, the two forms, the one cause, the measured PBKDF2 cost; the climb; the guard and NoWalkAfterInstallTest; a lane's own
  lookup; never print a Route/RouteMetadata whole), its class table (Lookups, RootTree/RootIndex), the contract table's (c) rows,
  the packages and the history line; the seed-tenant README's "built once per snapshot" made true (the install through RootInstall, one
  get for a root by code or db name, the counters, the tree road's bearer); `docs/agents/routesphere-seed-flow-requirements.md` R2's
  helper names = `byRouteCredentials · byIp · byDomain · bySipAccount · byPrefix · bySystemId · byPartnerId` (the architect updates routesphere
  main's copy); this state file; the report `docs/agents/P1-O-report.md`.
- 2026-10-25 06:32 · **item 2b done** (the architect's review finding: A4′ amends A4 — route credentials are MACHINE secrets) —
  `internal/Secrets`: the written form is now `{sha256}<base64 salt>$<base64 SHA-256(salt ‖ UTF-8 secret)>` (16-byte salt; ONE SHA-256 +
  `MessageDigest.isEqual`); `Secrets.newMachineSecret()` (32 SecureRandom bytes, base64url, no padding) and `Secrets.hashMachineSecret(secret)`
  are what writers use; `{pbkdf2}` stays VERIFIED (compatibility) but nothing writes it — the public `Secrets.hash(char[])` of item 2 is
  gone, its form only a package-private `pbkdf2(…)` for the compatibility vectors; the javadoc says a slow KDF must not guard a per-request
  credential; the legacy plaintext stays the constant-time compare. `Lookups.routeOfCredentials`: an unknown user name does ONE decoy
  `{sha256}` compare (`Secrets.compareWithNobody`, counted `decoyCompares`) before `CREDENTIALS_REJECTED` — equal work for "no such user"
  and "wrong secret". Testkit: `routeCredentials` writes `{sha256}`; `routeCredentialsStoredAs` keeps a pinned value (the `{pbkdf2}`
  compatibility row); `TESTKIT_ITERATIONS` gone. Tests: SecretsTest 6 (a `{sha256}` vector pinned from Python's hashlib, a second with a
  base64url secret; the machine secret's shape and a fresh salt per hash; the decoy counted; the PBKDF2 published vectors and the pinned
  `{pbkdf2}` one still verify; the legacy compare; the measured cost: `{sha256}` 1.9 µs p50 / 6.3 µs p99 (a wrong secret 1.7 / 3.7, the decoy 0.9 / 4.2, the legacy compare 0.1 / 1.7) against 98 ms per `{pbkdf2}` check at 210 000 iterations), RouteLayerHelpersTest (a `{pbkdf2}` row still admits; a known
  user's wrong secret does no decoy, an unknown user exactly one; the testkit stores `{sha256}`). Mutants 2, 2 red: no decoy for an unknown
  user (RouteLayerHelpersTest 1/5), the `{sha256}` check without its salt (SecretsTest); restored with a fresh mtime.
- 2026-10-25 06:32 · **item 1b done** (prime-context's PC-0025 finding: on the SERVED tree a `usernameVsRouteMetaData` row has no route —
  its `RouteMetadata.route` is `@JsonIgnore`) — `ServedTreeRoundTripTest` 3: a testkit tree serialized as prime-context serves it (a Jackson
  mapper with mix-ins: the row's route `@JsonIgnore`, the tree's index and chains off the wire, the route's partner off or on as a copy),
  read back by `Trees.parse`, installed: `byRouteCredentials` (the `{sha256}` row and the legacy row), `byIp`, `bySipAccount` name the right
  tier and the tier's OWN partner instance on four wires (partner off/on × `partnerVsRoutes` kept/dropped); with only `idVsRoute` the
  parsed row has NO route (rtc-domain's deserializer wires rows from `partnerVsRoutes` only) and the index still finds it by the row's id.
  No main-code change: item 1's `RootIndexBuild` already resolved a row's route by its id among the tier's routes (`partnerVsRoutes`,
  `idVsRoute`, `ipOrDomainWiseRoute`, built once per tier) and skipped an unserved one with one WARN — so the new test is GREEN on the
  previous head, not red as the finding expected; its red is the mutant (a row's route only by its back reference: ServedTreeRoundTripTest 2/3 and RouteLayerHelpersTest 3/5). Restored with a
  fresh mtime.
