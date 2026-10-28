# C4 — the officer's money on CRUD units, the catalog through the cached ledger · report

**Round:** C4 of the owner's ruling of 2026-10-28 (design `docs/architecture/prepaid-engine-design.md` §14), with the owner's three
answers of 06:0x ("go as you recommend best"): (1) the catalog (`package`, `packageitem`) goes through the cached ledger too; (2) a top-up
follows TelcoREST's rule; (3) a correction may take money away, with a reason, never below zero. Branches: routesphere-seed `c4-catalog`
(off c2-expose 6de9bf9), ad-sphere `c4-money` (off c2b-columns 36a990a). Nothing deployed; no box touched.

## Why the portal needed it

```
officer screen ─▶ ad manager BFF ─▶ ad-sphere money road ─▶ the root's cached ledger unit ─▶ mem-ledger WAL ─▶ the tier's tables
                  (never writes money)   (SwitchMoney)          (ONE record per write)
```

Before C4, on production (the switch's ledger, no BSS):

| Action | Before | After C4 |
|---|---|---|
| the package catalog | no road on the ad service ("not available on this deployment") | `GET/POST /packages`, `PUT /packages/{id}`, `…/active`, `…/items` — CRUD units |
| a sale of a package | the BFF sent `idPackage`; the service asked for items (400); its items path wrote package id **0** — a tier's foreign key refuses it and the write-behind would stop | `POST …/purchases {idPackage, discount, reference}`: telco-rest's purchase-package, ONE unit; the ledger checks the package in the catalog before the WAL |
| a top-up | 409 `NOT_ON_THE_SWITCH_LEDGER` | telco-rest's purchaseTopup, ONE unit: the TopUp purchase, the ONE cash wallet moved onto it and credited (opened when absent; the TopUp package made with the first top-up) |
| a correction | positive only | either way; a negative one only while the balance covers it (409 `BALANCE_TOO_LOW`) |
| the officer host on production | `prepaid.backend: "none"` only — no money screens | the word `"switch"`: the money screens call the ad service's roads |

## Items

| # | where | what |
|---|---|---|
| S1 | seed `api/LedgerPackage`, `api/LedgerPackageItem` | the catalog's flat twins (rtc-domain's `Package` carries JPA relations a MemLedger cannot load) |
| S2 | seed `internal/LedgerEntities` | known entities `Package`, `PackageItem` with their shapes (the narrower of PostgreSQL's DDL and RTC-Manager's MySQL template: name unique, `id_UOM` 20, `prefix` 200, percents (5,2)); the word `package` keeps naming every table (the catalog too), the new word `catalog` names the two beside a list of money tables |
| S3 | seed `internal/RowRules`, `LedgerCrud` | a purchase or an item made or moved onto a package its tier's catalog lacks is refused before the WAL; a package an item or a purchase names is never deleted (deactivate it); the catalog is writable |
| A1 | ad-sphere `lane/SwitchCatalog` | the catalog roads' logic on CRUD units (the officer book's rules) |
| A2 | ad-sphere `lane/SwitchMoneyWrites` | sale, top-up, correction — each ONE unit; the reference = the purchase's `paymentRef` (unique): a retry answers the first, after a restart too; another partner's or another kind's reference: 409 `REFERENCE_TAKEN` |
| A3 | ad-sphere `lane/SwitchMoney`, `AdFlow.cachedLedger`, `AdSphereRuntime` | the writes delegated; the root's cached ledger with bucket-next ids |
| A4 | ad-sphere `api/AdOfficerResource`, `AdDoor.catalog` | the five catalog roads (a tier off the switch's ledger: 404 in the words of a missing road — the BFF still reads "no catalog yet"); `POST …/purchases` takes `idPackage` |
| A5 | ad-sphere `api/dto/AdOfficerDtos` | `PurchaseDraft` + `idPackage`, `discount`, `paymentMethod`, `paymentRef`; `TopupDraft` + `validity`, `paymentMethod`, `paymentRef`; `PackageRow`, `PackageItemRow`, `PackageDraft`, `PackageItemDraft` |
| P1 | ad-officer-host `lib/config.mjs`, DEPLOY.md | the ledger word `switch` beside `none` |
| P2 | ad-manager-bff `lib/ads/ledger/backend.mjs` | `switch` = the stand-in seam over the ad service's client, the cancel off by the owner's policy whatever the config |
| P3 | ad-manager `screens/Accounts.tsx` (the credit dialog), BFF `adsRoutes.mjs` | a correction either way: Add / Take away (sent as a negative amount; refused on the screen beyond the balance, and by the ledger in words); the history line says −N, not +-N |
| A6 | ad-sphere `api/LedgerRoadsResource`, `dependencies/LedgerRoadsSettings` | the seed's CachedLedgerRoads mounted at `/api/v1/ledger` (units, row, rows) — for TelcoREST later; CLOSED unless `adsphere.ledger-roads.token-hash-var` names a variable holding `salt:sha256` (secreteer), nothing allowed unless named per table; a missing or malformed variable keeps them closed and says so |
| A7 | ad-sphere `docs/ad-as-call/P2-deployment-prod.template.json` | the root's `ledger.entities` (money tables + `catalog`, reserves cache-only) and `adsphere.ids.shards` (`@@BUCKET_NEXT_SHARDS@@`, devops fills) |
| D1 | ad-sphere `OFFICER-API.md` §6.1, §6.2 | the roads as they are now; the mounted ledger roads |

## Found while gating: the ledger's database rights (fixed)

prime-context's provisioning gave the switch ledger's role (ARCH-0077-F item 2, the bed's older model: "the BSS inserts both") SELECT and
UPDATE on the accounts, SELECT on the purchases, nothing on the catalog — and REFUSED a config that gave more. Since L3 the ledger itself
creates purchases and accounts, and since C4 the catalog: on a production tier (no BSS) the first sale or top-up would have been refused
by PostgreSQL ("permission denied") and the write-behind stopped. No test saw it: every test's ledger login owned its tables.

| Repo · commit | Fix |
|---|---|
| prime-context `ledger-grants` d398807 (off `ledger-columns`) | the ledger's three lists default to the five money tables; nothing of them is barred to their creator; one writer of a balance stays (the BSS never moves a balance nor touches a reserve). Unit 4/0 + 13/0; PostgreSQL labs: ProvisioningLabTest 2/0, LedgerGrantsLabTest 7/0 (a package, an item, a purchase, an account made, changed and taken away AS the ledger's login), TwoWritersLabTest 5/0; the mutant (the old bar back) red |
| seed `c3-slim` c74b997 | the start check reads the ledger login's rights on PostgreSQL (SELECT, INSERT, UPDATE, DELETE on every cached table): a missing right refuses the start in words. TierRightsLabTest 1/0; the mutant (rights unchecked) red |

**The bed (B6):** a BSS that still inserts the money rows beside the ledger is a second creator (its ids from the database's counter,
the ledger's from bucket-next — they collide): on a switch-ledger root the BSS calls the switch's road (design §13) — the owner's
decision with B6.

## What the deployment needs

- The root's bootstrap: `ledger.entities: packageaccount, packageaccountreserve, packagepurchase, catalog` (the bed's B6 line + `catalog`).
- `adsphere.ids.shards` (bucket-next): every write mints its ids there; none = 503 in words.
- The officer host: `prepaid.backend: "switch"`.
- Every tier has `package` and `packageitem` (prime-context's changelog has them) and changelog 006, and the ledger's login holds the five tables' rights (prime-context d398807's provisioning) — the seed's start check refuses otherwise, in words.

## Mutants

Every mutant: one rule broken, the named tests run, RED by a failing test (no compile error), the file restored by its hash.

| set | mutants | red |
|---|---|---|
| seed | `c4-package-unchecked`, `c4-package-delete-allowed`, `c4-word-package-exact`, `c4-word-catalog-missing`, `c4-catalog-not-writable`, `c4-name-not-unique` | 6 / 6 |
| ad-sphere (the PostgreSQL IT) | `c4-ad-reference-forgotten`, `c4-ad-wallet-not-moved`, `c4-ad-negative-unchecked`, `c4-ad-discount-not-whole`, `c4-ad-vat-on-price`, `c4-ad-other-kind-answered` | 6 / 6 |
| portal (node) | `c4-host-switch-dropped` (premises test), `c4-bff-switch-cancel-from-config` (prepaidBackend test) | 2 / 2 |

## Gates

| repo · commit | what | result |
|---|---|---|
| seed `c4-catalog` 7cfbaf8 | clean archive: the suite, the PostgreSQL lab in | 899/0/0/9 (+5: CatalogThroughTheLedgerTest) |
| ad-sphere `c4-money` 75f14d0 on seed 7cfbaf8 | clean archive: the suite + the switch-ledger PostgreSQL ITs | 1007/0/0/7 + AdSwitchLedgerPostgresIT 3/0, AdSwitchMoneyPostgresIT 2/0 (the C4 IT: tables WITH the database's foreign keys — a sale, two top-ups, the wallet moved, repeats, corrections both ways; no batch rolled back), AdTierCreatedPostgresIT 4/0/1 |
| ad-sphere `c4-money` 7c1fc28 (the portal) | the ad manager's typecheck · the BFF's tests · the officer host's tests | tsc clean · 928 + 1 skipped / 0 failed (with the app's packages installed; without them 5 reds of the environment: the icon face, a module the app ships) · 55/55 |

## Open

| # | what | default |
|---|---|---|
| C4-a | the advertiser's purchase REQUESTS live in the BFF's own state file on a deployment without a BSS (not money: the approval makes the purchase through the ledger) — one file on one host | keep for phase 1; a table later |
| C4-b | the purchase answer: the ad service answers `{tenant, what, id, rang}`; the BFF's history line then says `package <id> · total ?` | the BFF reads the purchase back (portal polish) |
| C4-c | `PrepaidAdmin.purchase` (L2b) is no longer called by ad-sphere; it still writes the offer's package id unchecked | C3 drops it or guards it |
| C4-d | the cancel stays off by the owner's policy (R17-c) | as it is |
| C4-e | two FIRST top-ups of one advertiser at the very same moment could each open a wallet: a unit locks the rows it writes, not the partner (later top-ups find the one wallet and lock it) | **FIXED 2026-10-28** (ad-sphere c4e-first-topup 90381cc, merged p2-adflow fcf78dd): the unit that opens the wallet requires the partner has no TopUp purchase at its commit, the one that makes the TopUp package that there is none; the loser is tried once more and moves the winner's wallet — AdSwitchMoneyPostgresIT's two eight-racer cases, 3 mutants red |
| C4-f | the FULLSTACK lab's journey (step a) still credits through prime-context's old road, and the lab runs no bucket-next — the C4 money writes need one | move the journey onto the officer host's top-up with a lab bucket-next, rerun before the bed rehearsal |
