# ARCH-0117 · the seed's remote-fixes round (P3) — done

Branch `memledger-remote-fixes` (off `82704c0`), worktree `routesphere-seed/worktrees/memledger-remote-fixes`.
Round closed 2026-11-10 08:5xZ. The code head that was gated is **`14181ad`**; the commit that adds this report changes `docs/` only.

```
82704c0 ── 250bb0e a ── 8e141d0 b ── b632025 c ── a020f6f d ── dafa9ee e          (the build stage)
       └── fcb8fd6 c2 ── 4cbf062 a2 ── 3f47073 money ── 95e71a8 d2 ── 19b4152 b2 ── 14181ad b3   (the closing: review findings)
```

Every commit ends with the `Co-Authored-By` line. Nothing was committed to `memledger-remote` or `memledger-shared`, nothing was
written in recall-db's or ad-sphere's working trees.

## 1. What the round gives ad-sphere

| Item | What the seed does now | Commit(s) |
|---|---|---|
| (a) plan-step fault | A fault of a ledger **on the wire** in the plan step (the partner's accounts, the catalog) is the admission's system fault (`BILLING_SYSTEM_ERROR`, `ctx.systemFault`), so AdFlow tries only the house ad after it. A ledger **in the process** keeps the road of `82704c0` (that candidate refused `INTERNAL_ERROR`, the next one tried). The indexes are made outside `computeIfAbsent`. | 250bb0e, 4cbf062 |
| (b) bookmark lock | The embedded store takes recall-db's own lock `recall-db-bookmark:<first schema>` on PostgreSQL before the MemLedger is built, holds it for the ledger's life, and gives it back only when the ledger is down **and** its WAL consumer has ended. A failed build (an Error too) gives it back. MySQL / H2: no lock, one INFO line. The remote store takes nothing. | 8e141d0, 19b4152, 14181ad |
| (c) start line | A remote store is named: `ledger switch (remote 10.10.188.2:7410) on …`. An embedded store (said or not) keeps the old line byte for byte: `ledger switch on …`. | b632025, fcb8fd6 |
| (d) start wait | `start-wait-ms` (default 30000, 0 = one try): the start waits for the server's first answer with backoff. A wrong bearer is refused at once. A word that is not a whole number refuses the start in words. | a020f6f, 95e71a8 |
| (e) unknown outcome | After Apply's second deadline: `UNKNOWN outcome: batch <id> sent twice, no answer within <n> ms — reconcile by this transaction id`. Still exactly two sends. | dafa9ee |
| money | A purchase that commits while its tier's partner index is being made drops that making, so the next view sees the new package. | 3f47073 |

## 2. The closing commits — RED first, then GREEN

| Commit | Finding it closes | RED (test first, on the code before the fix) | GREEN |
|---|---|---|---|
| fcb8fd6 | must-fix: item (c) changed every old document's start line | `KitFromBootstrapTest` 2 of 14 failed: `ledger switch (embedded) on jdbc:…` for a root with no store word and for `store: embedded` | `KitFromBootstrapTest` 14/0/0/0 |
| 4cbf062 | item (a) changed the embedded path; a code slip in the making lost its stack; the test javadoc's 800 ms claim | `InProcessPlanFaultTest` on fcb8fd6: `systemFault BILLING_SYSTEM_ERROR`, refusals `BILLING_SYSTEM_ERROR` x3. The same test on **82704c0**: GREEN (`systemFault null`, refusals `INTERNAL_ERROR` x3, the house ad) — the expectation is the old road's own | `InProcessPlanFaultTest` 1/0/0/0, `HungServerAdmissionTest` 1/0/0/0 (343 ms), `IndexOfATierTest` 3/0/0/0, prepaid parity 64/0/0/0 |
| 3f47073 | money: a purchase during an in-flight making was left out of the kept index | `PurchasesTest#aPurchaseWhileThePartnerIndexIsBeingMade…`: the next `accountsOf` gave `[30]`, not `[31, 32, 30]` | `PurchasesTest` 2/0/0/0 |
| 95e71a8 | a bad `start-wait-ms` / `deadline-ms` refused without words | `For input string: "30s"` | `StartWaitTest` 4/0/0/0, `RemoteStoreTest` 6/0/0/0 |
| 19b4152 | an Error from `MemLedger.build()` kept the lock | after an `ExceptionInInitializerError` in the build, a plain session could NOT take recall-db's lock | `EmbeddedBookmarkLockTest` 3/0/0/0 |
| 14181ad | the lock was given back while the WAL consumer still wrote the bookmark row | `WAL consumer thread did not stop within timeout`, then `gave back the lock`, and a plain session took it | the lock kept while the consumer waits, given back 22 ms after it ended; `EmbeddedBookmarkLockTest` 4/0/0/0 |

The PostgreSQL REDs ran on my own PostgreSQL 16.15 container on 127.0.0.1:7453.

## 3. The gate

**The seed reactor**, once, on a clean `git archive 14181ad`, `flock /tmp/mvn.lock mvn -o -B -Dmaven.repo.local=/home/mustafa/.m2/seed-remote-fixes-lab
-Dmaven.repo.local.tail=/home/mustafa/.m2/repository install -Dmaven.test.failure.ignore=true` (failures would be reported, not hidden).
JDK 21.0.9, my PostgreSQL 16 up on 7453 (so `EmbeddedBookmarkLockTest` ran, not skipped). 08:46:00Z → 08:50:12Z, all 18 modules
BUILD SUCCESS:

| Module | Run / Fail / Error / Skip |
|---|---|
| seed-logtext | 24/0/0/0 |
| seed-config | 5/0/0/0 |
| seed-config-client | 8/0/0/0 |
| seed-context | 23/0/0/1 |
| seed-campaign | 97/0/0/0 |
| seed-routing | 33/0/0/2 |
| seed-naming | 16/0/0/0 |
| seed-sessionflow | 170/0/0/0 |
| seed-bootstrap-config | 8/0/0/0 |
| seed-ledger-client | 6/0/0/0 |
| seed-switch-ledger | 142/0/0/0 |
| seed-idgen | 28/0/0/0 |
| seed-prepaid | 290/0/**2**/6 |
| seed-tenant | 30/0/0/0 |
| seed-sessionflow-samples | 78/0/0/0 |
| seed-memledger-remote | 22/0/0/0 |
| **total** | **980/0/2/9** |

The 2 errors are `TierRightsLabTest` (both tests): `Lab PostgreSQL unreachable at 127.0.0.1:7544`. That lab is down — an
environment error, not worked around. 975 at the build stage + 5 new tests = 980.

Proofs:
- Effective local repository: `help:evaluate settings.localRepository` = `/home/mustafa/.m2/seed-remote-fixes-lab` (the CLI overrides the
  tracked `.mvn/maven.config`). It is a `cp -a` copy: 0 files with more than one link; its mem-ledger jar is inode 59260460, the
  switch-ledger-lab one 28652842.
- Resolved mem-ledger: `dependency:build-classpath` of seed-switch-ledger → `/home/mustafa/.m2/seed-remote-fixes-lab/com/telcobright/mem-ledger/1.0.0-SNAPSHOT/mem-ledger-1.0.0-SNAPSHOT.jar`,
  `MemLedgerImpl.class` sha256 `211f76f10c8c3c023a1efa01512a106196a5a98e0f4d317b1671ed637f826a65` = **f739824**.
- The installed jars are this head's build (class sha256, installed = built): LedgerConsumer `cfd8dacd…`, Books `ee5e2a5b…`,
  Purchases `06791191…`, OwnedMemLedgerPort `1cc64629…`, SwitchLedgerMaker `e9c7fc16…`, BootstrapRoad `e70b44de…`,
  `LedgerPort$LedgerFault` `1bcd1e14…`, AccountWalk `8a44d29b…`, RemoteMemLedgerSource `28989c9c…`.

**The twice-run**, once, from a scratch `git archive` of recall-db `origin/master` **59028d2** (it moved during the round:
8823e79 → 5790edf → 74ae808 "a root with NO reseller tier starts" → 59028d2; `InstanceLock`, `ServerWiring`, `PrimeContextTiers` and
`ListedTiers` are unchanged since 8823e79). Built offline against my repository: `help:evaluate` = `/home/mustafa/.m2/seed-remote-fixes-lab`;
`dependency:build-classpath` resolves seed-memledger-remote, seed-switch-ledger, its tests jar, seed-sessionflow and mem-ledger from that
repository (the head's installed jars). `mvn test`: BUILD SUCCESS —
- recall-db's own `default-test`: **20/0/0/7** (the 7 = PostgreSQL lab tests: `Environment variable [RECALL_DB_LAB_PG_URL] does not exist`);
- the `twice-run` execution: **139/0/0/0** — the seed's switch-ledger test-jar over the REMOTE road (RemoteMemLedger ↔ loopback ↔
  recall-db's front ↔ the lab's MemLedger), `ModuleShapeTest` excluded by recall-db's pom; it includes the new
  `PurchasesTest#aPurchaseWhileThePartnerIndexIsBeingMade…` and all 4 `EmbeddedBookmarkLockTest` tests. 136 at the build stage + 3 = 139.

**Mutants** (each on its own scratch `git archive`, never the worktree):

| | Mutation | Result |
|---|---|---|
| M1 (build stage) | `AccountWalk.planReserve` without its LedgerFault catch | `HungServerAdmissionTest` RED — KILLED |
| M2 (build stage) | `BookmarkLock.KEY_PREFIX` = `seed-bookmark:` | `EmbeddedBookmarkLockTest` RED — KILLED |
| M3 (closing) | `Books.read` never marks a fault as on the wire (`guarded(what, call, false)`) | `HungServerAdmissionTest` RED: 966 ms, `systemFault null`, `INTERNAL_ERROR` x3 — KILLED |
| M4 (closing) | `Purchases.indexTheNewAccounts` without `accountsByPartner.forget(db)` | `PurchasesTest` RED: `[30]`, not `[31, 32, 30]` — KILLED |

## 4. The lock key proof

| | recall-db master 59028d2 `InstanceLock.java` | seed `BookmarkLock.java` (unchanged since 8e141d0) |
|---|---|---|
| key | `"recall-db-bookmark:" + firstSchema` (:34) | `KEY_PREFIX = "recall-db-bookmark:"` (:31) + first schema |
| take | `SELECT pg_try_advisory_lock(hashtext(?))` (:76) | the same text (:110) |
| give back | `SELECT pg_advisory_unlock(hashtext(?))` (:88) | the same text (:71) |
| first schema | `ServerWiring.start` → `InstanceLock.take(db, firstOf(tierDbs))`, the root first | `SwitchLedgerMaker.tierSchemasOf`: the root first; `bookmarkSchemaOf` = the first |

mem-ledger f739824 keeps the bookmark row in its first database (`MemLedgerImpl.java:455-458`, `trackingDatabase = databases.get(0)`).
On PostgreSQL 16.15, `hashtext('recall-db-bookmark:btcl')` = 255773084 (the reviewer saw the same key in `pg_locks` for both sides).
The build stage's cross-proof (recall-db's own `InstanceLock` against the seed's `BookmarkLock`, both directions) ran at 8823e79;
`InstanceLock.java` and `ServerWiring.java` are byte-identical at 59028d2. Mutant M2 (key text changed) is killed.

## 5. Every review finding, answered

| # | Finding (severity) | Answer |
|---|---|---|
| 1 | A hung recall-db at deadline-ms 1000 decides the house ad after ~1.0 s, past the captive's 800 ms (note) | **Agreed, not a code change tonight.** The view costs ONE deadline (not one per paid campaign); money is safe. W6 for a HUNG server: one `LEDGER FAULT` line per view, `BILLING_SYSTEM_ERROR`, the captive shows its own timeout; a cleanly stopped server answers UNAVAILABLE in ms and the house ad comes in time. The test's javadoc claim is corrected (4cbf062). Later: a read deadline for the admission below the captive's wait, or a short "ledger suspect" window. |
| 2 | A purchase during a making is left out of the kept index (note, money) | **Fixed — 3f47073** (RED + mutant M4). |
| 3 | Embedded: item (a) follows the hold's rule; a code slip inside the guarded making loses its stack (note) | **Fixed — 4cbf062**: only the table reads are guarded; a slip in the map building stays a plain exception (`INTERNAL_ERROR`, stack logged). |
| 4 | Checked: a plan fault leaves nothing half-reserved, AdFlow charges nothing after it, item (e) changes words only (note) | Agreed. Still true after 4cbf062 (`HungServerAdmissionTest` unchanged: BILLING_SYSTEM_ERROR x3, one deadline). |
| 5 | Item (c): an old document's start line changed (**must-fix**) | **Fixed — fcb8fd6.** `ledger switch on …` byte for byte for an embedded store, said or not. |
| 6 | When PostgreSQL restarts, the lock is lost without a word (note) | **Open.** A watch + fail-closed is a design decision (stop the ledger's writes?), and recall-db's `InstanceLock` has the same gap. Not tonight's path (the remote store takes no lock; the old jar takes none). Operations rule: after any PostgreSQL restart, restart the ledger's owner (recall-db) so it takes its locks again. |
| 7 | The lock is given back while the WAL consumer may still write the bookmark row (note) | **Fixed — 14181ad.** The consumer thread is read from mem-ledger's private `consumerThread` (pinned f739824; "not seen" = given back as before, said once). Exact per ledger, so a multi-root process is not blocked by another root's consumer. |
| 8 | Embedded: a plan-step read that throws became a system fault (note; breaks the brief's rule) | **Fixed — 4cbf062** (RED on fcb8fd6, GREEN on 82704c0 and on the fix). |
| 9 | A start that fails with an Error keeps the lock (note) | **Fixed — 19b4152.** |
| 10 | A root added LIVE waits up to start-wait-ms inside BootstrapRoad's monitor (note) | **Open — not tonight's path** (every root is served at the start; a live root needs a pending root whose server is down). Recipe: a "served live" flag in `LedgerMaker.Context` → `MemLedgerSource.Request` → start-wait 0 for a live root (PendingRoots already retries it). Both are SPI records: add a compatible constructor. |
| 11 | A bad start-wait-ms / deadline-ms refuses without naming the word or the root (note) | **Fixed — 95e71a8.** |
| 12 | Verified: lock key, hash, SQL, both directions, MySQL skip, bearer refusal, start bound, orchestrix line (note) | Agreed; re-read at 59028d2 (§4). |

The build stage's own "not done":

| Item | Answer |
|---|---|
| Reconnect backoff during the ledger's life (~23 s after a 150 s outage; gRPC caps at 120 s) | **Open** (not in the brief). Window rule: after any recall-db restart, watch the first PAID view, not only recall-db's Health. Later: `resetConnectBackoff()` on UNAVAILABLE (rate-limited). |
| A first-try Apply that fails UNAVAILABLE after the request reached the server reads as a plain fault | **Open.** recall-db's Fatal path answers UNAVAILABLE for a batch already in its WAL (applied at its next start), so such a view is charged with no CDR. recall-db P2 (036616d) covers the deadline case on the server. Window rule: never stop or restart recall-db while ad-sphere serves; any recall-db fatal = reconcile that minute's views by transaction id. |
| No log-capture test for the start-try WARN, the MySQL/H2 INFO, the new keeper ERROR | **Open** (minimal testing); each line was seen in a run log. |
| Officer-road reads `Purchases.purchasesOf` / `firstAnswer` unguarded | **Open.** Not on a view's path; guarding them changes the embedded officer roads' error type. |

## 6. The embedded path against 82704c0 — every difference

The brief: the embedded path behaves exactly as before, except item (b)'s lock. What differs, all of it:

1. **The lock** (item b) and its two refinements: given back after an Error in the build (19b4152), and only once the WAL consumer has
   ended (14181ad). To give the lock back safely after an Error in the engine's port build (after a good MemLedger build), the MemLedger is
   now shut down first — before, it was left running.
2. **The money fix** (3f47073) applies to both stores: a purchase during an in-flight index making now drops that making. Before, the
   stale index could be kept (the bug the review found, already in 82704c0).
3. **The index making** (item a) runs outside `computeIfAbsent`. When the reads answer, the indexes and the answers are the same. When a
   making FAILS while others wait for it, the waiters now get the same fault instead of each reading again.
4. **A plan-step read that throws**: same refusal (`INTERNAL_ERROR`), same candidates tried, no system fault — proven against 82704c0 by
   `InProcessPlanFaultTest`. The `INTERNAL_ERROR` line's words differ for `livePurchase` and the two makings' table reads: the original
   exception now comes wrapped in a `LedgerFault` (`the ledger could not list PackagePurchase of <tier>: …`), the original as its cause
   (stack logged).
5. **The start line** is byte for byte the old one (fcb8fd6).

## 7. What ad-sphere must change when it re-pins

- Pin the seed at this branch's head (code = the gated `14181ad`) with mem-ledger f739824, and build its base from a `git archive` of it.
- `AdSwitchLedgerPostgresIT.java:92` (an embedded root) passes again unchanged: `ledger switch on ` + url.
- `AdRecallDbRemotePostgresIT.java:99` (the remote root) must assert `ledger switch (remote <address>) on ` + url. The window's start
  check greps `ledger switch (remote 10.10.188.2:7410)` or the client's INFO line
  `mem-ledger client: the ledger of root btcl is the server at 10.10.188.2:7410`.
- No code change for the SPI: `LedgerFault.onTheWire()` / `LedgerFault.ofALedgerOnTheWire(...)` are additions; the two constructors and
  `systemFault` are as before. AdFlow's rule (only the house ad after a ledger fault) now applies from the plan step on the wire.
- The document: prime-context's facade `pc-ledger-store` (8e80782) knows `store`, `address`, `deadline-ms`, `token-var` — NOT
  `start-wait-ms`. Leave `start-wait-ms` out of the document (the default 30000 applies) until prime-context learns it.
- A bad `deadline-ms` now refuses the start in words, naming the root (not a NumberFormatException).

## 8. Environment and clean-up

- My PostgreSQL container `close-pg-7453` is removed at the end; port 7453 is free. No process of mine is left.
- Scratch copies (the gate archive, the twice-run copy of recall-db 59028d2, the two mutants) stay under the session scratchpad `close/`.
- `/home/mustafa/.m2/seed-remote-fixes-lab` now holds the jars of `14181ad`.
