# L2b — the prepaid engine on ONE record over all tiers (mem-ledger's batch), the LedgerDb, purchase

**To:** the seed agent (worktree `routesphere-seed/worktrees/prepaid-batch`, branch `prepaid-batch` off `routesphere-flow` at the L2a
merge) · **From:** the architect · **Date:** 2026-10-27 02:57 +0600
**Why:** the owner (2026-10-26): "perform crud atomically to all tiers (either success in all tiers or fail all)" · "multiple cruds
targeted at multiple db (admin+resellers) are batched and written as an atomic log in memledger" · "the mem-ledger channel/db shall be
a standard db feature offered by the seed framework, which entities to load in the cache for crud shall be configurable, but shall
match entity name prefix … we need to signal the cache from change-wire/prime-context feed through kafka … then the cache shall reload
the new entities." L1 brought the batch into mem-ledger main (**6523614**); L2a moved the call's money into `seed-prepaid` behind ONE
engine with the parity suite. This round makes every money operation ONE WAL record over all tiers.

**Read first, in this order:** routesphere `docs/architecture/prepaid-engine-design.md` §3, §5, §9 (ledger DB + signals, gaps S1–S7),
§10, §11; the L2a report `docs/agents/L2a-prepaid-report.md` (the map, the gap list, what it left for you); mem-ledger
`docs/agents/L1-multi-db-batch-done.md` and the README section "The multi-tier batch" (what a record is, the order of the steps, the
refusals, the one-RDBMS limit); `routesphere-seed-flow-requirements.md` R0. Then the code: `seed-switch-ledger` (`internal/Books`,
`MemLedgerPort`, `AccountLocks`, `AddTierLive`, `NewRowsOfATier`, `TwoEntities`, `OrphanReaper`, `api/*`), `seed-prepaid` (all),
`seed-idgen` (`spi/IdSource`, `api/IdKind`, `testkit/InMemoryIdSource`), `seed-context` (the doorbell / `tenant_created` consumers).

## 0 · The mem-ledger you build against

The seed's coordinates stay `com.telcobright:mem-ledger:1.0.0-SNAPSHOT`. Build mem-ledger main **6523614** from a `git archive` into
YOUR private repository (`mvn -o install -DskipTests`), and prove it by class bytes (`javap` shows `execCrud(WALEntryBatch)` on
`MemLedgerImpl` and `addDatabase` on `MemLedger`) and by the dependency tree of the seed. Never `~/.m2/repository`.

## 1 · The items (one commit each; every rule broken once and seen red; the suite before and after in the report)

| # | item | what | the proof |
|---|---|---|---|
| 1 | **One write path: the unit of work.** `Books` gains `internal/UnitOfWork`: it collects the post-images Books builds today (balance move, debit, reparent, reserve row open / grow / delete) as entries `(tier, entity, op, id, post-image)` and `commit(what)` sends them as ONE `MemLedger.execCrud(WALEntryBatch)`; a refusal → `LedgerFault` in words, nothing changed in any tier. Every existing single-step Books method becomes a one-entry unit (ONE write path, no `execCrud(CrudRequest)` left in the seed's money code — grep in the report). Inside a unit, a row written earlier in the same unit is read from the unit (not the cache); two moves of the same row in one unit are merged into one post-image (mem-ledger refuses the same row twice). `MemLedgerPort` keeps its idempotent references. `AddTierLive` becomes a plain `addDatabase(tier)` call. | a unit over 3 tiers = ONE record (count the real queue's records before/after on the lab, not a mock); a refused entry → no row in any tier, the record count unchanged; the merge rule; the mutants: commit per entry, read the cache instead of the unit |
| 2 | **All tiers in one record.** `admit` rates and authorizes EVERY tier first (reads only, under the seed's account locks), then ONE unit over all tiers; `renewWindow` computes every tier's grant first (the narrowest tier decides, as the live call) then ONE unit; `finish` settles every tier in ONE unit; CHARGE_NOW debits every tier in ONE unit. The seed's `AccountLocks` for one unit are taken in ONE global order (tier, accountId) and released after the commit. The saga's compensation for an in-process refusal goes (nothing was written); `OrphanReaper` stays for crashed sessions. | the parity suite (P1–P21 + the SMS-shaped tests) green with the SAME assertions — name any @Disabled gap this item closes; new: one record per business operation; a refused 3rd tier leaves tiers 1–2 untouched (balances, no reserve rows, zero records); a lock timeout on the 3rd tier the same; two sessions over the same 3 accounts in opposite tier order from two threads both finish (no deadlock); mutant: unsorted lock order → the two-thread test times out |
| 2b | **Durable references** (L2a report §6.2): today the idempotency memories (`MemLedgerPort.answered`, `ClosedTiers`, `Charges`) live in memory and are bounded. The reference that makes an operation happen once travels IN the operation's unit: a hold's reference is its reserve row (already); a CHARGE_NOW charge and a refund carry a durable reference row in the SAME unit, so a restart can neither charge twice nor lose the refund's claim. Read the live SMS's debit (`PrepaidServiceWithCompensation` 440–516) and rtc-domain's existing money tables first; use an existing table if one fits, and a new table only with its DDL for both dialects in the report (no schema change applied anywhere). | a restart between the charge and its answer → the repeat charges nothing; a refund after a restart finds its charge; mutants: the reference written in a second unit, the reference not read |
| 3 | **LedgerDb, a seed feature** (design §5.2, §9.3): `dependencies/LedgerDbSettings` (values only, R0) — the entity names or name prefixes to cache, the cache-only tables, the WAL/queue paths; the tiers come from the tree, never configured. Signals: `tenant_created` → `addDatabase(tierDb)`; the doorbell `config_event_loader_<root>` `changes` (`tier.table:OP`): an INSERT on a cached table → a reload of new rows, coalesced (`reloadNewRows()` whole — the targeted per-(tier, entity) reload is a mem-ledger follow-up: name it in the report, do not write it); `*:SNAPSHOT`, `what=start`, `what=stream-open` → a full catch-up; UPDATE/DELETE of the ledger's own tables → nothing (the ledger is their writer). `TwoEntities` relaxed to "exactly the configured entities". | a doorbell with `res_9.packageaccount:INSERT` → one reload; 50 doorbells in 1 s → one or two reloads; `packageaccountreserve:UPDATE` → none; a new tier by `tenant_created` → its rows served without a restart; mutants red |
| 4 | **Purchase.** `PackagePurchase` (rtc-domain) cached as the third money entity; `prepaid.admin().purchase(tier, partnerId, offer, reference)` = ONE unit: the purchase row + its account(s) (+ the opening balance); ids from an `IdSource` the app passes (seed-idgen `LONG` counters started above today's max per table — the testkit's `InMemoryIdSource` in tests; R0: no default service url in the seed); a repeated reference → the first answer, nothing written twice. `accounts(tier, partnerId)` and the expiry `AccountOrder` uses come from the cache (closes S4). | purchase → one record with every row; a repeat → no second row; an expired purchase's account is skipped by the order; mutants red |
| 5 | **The roads as plain Java** on `PrepaidAdmin` (the app mounts REST, the seed has no server): purchase · credit (+ reparent) · accounts · balance, each refusing in words. | tests |
| 6 | **Report** `docs/agents/L2b-prepaid-batch-report.md`: per item the files, the reds pasted, the suite before (the L2a merge head) and after, the grep of item 1; the proposed change-wire property for S3 (exclude `packageaccountreserve` from the feed: a line in `~/fullstack-work/proposed/change-wire/switch-btcl.properties`, proposed only, not applied); what L3 (ad-sphere) needs; the open decision D1 (one writer per account across microservices) as you met it. | — |

**Open with the owner, do not decide:** D10 — the live SMS charges only the LAST tier of the chain; the seed's CHARGE_NOW charges every
tier (the design). Keep the seed's as L2a made it; the SMS port is not this round.

**Not this round:** ad-sphere (L3); any change under routesphere-core, statewalk-v2-call, prime-context or change-wire (proposals only);
mem-ledger (it is merged; a need found → the report); `updateWithDelta`.

## 2 · Rules that stand

Java 21. Maven with YOUR private repository (`bash ~/arch-drafts/tools/private-m2.sh ~/l2b-work/m2`, then `-Dmaven.repo.local=~/l2b-work/m2`),
offline and under `flock /home/mustafa/telcobright-projects/.one-maven.lock` — never install into `~/.m2/repository`. statewalk 4.1.0
only (the resolved version, proven by the tree). `date` before any time you write. Commit this brief first as the round's first commit.
The commit trailer `Co-Authored-By: <the model you run as> <noreply@anthropic.com>`. **No push.** EVERY commit waits for its test run's
exit status. Nothing listens on a port (H2 in process, as `LedgerLab`); scratch under `~/l2b-work`, never /tmp. No sudo, no systemctl,
no `pkill -f`. No password or secret anywhere (a lab password only by NAME from `~/.lab-env/`, never on a command line). The seed has NO
config file, no main, no runnable jar (R0): every value is a parameter. A test that fails for an unreachable service: stop and report,
do not fix the code. Top-level methods read as named business steps (≤ ~10 lines; loops one layer down); every class ≤ ~300 lines; the
house package shape. A mutant's original kept on disk and restored with a FRESH mtime. A State line per item in `~/l2b-work/STATE.md`
(a stop or a usage limit must lose nothing). Nothing left running. The merge is mine.
