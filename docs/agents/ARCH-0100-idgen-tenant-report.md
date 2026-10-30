# ARCH-0100 — seed-idgen on the tenant-first id service · report

**Round:** ARCH-0100 (brief `~/c-work/briefs/ARCH-0100-seed-idgen-tenant.md`, the routesphere architect, 2026-10-30 23:4x +06) · branch
`idgen-tenant` off `routesphere-flow` adf583d · worktree `worktrees/idgen-tenant` · the id service: bucket-next `tenant-first`
0e8929f (base fc779e7 = the bed's binary), its `docs/tenancy.md` + `docs/api-reference.md` read first; the design: routesphere main
`docs/architecture/two-db-two-tenant-organisation.md` §1 (the owner's rulings), §6 (the wire contract).

**In one breath.** The id service is tenant-first: every road `/api/v2/{tenant}/…`, the tenant mandatory, 400 (never 404) for the
tenant refusals, the v1 roads only while the service's `legacy_tenant: btcl` line exists. This round moves the seed's client and its
callers inside the seed onto v2 — the tenant PASSED IN BY THE CALLER on every call, no default tenant anywhere — and makes the live test
build and start the tenant-first binary itself, with the new config shape, in the 7000 range.

## The words of the two binaries, read before the client was written (00:0x +06, `~/idgen-work/tools/curl-rehearsal.sh`)

| binary | road | answer |
|---|---|---|
| tenant-first 0e8929f, `legacy_tenant: btcl` | `GET /api/v2/btcl/next-id/partner.btcl?dataType=int` | 200 `{"dataType":"int","entityName":"partner.btcl","shard":1,"tenant":"btcl","value":1}` |
| | `GET /api/v2/acme/next-id/partner.btcl?dataType=int` | 200 … `"tenant":"acme","value":1` — the same entity, another counter |
| | `GET /api/v2/btcl/status/nothing` | 404 `{"error":"Entity not found","tenant":"btcl"}` — the one meaning of 404 kept |
| | `POST /api/v2/btcl/init/order {int, 5000}` | 201 … `"actualStartValue":5001` (snapped to shard 1 of 2); again → 409 `Entity already exists` |
| | `PUT /api/v2/btcl/reset/order {10}` | 409 `Backward reset refused`; `{9000}` → 200 `"nextValue":9001` |
| | `GET /api/v2/nobody/next-id/…` | 400 `{"error":"Unknown tenant","message":"tenant 'nobody' is not served by this installation (served tenants: acme, btcl)"}` |
| | `GET /api/v2/BTCL/next-id/…` | 400 `Invalid tenant` … `breaks the rule ^[a-z][a-z0-9]{1,15}$` |
| | `GET /api/v2/next-id/…` | 400 `Tenant is required` … `the tenant comes first: /api/v2/{tenant}/next-id/{entity}` |
| | `GET /api/next-id/partner.btcl?dataType=int` (v1) | 200, the pre-tenant body (no `tenant` field); `/shard-info` then shows `"legacyTenant":"btcl","v1Calls":1` |
| tenant-first, restarted WITHOUT `legacy_tenant` (same `state_dir`) | `GET /api/next-id/…` (v1) | 400 `{"error":"Tenant is required","message":"this installation serves tenants by name: call /api/v2/{tenant}/next-id/partner.btcl"}` |
| | `GET /api/v2/btcl/next-id/…` | 200, the counter continues (`value: 41`) |
| **fc779e7 (the bed's binary today)** | `GET /api/v2/btcl/next-id/…`, `GET /api/v2/btcl/status/…` | **404 `404 page not found`** — plain text, not JSON: the pre-tenant service has no v2 road at all |
| | `GET /api/next-id/…` (v1) | 200 as always |

The last row rules the compatibility question (item 3): the moved client CANNOT run against fc779e7 — there is no road for it. So no
fixture of the old run shape (`state_path`) is kept; instead the client turns that bare 404 into a refusal in words (below).

## Items

### 1 · The client on v2 — `seed-idgen`

| what | where |
|---|---|
| **The tenant word's rule** — `api/TenantId` (new): `RULE = [a-z][a-z0-9]{1,15}` (tenant-master's tenant id, prime-context `TenantRegistry.PLATFORM_TENANT_ID`, the service's own check), `checked(t)` refuses null/blank/ill-formed IN WORDS before any call ("… the caller passes the root's tenant on every call; there is no default") | `api/TenantId.java` |
| **The port takes the tenant first** — `IdSource.mint(tenant, entity, kind, count)`, `mintAbove(tenant, entity, kind, count, highestInUse)`; javadoc: the tenant comes first on every call, no implementation has a default | `spi/IdSource.java` |
| **PartnerIds.next(tenant, root, highestInTree)** — the root's tenant id beside the root db name (both `btcl` on the bed); `entityOf(root)` unchanged (`partner.<root>`); the tenant checked by the rule here too | `api/PartnerIds.java` |
| **The roads, in ONE place** — `internal/Roads` (new, package-private): `nextId/nextBatch/status/init/reset(tenant, entity)` = `/api/v2/{tenant}/{road}/{entity}`; no pre-tenant road is written anywhere in the client | `internal/Roads.java` |
| **BucketNextSource** — the tenant through both verbs and the rotation; `check(tenant, entity, count)` = `TenantId.checked` + the entity rule + the batch rule, before any call; `IdServiceUnavailable` names `tenant/entity`; every `what` names shard, tenant, entity and road | `internal/BucketNextSource.java` |
| **ShardCounter.raiseAbove(shard, tenant, entity, kind, highestInUse)** — the three roads on v2; "the shard does not know the entity" = **404 + the word `Entity not found`** (before: any 404). Why narrower: the service documents 404 `Entity not found` as the ONE meaning and makes every tenant refusal a 400 on purpose; a bare 404 (the bed's pre-tenant binary) must never register anything | `internal/ShardCounter.java` |
| **Answer** — the matches unchanged (5xx and `Range exhausted` → the next shard; any other 4xx → `IdRefused`); the three tenant refusals are 4xx → `IdRefused` with the service's words AND `what` ("… btcl/order next-id — tenant 'nobody' is not served …"); **new:** a 404 whose body is not JSON → `IdRefused(404, "No v2 road", "… it has no /api/v2/{tenant}/ roads; this client needs the tenant-first service")` | `internal/Answer.java`, `internal/Shard.java` (the `not JSON` word shared) |
| **BucketNext** builder — unchanged: shards + timeout, NEVER a tenant (javadoc says so, with the call shape) | `dependencies/BucketNext.java` |
| **InMemoryIdSource** — counters AND the kind binding keyed by `(tenant, entity)`; the tenant checked by the rule; `entitiesOf(tenant)` / `tenants()` show what a caller's tenant word reached (the hook the PrepaidAdmin mutant trips) | `testkit/InMemoryIdSource.java` |
| README: the 2026-10-30 ruling, the call shape with the tenant, the rules (the tenant comes first; a service older than tenancy fails closed) | `seed-idgen/README.md` |

Tests (seed-idgen): `StubShard` serves `/api/v2/{tenant}/…` with a tenant roster (btcl + `serving(...)`), counters per (tenant, entity),
the three tenant refusals in the service's words (400), the pre-tenant roads answering 400 `Tenant is required`, and an
`olderThanTenancy` mode (a bare `404 page not found`, text/plain). `BucketNextSourceTest`: every road names the tenant first (the
exact paths asserted); the same entity in two tenants = two counters; a blank/ill-formed tenant (8 shapes) refused before any call,
`calls` empty; an unknown tenant → `IdRefused` 400 `Unknown tenant` naming `nobody/x`, ONE call per shard (refused everywhere, no failover),
no POST (nothing registered); a service older than tenancy → `IdRefused` `No v2 road`, no registration tried; the failover, the refusals
and the raise as before, per tenant. `PartnerIdsTest`: the same root word under two tenants = two counters; a blank tenant refused before
any call. `BucketNextLiveTest`: item 3.

**State (00:23 +06): item 1 written and run.** First run (`-pl seed-idgen -am install`, 00:07–00:21, the lock waited for): seed-idgen
28 tests / 2 red, both the test's or the client's reading of a body, both fixed: (a) `Shard.parse` — Jackson reads the bare
`404 page not found` as the NUMBER 404 (no trailing-token check), so the client saw `error ""` instead of "not JSON"; now a body that is not
a JSON OBJECT is "not JSON" and the refusal says `No v2 road`; (b) the live test read `/shard-info.legacyTenant` as `"null"` (`asText()`
of a JSON null) when unset — the check now takes missing, null and empty alike. Second run (`-pl seed-idgen install`, then
`-pl seed-prepaid test`) started 00:22.

### 2 · The callers inside the seed

| caller | the tenant it passes |
|---|---|
| `seed-prepaid` `api/PrepaidAdmin.purchase(root, tierDb, …)` → `kit.ledger().purchase(buyer, offer, reference, (table, highest) -> nextId(root, table, highest))` — `nextId(rootTenant, table, highestInUse)` = `ids.mintAbove(rootTenant, table, LONG, 1, highest)` | **the root's tenant id = the `root` word of the call** (tenant-master's tenant id = the root schema = the topic suffix: `btcl`); the tier (`res_2`) is never the tenant — a reseller's accounts are minted in its root's counters. `LedgerPort.Ids` (`long next(table, highest)`) is untouched: the seam between the ledger and the ids knows no tenant; PrepaidAdmin binds it |
| `PrepaidAdmin` javadoc + `seed-prepaid/README.md` | a paragraph "The tenant on every mint" each; the config value names shards only, never a tenant |
| `seed-prepaid` tests: `PurchaseThroughTheLedgerTest` | NEW assertion: `ids.tenants()` = `[btcl]` and `ids.entitiesOf("btcl")` has the two tables — the hook for mutant 3 |
| `seed-prepaid` `admin/lab/PurchasesOverTwoLiveShardsTest` + `admin/lab/LiveShards` | the module's own `LiveShards` (the OLD config shape `state_path`, the repo's `bin/` binary) is REMOVED; the test uses seed-idgen's live fixture (`live/LiveShards.bedShaped(dir, 2)`, the test-jar) — the bed's shape, every mint under `btcl` |
| `seed-switch-ledger` `PurchasesTest` | not a caller: its `above(table, highest)` is a local lambda on `LedgerPort.Ids`; unchanged |
| every other grep hit (`seed-switch-ledger` `LedgerEntities`, `LedgerDb` javadocs; the docs/agents reports) | words only, no call; unchanged |

**prime-context's `TreePartnerIds` (NOT this round's; the one-line change it needs):** in `minted(PartnerIds partnerIds)`,
`int id = partnerIds.next(root, highest);` → `int id = partnerIds.next(tenant, root, highest);` — `tenant` = the root's tenant id
(tenant-master's word, `btcl` on the bed), given to `TreePartnerIds` beside `root` by its constructor (today it gets `root` alone:
`new TreePartnerIds(tiers, pools, "wroot", Optional.of(new PartnerIds(generator)))`). Until prime-context redeploys with the moved
client, the bed's `legacy_tenant: btcl` keeps its v1 roads alive (bucket-next tenancy.md, the switch-over's (b)).

**A finding of item 2 (01:12 +06), and a seam for the architect.** seed-prepaid's first suite: 295 tests / 0 failures / **3 errors**, all
in `MysqlPurchaseMakesItsAccountsLabTest`: `IllegalArgumentException: a tenant is tenant-master's tenant id (…), not 'l2c_lab_btcl'`. The
MySQL purchase lab names its root DATABASE `l2c_lab_btcl` (a lab prefix against clashes on the shared lab MySQL; the H2 and PostgreSQL
labs name theirs `btcl`), and PrepaidAdmin now hands the root word to the id client as the tenant — the rule refuses the underscores
before any mint. The design's premise (the root schema = the tenant id, `^[a-z][a-z0-9]{1,15}$`) is now ENFORCED at the first purchase of
a root. Done here: the lab's databases renamed `l2clabbtcl`, `l2clabres2`, `l2clabres3` (test-only: the lab drops and creates them on
every run; `MysqlTwins.of(tier)` only formats the word into DDL). **The seam (a ruling, when convenient; nothing here waits on it):** a
root whose schema word is not a valid tenant id cannot buy through `PrepaidAdmin` — on the bed the root is `btcl`, fine; if a real root
ever needs a schema name that differs from its tenant id, PrepaidAdmin needs a tenant-of-root lookup from the tree (rtc-domain's
`Tenant` carries `getName()` beside `getDbName()`; the seed's `TenantTreeBuilder.root(dbName)` sets both to the one word today) — not
built; the brief's reading "the root's tenant id = the root word" is what landed.

**State (01:15 +06): item 2 done.** `PurchaseIdsUnderTheRootsTenantTest` (root `acme`) and `PurchaseThroughTheLedgerTest`'s tenant
assertion green in the first prepaid suite; the MySQL lab re-run with the renamed databases: 9/0/0/0 (`MysqlPurchaseMakesItsAccountsLabTest`
3, the two tenant tests 6) — `MysqlPurchaseLab: … databases [l2clabbtcl, l2clabres2, l2clabres3]`.

### 3 · The live test — `BucketNextLiveTest` on the REAL tenant-first service

`live/BucketNextBinary` (seed-idgen test-jar): `git -C ~/telcobright-projects/bucket-next archive --output=<tmp>/src.tar tenant-first`,
`tar -xf … -C <tmp>/src`, `go build -o <tmp>/bucket-next ./cmd/bucket-next` (GOFLAGS=-mod=mod) — ONCE per JVM into a
`Files.createTempDirectory("bucket-next-tenant-first-")`, removed at JVM exit; never the repository's tree, never its `bin/`
(the bed's pre-tenant binary lives there). `-Dbucketnext.bin=<path>` names a built binary (nothing built); `-Dbucketnext.repo`,
`-Dbucketnext.ref` say what to build. A test SKIPS only when nothing can be built here (no repository, no `go`, no `git`); a build that
fails with them present is an ERROR with the build log in the message, never a skip.

`live/LiveShards`: a config of the new shape per shard — `state_dir: <test dir>/shard-k`, `tenants: [...]`, `legacy_tenant` only when
asked (`bedShaped` = the profile as committed: `tenants: [btcl]`, `legacy_tenant: btcl`; `serving(...)` = no legacy line), `segment_size: 10`,
`-init` on the first start — on **the first free port of 7710–7799 on 127.0.0.1** (the house 7000 range; nothing listens there on this
box). Every shard stopped by its `Process` (`destroyForcibly`), never by name.

| # | scenario | shape | proves |
|---|---|---|---|
| 1 | the bed's shape: two shards, 400 concurrent `PartnerIds.next("btcl","btcl",5000)`, shard 1 killed, 20 mints on shard 2 alone, shard 1 back on its `state_dir` without `-init`, 40 more | `tenants: [btcl]`, `legacy_tenant: btcl` | 460 ids, none repeated, all > 5000, both shards minted before and after; `/shard-info`: `legacyTenant: btcl`, **`v1Calls: 0`** — the client never touched a pre-tenant road; `status` answers `"tenant":"btcl"` |
| 2 | one shard, NO `legacy_tenant`: `mint` 1 (next-id), 3 (next-batch), 2 uuid12; `status` 404 → `mintAbove(…, 5000)` registers and mints 5001 (status then 5002); a known counter moved forward past 9000 → 9001; already past → 9002, 9003 (no move back) | `tenants: [btcl]` | every road of the client on v2 after the callers moved — **the mutant "a v1 road left" goes red here**; the pre-tenant road `GET /api/next-id/…` answers 400 `Tenant is required` |
| 3 | one shard, two declared tenants: `order` INT on btcl → 1,2,3; on acme → 1,2; `mintAbove` acme 700 → 701; btcl's next is still 4; `cdr` LONG in btcl and INT in acme both → 1 | `tenants: [btcl, acme]` | the same entity name in a second declared tenant = a separate counter and its own kind; `status` per tenant 5 / 702; `/shard-info.tenants` = [acme, btcl] |
| 4 | one shard: `mint("nobody", …)` → `IdRefused` 400 `Unknown tenant` naming `nobody/order` + "tenant 'nobody' is not served by this installation (served tenants: btcl)"; `mintAbove("nobody", …)` → the same (400, never 404: not an unregistered entity); `"BTCL"` and `" "` → `IllegalArgumentException` before any call | `tenants: [btcl]` | an unknown tenant refused in the service's words, `/api/v2/btcl/list` empty (nothing registered), `v1Calls: 0` |

**fc779e7 compatibility kept: none at the wire, by necessity** — the pre-tenant binary has no `/api/v2/{tenant}/` road (a bare
`404 page not found`); the moved client refuses in words naming the tenant-first service and mints nothing (`BucketNextSourceTest.
aServiceOlderThanTenancy_…`). The old run shape (`state_path`, `bin/bucket-next`) is gone from both live fixtures. On the bed the order is
the service first (`legacy_tenant: btcl` keeps prime-context's v1 client alive), then this client's consumers.

**State: item 3 written; in the same first run (the live test builds the binary itself).**

### 4 · Gates and mutants

_(filled below as the runs return)_

## Suites

| tree | result |
|---|---|
| worktree, first run (`-pl seed-idgen -am install`) | _pending_ |
| worktree, the reactor | _pending_ |
| clean copy (git archive of the code-final commit) | _pending_ |

## Mutants

| mutant | what it breaks | red |
|---|---|---|
| `m1-default-tenant` | a blank tenant becomes `btcl` in the client instead of a refusal | _pending_ |
| `m2-v1-road-left` | `ShardCounter`'s status road left on the pre-tenant shape (`/api/status/{entity}`) | _pending_ |
| `m3-prepaid-no-tenant` | `PrepaidAdmin` passes the tier's word instead of the root's tenant | _pending_ |

## Rules of the round kept

JDK 21.0.9; Maven offline on the private repository `~/idgen-work/m2` (a `cp -al` copy of `~/items-work/m2`, the rewritable files
un-shared, the seed's `2.0.0-SNAPSHOT` folders removed before the first install; `~/.m2/repository` a read-only tail), every build under
`flock ~/telcobright-projects/.one-maven.lock` (waited for; it was busy at the round's start); Go 1.21.0 (`go.mod` says 1.21), the module
cache held `yaml.v3`, no network; ports 7710–7799; no bed, no live box, no sudo/systemctl/`pkill -f` — every test process stopped by PID
or `Process`; `date` before every stamp; mutants restored from a kept copy, hash-checked; the branch pushed by its own name, no merge, no tag.
