# seed-idgen — the ids every product mints from bucket-next

The owner, 2026-10-09: **partner ids are unique within a tenant**, by an external unique-id generator for partner CRUD. The
generator is **bucket-next** (`git@github.com:pialmmh/bucket-next.git`): a sharded id service, one Go binary per shard, shard k of
N handing out k, k+N, k+2N, … for a counter, so no two shards ever give the same id and no shard asks another.

The owner, 2026-10-30: **one id service per installation, serving every tenant, a tenant id mandatory on every call.** The service is
tenant-first (bucket-next `tenant-first`, `docs/tenancy.md`): every road is `/api/v2/{tenant}/…`, each (tenant, entity) its own
counter. This client is tenant-first too — **the tenant is the caller's word on every call; the client has no default tenant.**

## Use

```java
IdSource ids = BucketNext.builder()
    .shards("http://10.10.199.21:7001,http://10.10.198.21:7001")     // from the product's config file; no secret; no tenant here
    .timeout(Duration.ofSeconds(2))
    .build();

int idPartner = new PartnerIds(ids).next(tenant, root, highestInTree);   // the root's tenant id + the tree's root db name (both btcl on the bed)
List<String> accountIds = ids.mintAbove(tenant, "packageaccount", IdKind.LONG, 2, highestHeld);
```

| Piece | What it is |
|---|---|
| `spi.IdSource` | the port: `mint(tenant, entity, kind, count)` and `mintAbove(tenant, entity, kind, count, highestInUse)` |
| `api.TenantId` | the tenant word's rule — tenant-master's tenant id, `^[a-z][a-z0-9]{1,15}$`, never case-folded; `checked(t)` refuses a blank or ill-formed one in words before any call |
| `api.PartnerIds` | the owner's rule: one counter per tenant tree, entity `partner.<root>`, type `int` (`partner.idPartner`), under the root's tenant |
| `api.IdKind` | the service's types: `int`, `long`, `snowflake`, `uuid8/12/16/22` |
| `api.IdServiceUnavailable` | no shard answered: nothing was minted (fail closed — never a local fallback) |
| `api.IdRefused` | the service refused the request itself (`Type mismatch`, `Invalid startValue`, `Unknown tenant`, …): no other shard is asked |
| `dependencies.BucketNext` | the builder: every shard's base URL, the time one call may take — never a tenant |
| `testkit.InMemoryIdSource` | a cluster in memory with the service's shard math, counters per (tenant, entity), for products' tests; a shard can be taken down; `entitiesOf(tenant)` shows what a caller's tenant word reached |

## The rules it keeps

- **The tenant comes first.** Every call carries tenant-master's tenant id — the word a root carries (`btcl` = the root schema = the
  topic suffix = the tenant id). The client builds every road as `/api/v2/{tenant}/{road}/{entity}` (`internal/Roads`, the one place)
  and never calls a pre-tenant road (`/api/{road}/…`). A blank or ill-formed tenant is refused in the process; a tenant the installation
  does not serve is refused by the service — 400 `Unknown tenant`, never 404 — and the client raises `IdRefused` naming it.
- **Every shard serves.** A call goes to the next shard in the rotation; a shard that is down, times out, fails on its side (5xx) or
  has no values left (409 `Range exhausted`) is skipped and the next one asked — each once. A request the service refuses (another
  4xx) is refused everywhere.
- **A tree that existed before the generator keeps its ids.** `mintAbove` raises the shard that mints: registered with
  `startValue = highest + 1` when the shard does not know the entity (its `status` answers 404 `Entity not found` — that word alone),
  moved forward when it would hand out an id in use. Each shard keeps its own counter, so the shard that mints is the one raised —
  after a failover too. A move the service calls backward (409) means another process moved it past already. Partner creation is human
  CRUD, so the extra calls cost nothing that matters.
- **One counter per (tenant, entity).** `int` counters of different entities overlap on purpose, and so do the same entity's in two
  tenants: two trees' partners may share a number, a tree never repeats one (the owner's rule is per tenant).
- **A service older than tenancy fails closed.** The bed's pre-tenant binary answers a v2 road with a bare `404 page not found`: the
  client refuses in words naming the tenant-first service (`IdRefused`, code `No v2 road`), registers nothing, mints nothing.

## Tests

`PartnerIdsTest` (the rule on the in-memory cluster: 600 creators on 3 shards, all unique, all above the tree; the same root word
under two tenants = two counters; a blank tenant refused before any call), `BucketNextSourceTest` (the client against stub shards that
act like the tenant-first service: every road `/api/v2/{tenant}/…`, the wire, the failover, the refusals — the tenant's in the service's
words, the pre-tenant binary's bare 404 — the raise per tenant), `BucketNextLiveTest` (the REAL service: the `tenant-first` ref built from
a `git archive` into a temp dir — never the repository's tree, never its `bin/` — started on 127.0.0.1 in the 7000 range with the new
config shape `state_dir` + `tenants` [+ `legacy_tenant`]: the bed's shape with two shards, 400 concurrent mints, a shard killed and
restarted, 460 ids none repeated, `v1Calls` 0; without `legacy_tenant` every road of the client on v2 and the pre-tenant roads
answering 400; the same entity in a second declared tenant = a separate counter; an unknown tenant refused in the service's words with
nothing registered). Skipped when the service cannot be built here (no repository at `~/telcobright-projects/bucket-next` or no `go`);
`-Dbucketnext.bin=<path>` names a built binary, `-Dbucketnext.repo` / `-Dbucketnext.ref` what to build. The live fixtures
(`live/BucketNextBinary`, `live/LiveShards`) ship in this module's test-jar for the other modules' live proofs.
