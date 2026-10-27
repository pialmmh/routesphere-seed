# L3 — the ad lane on the prepaid library (seed + ad-sphere) · report

**Round:** L3 of the prepaid engine, done by the architect by hand (the owner's go 2026-10-27 ~21:55: "continue with the rest of the
development"). Branches `l3-prepaid` in routesphere-seed (off `routesphere-flow` 7fb9bb6) and in ad-sphere (off 9c881fc). Built against
mem-ledger 535ccc0 and statewalk 4.1.0. Nothing pushed; nothing deployed; no box touched.

## The owner's rulings this round carries out (2026-10-27)

| ruling (his words) | where |
|---|---|
| "two apis must: reserveBalance() and finalizeBalance()" | seed S1: `MoneyEngine.reserveBalance` / `finalizeBalance` (a pure rename) |
| "for ad we charge now, like sms, no refunds/adjustments are required" — full billing | ad-sphere `AdPrepaid`: `Admission.CHARGE_NOW`, no `finalize` |
| "packages … are the defaults for all services … then rate based deduction like pay as you go" · "best match/active package finding logic … shall be pushed to the prepaid lib" | seed S2: `PackageMatch` + the builder's `packageUnits` |
| "for calls, we calculate call's actual charge, return balance and apply the final charge" | seed S3: the builder's `actualCharge` |
| "a pre-processing step that inverts the customer and supplier leg after pre-routing" | seed S4: the base's named step `invertLegs`; ad-sphere's override |
| 9c881fc and the date-bound test go into L3 | ad-sphere B0, B1 |

## Items

| # | repo | commit | what |
|---|---|---|---|
| S1 | seed | 6744a2e | the two APIs by the owner's names — rename only |
| S2–S4 | seed | b620d0a | ONE package rule (`PackageMatch`, `packageUnits`), `actualCharge`, `finalize` optional for CHARGE_NOW, `invertLegs` |
| S5 | seed | cb96391 | a partner's purchases from the ledger's cache (`LedgerPort.purchasesOf`, `PrepaidAdmin.purchases`) — the officer's list |
| S6 | seed | 7e340ea | **a finding, fixed**: the ledger's catalog is prime-context's — ACTIVE purchases only, the operator's priority first; the package rule keeps the catalog's order |
| B0+B1 | ad-sphere | 4cd32bc | 9c881fc finished (OFFICER-API.md §4); the date-bound `LedgerTest` on a fixed clock |
| B2+B3 | ad-sphere | 2f3b1ea | the ad's money on the prepaid library; `invertLegs` |
| follow S6 | ad-sphere | bfb9cf7 | the package IT's tree in prime-context's order |
| B4 | ad-sphere | bd30663 | the officer's money roads of a tree on the switch's ledger (`SwitchMoney` over `PrepaidAdmin`) |


## Tests added or changed

| test | repo · engine | what it shows |
|---|---|---|
| `PackageMatchTest` (5) | seed · pure | the lane's units only, active only, the catalog's order (prime-context's), then cash; the operator's priority never undone by a sooner expiry; the three rules |
| `AdLaneOnPrepaidTest` (5) | seed · the real switch ledger | the ad on the library: charged at admission on every tier, the best matching package first, cash at the price when none matches, the end moves nothing |
| `InvertLegsStepTest` (1) | seed | the base asks `invertLegs` per candidate, before the payer is found |
| `PurchaseThroughTheLedgerTest` (+2, 1 changed) | seed · H2 | a partner's purchases from the ledger's cache; the ledger's catalog = prime-context's (a cancelled purchase never listed, the operator's priority, then the expiry) |
| `PurchasesTest` (changed) | seed · H2 | the partner's accounts in prime-context's order |
| `AdPrepaidTest` (2) | ad-sphere · pure | a video pays from a seconds package, a picture or a text from a views package; what a view costs in a package's units |
| `AdSwitchLedgerPostgresIT` (3, changed) | ad-sphere · PostgreSQL 16 | no hold row; the end moves nothing, shown or not (full billing); the best matching package pays one view at a time, the tiers above pay cash |
| `AdSwitchMoneyPostgresIT` (2, new) | ad-sphere · PostgreSQL 16 | a purchase through the ledger reaches the database under the minted id and the next view pays from it; a credit adds; expired and cancelled purchases stay out of the live accounts; every refusal in words, nothing written |
| `LedgerTest` (changed) | ad-sphere | the in-memory ledger on the test's own clock (was red since 06:00Z on every base) |

## Mutants (each rule broken once, seen red, restored by hash)

| mutant | red in |
|---|---|
| S2–S4 ×6: `s2-no-best-match-sort` (its rule REPLACED by S6: the catalog's order, never re-sorted — see `s6-resort-by-expiry`), `s2-units-ignored`, `s2-walk-ignores-the-lanes-units`, `s3-actual-charge-releases`, `s3-charge-now-must-finalize`, `s4-legs-never-inverted` | `PackageMatchTest`, `AdLaneOnPrepaidTest`, `SmsParityTest`, `CallParitySettlementOnPrepaidTest`, `InvertLegsStepTest` |
| S6 ×4 (a cancelled purchase pays; the ledger by id; the operator's priority ignored; the re-sort by expiry back) | `PurchaseThroughTheLedgerTest`, `PackageMatchTest` |
| B0 (the form's seconds win again for a video) | `VideoLengthIsMeasuredTest` 2, `ContentIngestTest` 2 |
| B3 ×7 (no prepaid; prepaid everywhere; a video pays views; seconds cost one; a view costs two; no cash price; legs not inverted) | `AdSwitchLedgerPostgresIT`, `AdCallStoryTest`, `AdRefundRuleTest`, `AdPrepaidTest` |
| B4 ×8: the switch money everywhere; nowhere; the partner not checked; a negative credit sent to the ledger; any account; the expired listed; the purchase's name with its unit; the door ignoring the switch | `TreeDoorRoadsTest`, `AdSwitchMoneyPostgresIT`, `AdDoorMoneyChoiceTest` |
| B4 ninth, SURVIVED → code removed: `SwitchMoney`'s own purchase-status filter (since S6 the ledger lists active purchases only — dead code) | — |

## The finding (S6)

Where a root's ledger caches `packagepurchase`, the walk read the partner's accounts from the ledger (L2b S4) — every account of the
partner's purchases, in account-id order. prime-context serves that catalog as ACTIVE + unexpired purchases only, ordered by the operator's
`onSelectPriority` (> 0), then the soonest expiry, then the older purchase, then the lower id (`AllCacheLoader.buildPartnerAccountMap`).
A CANCELLED purchase's accounts could pay; an operator's priority was lost; and my S2 re-sorted packages by expiry alone. Fixed in S6:
the ledger's catalog is prime-context's, the package rule keeps the catalog's order. The live voice is untouched (it does not run the
seed's engine). Rule for later rounds: before the ledger answers a read the tree answered, copy prime-context's filter and order, with a
test of each.

## Gates (clean git archives, the PostgreSQL lab l3-pg16 up, Maven offline on the private repo ~/l3-work/m2)

| repo | commit | result |
|---|---|---|
| seed | 7e340ea | 846/0/0/9 |
| ad-sphere | bd30663 (on seed 7e340ea) | plain 1005/0/0/7 (a clean copy skips SwitchSchemaScriptTest: no sibling prime-context) · ITs `AdSwitchLedgerPostgresIT` 3/0 · `AdSwitchMoneyPostgresIT` 2/0 · `AdTierCreatedPostgresIT` 4/0/1 (the old-mem-ledger case, by design) |

## Environment notes (no red of ours)

- `SwitchSchemaScriptTest` names the sibling prime-context checkout's HEAD in the script's header: a worktree run sees prime-context at
  50f23f1 (a bed-config-only commit) and goes red on the header line alone (the schema body is byte-identical); a clean archive finds no
  sibling checkout and skips the test.
- The PostgreSQL ITs need Chronicle Queue's `--add-opens` (the pom's argLine lacks them): run with `JDK_JAVA_OPTIONS`.
- Surefire never runs `*IT` classes in a plain run: a focused or mutant run's IT reports stay in `target/surefire-reports` and inflate a
  later count — the suite script now clears them first.

## Open with the owner

| # | question | my recommendation |
|---|---|---|
| B5 | "we know the ad length, so we end the session on time elapsed" + a user's close = a hang-up. The WiFi client contract (§4, §7) allows `/viewed` up to 150 s after `/shown` and a beacon every ≤ 30 s; today's portal never beacons. Ending a view at its length would end every view of today's portal as stalled — no free WiFi. With CHARGE_NOW the end moves no money; it decides the grant, the CDR's end and the live-views slot. | keep the contract for go-live; do B5 with the portal's beacon (≤ 5 s) and a contract change |
| B6 | the bed's ad-sphere-s2 config on radius-2: `adsphere.ids.shards = http://10.10.188.2:7001`; `journal.dir` outside radius-2's mirrored paths; `ledger.entities: packageaccount, packageaccountreserve, packagepurchase`; `ledger.cache-only: packageaccountreserve`; `packagepurchase` in every tier schema | needs his go (it changes the bed) |
| T1 | a top-up on the switch's ledger (the TopUp account onto a new purchase AND credited, one unit) | a seed road `PrepaidAdmin.topup` when he wants the button |
| T2 | a correction that takes money away | a debit road beside the credit, if needed |
| — | a view admitted but never handed to the phone is now charged (CHARGE_NOW, his "full billing") | confirm |

Nothing pushed. Nothing deployed. No box touched in this round.
