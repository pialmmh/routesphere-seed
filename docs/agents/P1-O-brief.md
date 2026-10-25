# P1-O — the seed: the ROUTE layer for identifyPartner, and no list scan on the hot path (READY)

Written 2026-10-25 02:3x +06 by the architect. The owner's design of 2026-10-25 (routesphere main 9eab8243
`docs/architecture/partner-identification-analysis-2026-10-25.md`, gaps G1 + G5, decisions D-A…D-F "go as you recommend"). Branch `p1-follow`
at **f7539a1** (= routesphere-flow, P1-N merged), worktree `/home/mustafa/telcobright-projects/routesphere-seed/worktrees/p1-follow`. Rules:
`~/arch-drafts/launch/RULES-2026-10-25.md` + your lane's `docs/agents/P1-routesphere-flow-brief.md` §0/§A (pins: statewalk 4.1.0 only).

## Why (the owner's words, shortened)

"The DynamicContext holds the partner entities AND the route entities, transformed into hashmaps by the key the business logic needs
(partner by ip, by name, …). The client passes a param, e.g. route username+password (the aggregator SMS pipeline has it), matched with the
routes key indexed in the DynamicContext; route → partner (`route.partner.name`); one partner may own many routes. The same entity may be
loaded into several hashmaps. Avoiding a LIST lookup on the hot path is a STRICT rule for partner and tenant identification."

The live switch does exactly this (routesphere-core `PartnerIdentifier`: `usernameVsRouteMetaData.get(user)` → `route` →
`partners.get(route.getIdPartner())`, password = `route_metadata.extra2`; ESL: `routeWisePartners.get(ip)` keyed by `route.field5`;
`sipAccountWisePartners`; `partnerVsRoutes`). The tree prime-context serves to the seed ALREADY carries these maps in rtc-domain's
`DynamicContext` (`usernameVsRouteMetaData`, `routeWisePartners`, `ipOrDomainWiseRoute`, `partnerVsRoutes`, `idVsRoute`,
`sipAccountWisePartners`, `prefixWisePartners`, `partnerNameWisePartners`). The seed reads NONE of them: `Lookups` offers only
`byPartnerId` / `byPartnerName`, so every lane sample invents a private map. And three scans run on the hot path:
`RootTree.partnerMap()` (every tier × every partner, rebuilt per SESSION because `TenantTrees.snapshot` makes a new `RootTree` each call),
`RoutesphereFlow.partnerNamedAs` (loops the parent tier's partners for every tier above the leaf, even when the id lookup hit),
`RootTree.entryNamed` (tiers × partners), plus `TenantTrees.rootOfCode` / `ServedTrees.rootOfCode` streams and seed-routing's
`DialplanRouter` prefix stream.

## Items

1. **One index per snapshot, never per session.** `RootTree` (or a new `RootIndex`) is built ONCE when a root's snapshot is taken into
   `TenantContextsImpl` (at load and at every reload) and cached on the snapshot; `TenantTrees.snapshot(root)` and every `TenantLookup`
   return the cached instance. It holds, for the WHOLE tree (every tier), flat HashMaps `key → EntryPartner(tier, partner)`:
   `byPartnerId`, `byPartnerName`, `byRouteKey` (from each tier's `routeWisePartners` / `ipOrDomainWiseRoute`: ip, `ip:port`, domain — the
   key as served), `byRouteUsername` (from `usernameVsRouteMetaData`: username → (tier, RouteMetadata, route, partner)),
   `bySipAccount` (`sipAccountWisePartners`), and `byPrefixLength` (a `Map<Integer length, Map<String prefix, EntryPartner>>` from
   `prefixWisePartners`, with the sorted set of lengths present). A key held by TWO tiers names nobody (as `heldByTwo` does for ids): one WARN
   per key at build, never per session. A counter `rootIndexBuilds` proves the rule: a test runs 1 000 sessions on one snapshot and reads
   ONE build; the mutant "build per session" is red.
2. **The helpers in `Lookups`** (the owner's `identifyPartner` toolbox; every one a HashMap get on the index, `null` = not found):
   - `byRouteCredentials(ctx, username, password)`: the username's route; the password compared constant-time with `route_metadata.extra2`
     (the SMS lane's column); a wrong or missing password → `null` AND `ctx` carries the cause `CREDENTIALS_REJECTED` (a new `SessionCause`,
     distinct from `PARTNER_NOT_FOUND`, so an HTTP lane answers 401 and an SMPP lane its bind error); the password is NEVER logged or put in
     a cause text (LogText.clean on the username only). `bySystemId(ctx, systemId, password)` = the same helper under the SMPP name.
   - `byIp(ctx, ip)` and `byIp(ctx, ip, port)`: `ip:port` first when a port is given, then the bare ip (the live switch's order); `byDomain`.
   - `bySipAccount(ctx, username)`; `byPrefix(ctx, number)`: longest match by at most N map gets (one per prefix length present, longest
     first) — never a scan of the prefixes.
   - `routesOf(partner)` from `partnerVsRoutes` (one partner, many routes) for a lane that needs the route list; `route.partner` is re-wired
     on load as routesphere-core's `DynamicContextDeserializer.wirePartner` does (check what `Trees.parse` already does; add the wiring if
     missing — the SAME partner instance as `partners`).
3. **The three scans gone:** `partnerAbove` → the id at the end of the child's db name against the PARENT's `partners` map; the name road
   only on a MISS and only through the index's `byPartnerName` (no loop). `entryNamed` → the index. `rootOfCode` → a map built with the
   served set. `DialplanRouter` → per-length map gets (seed-routing). Mutants: the loop back in `partnerNamedAs`; the per-session build
   back; a two-tier key resolving to one — each red, restored with a fresh mtime.
4. **The guard that keeps it kept:** a test in seed-sessionflow that scans the MAIN sources of seed-sessionflow, seed-tenant, seed-routing
   for `for (` / `.forEach(` / `.stream()` over `partners`, `routes`, `children`, `tiers`, `tenants` outside an allow-list of index-build
   classes (one file `docs/agents/hot-path-allowlist.txt`, each entry with a reason). A new scan in a flow class = red.
5. **The samples use the helpers** (test code, the acceptance): Voice `byIp(sourceIp, port)` then `bySipAccount`; the SMS REST sample
   `byRouteCredentials(user, password)` (`SmsFacts` gains `password`; a wrong password is refused `CREDENTIALS_REJECTED`, the HTTP
   sample answers 401); the SMS Kafka sample keeps `byPartnerId` (the campaign names the partner); WiFi unchanged; the ad sample unchanged
   (P2f does the ad). The testkit `TenantTreeBuilder` gains `route(partner, key…)` and `routeCredentials(partner, user, password)` so the
   samples build real served maps instead of private ones.
6. **The tree source sends the bearer:** `PrimeContextTreeSource` already takes an optional bearer — wire it from the bootstrap's token
   (the same token `HttpBootstrapSource` sends) in `TenantTreesBuilder` / `PrimeContextTrees.make`, so the facade may close its tree road
   (PC-0025 item 3). A test: the fetch carries `Authorization: Bearer …` when the kit has a token, nothing when it has none; the token is
   never printed.
7. **Docs:** seed-sessionflow README §identification (the helpers, the index-per-snapshot rule, the guard); seed-tenant README's
   "built once per snapshot" made true; `docs/agents/routesphere-seed-flow-requirements.md` R2's helper names =
   `byRouteCredentials · byIp · byDomain · bySipAccount · byPrefix · bySystemId · byPartnerId` (the architect updates routesphere main's
   copy); report `docs/agents/P1-O-report.md`; the state file.

## Gates

The whole reactor from clean in the worktree and on a git-archive clean copy, offline, no install, under the lock; statewalk 4.1.0 only; the
R-1 proofs green; the 1 000-session one-build proof; the guard test green on the final head. Hand back the head, the numbers, the reds.

## Out of scope

Loading partners from Odoo (D-A step 2, its own round); an SMPP ingress; prime-context's rule kinds and load profile (PC-0025); the ad lane
(P2f). If an item needs a prime-context change to be real, say which and continue with the testkit.

## ADDENDUM (binding)

The design points are FIXED in `~/arch-drafts/launch/DECISIONS-ADDENDUM-2026-10-25.md` (sections A = P1-O, B = P2f, C = PC-0025). Read it before resuming; where it differs from this brief, it wins.

---

# The addendum's section A (copied from ~/arch-drafts/launch/DECISIONS-ADDENDUM-2026-10-25.md, binding where it differs from the brief)

## A · P1-O (the seed) — the index per snapshot and the credential helpers

A1. **Build eagerly, once, at snapshot install — never lazily on first use.** `TenantContextsImpl` builds the `RootIndex` inside the same step
    that installs a root's `Snapshot` (load and every reload), and stores it ON the snapshot (`Snapshot(version, tree, index)`). A lazy build
    races (two sessions, two builds) and hides the cost in a request. `TenantTrees.snapshot(root)` returns `cell.snapshot().index()`;
    `RootTree.of(...)` becomes package-private and is called from the install step only. The counter `rootIndexBuilds` (a LongAdder on the
    kit, read by the status/stats road) must read `roots × reloads`, never `sessions`.
A2. **The index shape** (one immutable class, final fields, built by one builder that walks every tier ONCE):
    ```
    final class RootIndex {
      Map<Integer, EntryPartner>  byPartnerId;        // tier + partner
      Map<String,  EntryPartner>  byPartnerName;      // exact; no case-insensitive loop (the live findPartnerByName miss-loop is NOT copied)
      Map<String,  EntryPartner>  byRouteKey;         // every key of routeWisePartners + ipOrDomainWiseRoute, as served (ip, ip:port, domain)
      Map<String,  RouteEntry>    byRouteUsername;    // usernameVsRouteMetaData: username → (tier, RouteMetadata, Route, Partner)
      Map<String,  EntryPartner>  bySipAccount;       // sipAccountWisePartners
      NavigableMap<Integer, Map<String, EntryPartner>> byPrefixLength;  // prefixWisePartners grouped by key length, descending iteration
      Set<String>                 heldByTwo;          // every key (any map) seen in two tiers → names nobody; ONE WARN per key at build
    }
    record RouteEntry(Tier tier, RouteMetadata metadata, Route route, Partner partner) {}
    ```
    The partner of a route is resolved AT BUILD as `tier.context.partners.get(route.getIdPartner())` — never through `route.getPartner()`
    (rtc-domain's `Route.partner` is @Transient and may not be re-wired by the seed's `Trees.parse`; do not depend on it). A route whose
    partner id names no partner in its tier is skipped with one WARN at build.
A3. **`byRouteCredentials(ctx, username, password)`:** `RouteEntry e = index.byRouteUsername.get(username)`; if `e == null` OR the password does
    not verify → record `ctx.identificationCause = SessionCause.CREDENTIALS_REJECTED` and return `null`. BOTH cases the SAME cause (an
    unknown username must not be distinguishable from a wrong password). `PARTNER_NOT_FOUND` stays for the id / ip / prefix helpers.
    `AdmissionChain`: a null leaf → the recorded identification cause if set, else `PARTNER_NOT_FOUND`. The HTTP ad lane maps
    `CREDENTIALS_REJECTED` → 401 (P2f item 5); an SMPP lane → its bind error. The password never appears in a cause text, a log, or a
    `toString` (the helper takes it as `char[]` or a `String` it does not store).
A4. **Password at rest — two forms, one verifier** (`seed-sessionflow` `internal/Secrets`, no new dependency):
    - `route_metadata.extra2` starting with `{pbkdf2}` = `{pbkdf2}<iterations>$<base64 salt>$<base64 hash>` (PBKDF2WithHmacSHA256, 16-byte
      salt, 32-byte hash, default 210 000 iterations) → verify by recomputing; the ad lane's route-admin road WRITES this form (P2f).
    - anything else = the legacy SMS plaintext → compare with `MessageDigest.isEqual` on UTF-8 bytes (constant time). Documented as legacy.
    A `Secrets.hash(char[])` helper for the writers; a test vector pinned; a mutant (plain `equals`) is not provable by a test — the
    reviewer checks by eye; the brief says so.
A5. **`byIp(ctx, ip, port)`:** `byRouteKey.get(ip + ":" + port)` then `byRouteKey.get(ip)`; `byIp(ctx, ip)` = the bare key only; `byDomain`
    = the bare key. No `:5060` default in the seed — the voice lane passes the port it saw.
A6. **`byPrefix(ctx, number)`:** iterate `byPrefixLength.descendingMap()`, `len ≤ number.length()`, `get(number.substring(0, len))`; first hit
    wins. At most (distinct lengths present) map gets; ≤ 15 in practice.
A7. **The three scans:** `partnerAbove`: `parent.context.partners.get(idAtTheEndOf(child.dbName))`; on a miss ONLY, `index.byPartnerName.get
    (child.displayName)`; never a loop. `entryNamed` → `index.byPartnerName`. `TenantTrees.rootOfCode` / `ServedTrees.rootOfCode` → a
    `Map<String code, String root>` rebuilt when the served set changes. The default `TenantLookup.snapshot` uses that map.
A8. **The guard:** (a) the counter test — 1 000 sessions on one snapshot, `rootIndexBuilds == 1`; a reload → 2. (b) the source lint test
    `HotPathHasNoListScanTest`: scans `src/main/java` of seed-sessionflow, seed-tenant, seed-routing for lines matching
    `for \(.*(partners|routes|children|tiers|tenants)\(\)|\.(stream|forEach)\(` inside classes NOT listed in
    `docs/agents/hot-path-allowlist.txt` (one `Class#method — reason` per line; the index builder, `Trees.ready`, `PartnerTiers`'s build,
    the snapshot install). A new match = red with the file:line in the message.
A9. **Item 6 (the bearer):** `TenantTreesBuilder` passes the SAME `Supplier<String>` the bootstrap source uses (the token read from the
    environment variable the deployment names), so `PrimeContextTreeSource` sends `Authorization: Bearer …` whenever the kit has a token.


B5 (the testkit's routeCredentials for P2f), copied:

B5. **The lane's tree in tests:** `TenantTreeBuilder.routeCredentials(partner, routeName, username, password)` writes the hashed form into
    the built `usernameVsRouteMetaData`; one test uses a legacy plaintext row to prove A4's second branch.

