# C1 — the cached ledger (CRUD) in the seed · report

**Round:** C1 of the owner's ruling of 2026-10-28 (design `docs/architecture/prepaid-engine-design.md` §14, routesphere main 95f46eff).
His go: "go" (2026-10-28 ~02:50). Branch `c1-crud` in routesphere-seed, off `routesphere-flow` ec05888. Built against mem-ledger 535ccc0.
Nothing pushed; nothing deployed; no box touched.

## What the seed offers now

```java
CachedLedger ledger = kit.ledger().cachedLedgerOf(root).orElseThrow().withIds(ids);
ledger.get(tier, PackageAccount.class, 42L);                                 // a copy
ledger.find(tier, LedgerPurchase.class, Map.of("idPartner", 7L));            // copies, by key

CachedLedger.Unit unit = ledger.unit("topup:" + paymentRef);                 // a repeat answers the first
long purchase = unit.insert(tier, newPurchase);                              // a null key is minted now
unit.update(tier, PackageAccount.class, topUp, Map.of("idpackagePurchase", purchase));
unit.add(tier, PackageAccount.class, topUp, "balanceAfter", amount);
unit.require(tier, PackageAccount.class, other, Check.equalTo("balanceAfter", read));
unit.requireNone(tier, PackageAccount.class, Map.of("idpackagePurchase", p, "uom", "TF_min"));
CachedLedger.Outcome outcome = unit.commit();                                // Committed(keys, rows, repeated) | Refused(reason)
```

## Items

| # | where | what |
|---|---|---|
| 1 | seed-sessionflow `api/CachedLedger` | the API: `get`, `find`, `withIds`, `unit()`, `unit(reference)`; `Unit`: insert, update, add, delete, require, requireNone, commit; `Check`; `Committed` / `Refused`. No mem-ledger type in the base (ModuleShapeTest's rule) |
| 2 | seed-sessionflow `spi/LedgerPort` | `cachedLedgerOf(root)`, default empty; `PerRootLedger` routes it by the root |
| 3 | seed-switch-ledger `internal/` | `LedgerCrud` (reads, gate, ids), `CrudUnit` (the commit), `CrudMoves` (each move on the unit of work), `RowFields` (fields by name, copies), `UnitAnswers` (references in memory, one table per MemLedger) |
| 4 | `AccountLocks` | a lock is one ROW's: (tier, entity, id); an account's key is unchanged, so a unit and a session's reserve on one account take the same lock; the global order for accounts is unchanged |
| 5 | `MemLedgerPort`, `OwnedMemLedgerPort` | `cachedLedgerOf` answers the root's own; the owned port puts its gate around every read and commit (a new tier never loads under a unit) |
| 6 | `api/Credit` | credit and reparent are now CRUD units — same words, same answers, same lock. The `SwitchLedger`'s credit door shares the port's cached ledger, so a reparent through it also refreshes the partner → accounts index (before, only the port's reparent did). `Books.reparent` removed (no caller) |

## The rules it keeps

- A balance moves by `add` (before = old, after = old + delta, last = delta — as the live recharge). A `set` of `balanceAfter` needs a
  `require … equalTo` of the value it replaces, earlier in the same unit, or it is refused at once.
- Reserve rows and charge references are read-only through the CRUD.
- A caller's mistake → `IllegalArgumentException` at once. What the rows say at the commit → `Refused`, nothing written. A ledger that
  does not answer, or a lock not free in time → `LedgerFault`.
- A reference's first answer: in memory always; durable where the ledger keeps `packageaccountcharge` (row `unit:<reference>`, its
  account column = the first key). Without that table a restart forgets it — pinned by a test, open as C2-b.

## Tests added

| test | engine | what it shows |
|---|---|---|
| `CachedLedgerTest` (10) | real MemLedger, real WAL, H2 | copies; find by fields; a unit over two tiers = ONE record, minted keys in the DB; repeat after a restart; refusals write nothing; a check sees the moves before it; balance set only under a check; sessions' rows read-only; no purchases cached → refused in words; the partner's accounts follow a unit's moves; memory-only references forget at a restart |
| `CachedLedgerLocksTest` (5) | same | a unit waits for a session's account lock; 40 adds + 40 charges on one account lose nothing; the same `requireNone` runs one after the other; two racing welcome grants → one written; a unit waits while a tier loads (gate) |
| `TelcoRestWritesAsUnitsTest` (4) | same | TelcoREST's top-up (one record, a repeat = `AlreadyApplied`), upgrade carry-over (refused when a session moved the old balance, lands on a fresh read), welcome once, a postpaid limit lowered |
| `PerRootCachedLedgerTest` (1) | pure | each root's own cached ledger; none for a root without one |

## Mutants (each rule broken once, seen red by a failing test, restored by hash)

| mutant | red in |
|---|---|
| a unit's add takes a lock of its own (not the session's) | `CachedLedgerLocksTest` 2, `CreditTest` 1 |
| an account's lock key differs from its row key | `CachedLedgerLocksTest` 2, `CreditTest` 1 |
| `requireNone` takes no shared lock | `CachedLedgerLocksTest` 1 |
| a balance set without a check | `CachedLedgerTest` 1 |
| an account's add skips before/last | `CachedLedgerTest`, `TelcoRestWritesAsUnitsTest`, `CreditTest` |
| no durable reference row | `CachedLedgerTest` 1 |
| no memory of references | `CachedLedgerTest` 1 |
| a refused unit still writes | `CachedLedgerTest` 1 |
| reads hand out the cached row | `CachedLedgerTest` 1 |
| reserve rows writable | `CachedLedgerTest` 1 |
| the owned port skips its gate | `CachedLedgerLocksTest` 1 |
| the per-root ledger answers any root's | `PerRootCachedLedgerTest` 1 |
| the partner → accounts index not told | `CachedLedgerTest` 1 |
| a unit forgets the keys it minted | `CachedLedgerTest` 1 |
| moves staged in reverse order | `CachedLedgerTest` 3, `TelcoRestWritesAsUnitsTest` 2 |
| a repeat writes again | `CachedLedgerTest` 2, `TelcoRestWritesAsUnitsTest` 1 |

16 of 16 red; none survived.

## Gates (clean git archives, the PostgreSQL lab up, Maven offline on the private repo)

| what | commit | result |
|---|---|---|
| seed | 3acaa5f | **866/0/0/9** (846 before + 20 new; the PostgreSQL lab test 3/0) |
| ad-sphere `p2-adflow` on that seed (an SPI change is checked against its users) | ed95e93 | **1005/0/0/7** + its PostgreSQL ITs 3/0, 2/0, 4/0/1 — the same as L3; the runner jar carries the C1 classes |

## A finding carried to C2

mem-ledger's WAL consumer rolls back a batch the database refuses, reads it again, and **stops after 10 failures in a row**
(`WALConsumerThread`, `MAX_CONSECUTIVE_ERRORS = 10`): the root's money then lives in RAM only. Every row the seed writes today is built
by the seed itself; a CRUD open to callers could carry a row the database refuses (an empty NOT NULL column, a text too long, a number
too big, an account on a purchase the tier does not hold). C1 has no outside caller yet (only `Credit`, whose rows are the seed's). C2
refuses such rows before the WAL; the purchase → package key stays the caller's (the package table is not cached). A mem-ledger round
(a refused batch parked with an alarm, the consumer goes on) is the owner's to decide.

## Open with the owner

| # | question | my default |
|---|---|---|
| L4 | the cached entity list per root or per database; live or at restart | per root, at restart |
| C2-a | `creditLimit` (not in rtc-domain's `PackageAccount`) and `paymentRef` (not in `LedgerPurchase`) | decide in C2 with the contract tests |
| C2-b | durable references for CRUD units: adopt `packageaccountcharge` where TelcoREST's writes arrive (§12 did not adopt it for the ad lane) | adopt it there |
