# seed-idgen — the ids every product mints from bucket-next

The owner, 2026-10-09: **partner ids are unique within a tenant**, by an external unique-id generator for partner CRUD. The
generator is **bucket-next** (`git@github.com:pialmmh/bucket-next.git`): a sharded id service, one Go binary per shard, shard k of
N handing out k, k+N, k+2N, … for a counter, so no two shards ever give the same id and no shard asks another.

## Use

```java
IdSource ids = BucketNext.builder()
    .shards("http://10.10.199.21:7001,http://10.10.198.21:7001")     // from the product's config file; no secret
    .timeout(Duration.ofSeconds(2))
    .build();

int idPartner = new PartnerIds(ids).next("btcl", highestInTree);    // the tree's highest idPartner today
```

| Piece | What it is |
|---|---|
| `spi.IdSource` | the port: `mint(entity, kind, count)` and `mintAbove(entity, kind, count, highestInUse)` |
| `api.PartnerIds` | the owner's rule: one counter per tenant tree, entity `partner.<root>`, type `int` (`partner.idPartner`) |
| `api.IdKind` | the service's types: `int`, `long`, `snowflake`, `uuid8/12/16/22` |
| `api.IdServiceUnavailable` | no shard answered: nothing was minted (fail closed — never a local fallback) |
| `api.IdRefused` | the service refused the request itself (`Type mismatch`, `Invalid startValue`): no other shard is asked |
| `dependencies.BucketNext` | the builder: every shard's base URL, the time one call may take |
| `testkit.InMemoryIdSource` | a cluster in memory with the service's shard math, for products' tests; a shard can be taken down |

## The rules it keeps

- **Every shard serves.** A call goes to the next shard in the rotation; a shard that is down, times out, fails on its side (5xx) or
  has no values left (409 `Range exhausted`) is skipped and the next one asked — each once. A request the service refuses (another
  4xx) is refused everywhere.
- **A tree that existed before the generator keeps its ids.** `mintAbove` raises the shard that mints: registered with
  `startValue = highest + 1` when the shard does not know the entity, moved forward when it would hand out an id in use. Each shard
  keeps its own counter, so the shard that mints is the one raised — after a failover too. A move the service calls backward (409)
  means another process moved it past already. Partner creation is human CRUD, so the extra calls cost nothing that matters.
- **One counter per tree.** `int` counters of different entities overlap on purpose: two trees' partners may share a number, a tree
  never repeats one (the owner's rule is per tenant).

## Tests

`PartnerIdsTest` (the rule on the in-memory cluster: 600 creators on 3 shards, all unique, all above the tree), `BucketNextSourceTest`
(the client against stub shards that act like bucket-next: the wire, the failover, the refusals, the raise), `BucketNextLiveTest`
(two REAL shards started on 127.0.0.1 from the binary — 400 concurrent mints, a shard killed and restarted, 460 ids, none repeated,
all above the tree; skipped when the binary is absent, `-Dbucketnext.bin=<path>` to name one).
