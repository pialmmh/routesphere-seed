# L1b + L2c — a key the caller mints reaches the database (mem-ledger), then the purchase makes its accounts (seed)

**To:** one agent, two stages · **From:** the architect · **Date:** 2026-10-27 14:40 +0600
**Why:** the L2b report §7.1: rtc-domain's `PackageAccount.id` is `@GeneratedValue(IDENTITY)`; mem-ledger's `EntitySQLPersister` leaves
such a column out of every INSERT, so an account made through the ledger with a minted id gets ANOTHER id in the database (MySQL's
auto-increment) — the cache and the database disagree. The purchase road refuses until this is fixed. The owner, 2026-10-27, chose the
fix: **"go with A"** = mem-ledger writes the id the new row already carries; rtc-domain is NOT changed (the live voice keeps its classes).
The ids come from bucket-next — the owner: *"we have uniqueid service for id generation. uniqueId can have multiple sharded instances,
routesphere-seed shall have config to connect to all instances, kind of like connecting to multiple kafka instances in the cluster."*
seed-idgen already IS that client (`dependencies/BucketNext`: every shard's URL in one setting, rotation, a down shard skipped, fail
closed). The rule that comes with A: in a lane on the new engine, every writer that creates accounts takes ids from bucket-next.

**Read first:** `/home/mustafa/telcobright-projects/bucket-next/README.md` (types, shard math, `init`/`reset` per shard); the seed's
`docs/agents/L2b-prepaid-batch-report.md` (§0, §7.1, the purchase item); `seed-idgen/README.md`; mem-ledger
`docs/agents/L1-multi-db-batch-done.md` (§item 3 the idempotent upsert, §2 the lab) and `core/EntitySQLPersister.java`,
`core/metadata/EntityMetadataExtractor.java`, `core/SqlDialect.java`.

## Stage 1 · mem-ledger (worktree `routesphere/mem-ledger/worktrees/minted-keys`, branch `minted-keys` off `main` 6523614)

| # | item | what | the proof |
|---|---|---|---|
| 1 | **Keys minted by the caller, opt-in per entity** | A builder setting on the ledger (your name, house style, e.g. `keysMintedByTheCaller(Class<?>…)`). For such an entity EVERY INSERT path writes its key column: the consumer's insert, the extended (batch) insert, the replay's upsert (which now conflicts on that key — idempotent, like any other keyed row) and the start's replay. ONE helper decides "the columns an INSERT writes" and every path uses it (no second copy of the rule). A minted-key entity whose post-image has a NULL key → the batch is refused in words before anything is written (nothing in any tier, no record). **Default off:** for every entity not named, the SQL text is byte-identical to 6523614. | a row with key 5001 → the database row is 5001 after the consumer (MySQL lab, PostgreSQL lab, H2); a crash-restart replays it → still one row 5001, no duplicate; a restart that reloads from the database → the cache holds 5001; a test pins the INSERT and upsert TEXT of the entities NOT named (PackageAccount, PackageAccountReserve, PackagePurchase as the live voice uses them) = 6523614's; a NULL minted key → refused, zero records. Mutants red: the setting ignored on each path in turn (consumer, extended insert, upsert, start replay); the key column left out of the upsert's conflict |
| 2 | **PostgreSQL's identity column** | Read the PostgreSQL DDL the ad lane uses for `packageaccount` (prime-context's PostgreSQL migrations; rtc-domain if it has one). If the key is `GENERATED ALWAYS AS IDENTITY`, PostgreSQL refuses an explicit value: the PostgreSQL dialect adds `OVERRIDING SYSTEM VALUE` for minted-key entities only. If it is `BY DEFAULT` or `serial`, a plain INSERT works. State which, with the file and line. Write in the report that PostgreSQL's sequence (and MySQL's auto-increment, which DOES move) is then not the source of ids — fine under the one-creator rule. No schema change anywhere. | the lab table made with the SAME DDL as the ad lane's; mutant: the override dropped (if needed) → red |
| 3 | **Report** `docs/agents/L1b-minted-keys-done.md`: per item the files, the reds pasted, the suite before (a clean copy of 6523614) and after (a clean copy of your last commit), the grep that shows ONE "columns an INSERT writes" helper. Hand back after this stage's clean copy, then go on to stage 2 (do not wait for my merge). | — |

## Stage 2 · the seed (worktree `routesphere-seed/worktrees/purchase-ids`, branch `purchase-ids` off `routesphere-flow` 042feef)

Build mem-ledger from YOUR stage-1 commit (a `git archive` of it) into your private repository and prove it by class bytes (`javap`
shows the new builder setting) and by the seed's dependency tree. The seed's coordinates stay `com.telcobright:mem-ledger:1.0.0-SNAPSHOT`.

| # | item | what | the proof |
|---|---|---|---|
| 4 | **The purchase makes its accounts** | Where the seed builds its ledger (LedgerDb / the switch ledger), name `PackageAccount` (and any other money entity the purchase CREATES whose key is `@GeneratedValue` — check `PackagePurchase`, the reference row) as minted by the caller. `Purchases` stops refusing. A purchase = ONE record: the purchase row + its account(s) with ids from the app's `IdSource` (`mintAbove` over the highest id the tier holds, as L2b wrote it). | purchase on H2 + MySQL lab + PostgreSQL lab: after the consumer the database ids = the cache ids; a restart → the same accounts by the same ids, a hold opened on the account before the restart is found and settled after it; a repeated reference → the first answer, nothing twice. **Two REAL bucket-next shards** (the `BucketNextLiveTest` pattern: the binary at `~/telcobright-projects/bucket-next/bin/bucket-next`, two shards on 127.0.0.1, skipped when absent): N purchases at once over 3 tiers → every account id unique, database = cache, a shard killed in the middle → the other serves, nothing repeats. Mutants red: the entity not named (→ the database id differs), `mintAbove` replaced by `mint` over a tier that already holds ids |
| 5 | **The app's setting, in words** | `seed-prepaid` README + `PrepaidAdmin` javadoc: the app passes `BucketNext.builder().shards(<one config value: every shard's URL, comma-separated>)` — like Kafka's server list, but EVERY shard is named (a shard does not tell about the others); the seed has no config file (R0). The one-creator rule: in a lane on the new engine nothing else creates accounts in that table by auto-increment. | — |
| 6 | **Report** `docs/agents/L2c-purchase-ids-report.md`: per item the files, the reds pasted, the suite before (a clean copy of 042feef) and after (a clean copy of your last commit), the resolved mem-ledger and statewalk (4.1.0) in the tree; what L3 (ad-sphere) needs (the setting key, the shard URLs it waits for — the owner names the hosts). | — |

**Not this round:** rtc-domain (`@GeneratedValue` stays — that was option B, not chosen); routesphere-core and the live voice/SMS (they
build mem-ledger at their own submodule pointer; default off keeps them byte-identical anyway); ad-sphere (L3); prime-context /
change-wire (proposals only); bucket-next itself; mem-ledger's targeted reload (L2b §7.2).

## Rules that stand

Java 21. Maven with YOUR private repository (`bash ~/arch-drafts/tools/private-m2.sh ~/l1b-work/m2`, then
`-Dmaven.repo.local=~/l1b-work/m2`), offline (`-o`), under `flock /home/mustafa/telcobright-projects/.one-maven.lock` — never install
into `~/.m2/repository`. statewalk 4.1.0 in the seed (the resolved version, proven by the tree). `date` before any time you write.
Commit this brief first in EACH worktree as its first commit. The commit trailer `Co-Authored-By: <the model you run as>
<noreply@anthropic.com>`. **No push.** EVERY commit waits for its test run's exit status. Every process binds 127.0.0.1 and prints its
endpoints first. Scratch under `~/l1b-work`, never /tmp. Labs: your own PostgreSQL 16 container on 127.0.0.1:7544 as ARCH-0077-B4 made
it (rootless, trust on loopback, no password exists; name it `ml-pg16-l1b`), and the local MySQL with `LAB_MYSQL_PASSWORD` by NAME from
`~/.lab-env/mysql.env` inside the run's subshell — **never on a command line** (L1 slipped once; do not), lab databases dropped after.
WireGuard is UP — never reach 172.17.191.1, 10.10.191.x, 10.10.188.x, 10.10.175.x, 10.9.9.x or 103.95.96.77. A test that fails for an
unreachable service: stop and report, do not fix the code. No password in any file, command line or report. No sudo, no systemctl, no
`pkill -f` (stop by PID). Top-level methods read as named business steps (≤ ~10 lines; loops one layer down); every class ≤ ~300 lines;
the house package shape. A mutant's original kept on disk and restored with a FRESH mtime, checked by hash. A State line per item in
`~/l1b-work/STATE.md` (a stop or a usage limit must lose nothing). Nothing left running at the end (containers removed, lab databases
dropped, no bucket-next shard, no JVM). The merges are mine.
