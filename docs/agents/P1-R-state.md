# P1-R · stream R — state

One line per item, newest last. Times are Dhaka (+06).

- 2026-10-23 17:09 · START — worktree `p1-records` from `routesphere-flow` c32e374; the reactor there **430/0/0/7** (confirmed, offline, under the lock).
- 2026-10-23 17:55 · **R1 done** (§A) — `RecordStatement` (+ the probe), `RecordId`, `RecordStores`/`RecordStore`/`RecordDialect`, the hooks
  `cdrStatements` / `sdrStatements` / `summaryEntryOf`, the default `CdrTableSql` (MySQL + PostgreSQL, billing-core's once rule), the default
  summary entry, the blob; C6 green; 13 R1 mutants red; the reactor **446/0/0/7**. Commit **77f4cc5**. Next: R2 (the writer). Blocked: nothing.
- 2026-10-23 18:32 · **R2 done** (§B) — the writer: one platform thread + queue per root (`RecordWriter`), batches N/T, ONE transaction per
  batch (`RecordBatch`) under billing-core's per-tier lock (`BatchLock`: GET_LOCK / pg_try_advisory_xact_lock, billing-core's names and key),
  one `summary_affected` row per tier of the records written now, the ping after the commit on its own thread (`PingRoad`, `SummaryPing`,
  `SummaryPings.kafka`), the journal's done after the commit; a failed batch rolled back whole and tried again (1 s … 30 s); the finish takes
  the records road when the kit has one; the SDR's rows in the same transaction. C1, C3, C5 green; 14 R2 mutants red; the reactor from
  clean **454/0/0/7**. Commit **bf46c82**. Next: R3 (park / unpark). Blocked: nothing.
- 2026-10-23 18:46 · **R3 done** (the failure road) — `RecordParking`: a failed batch tried again (1 s … 30 s), PARKED after the budget
  (`RecordStores.retries(first, cap, budget)`, default 7 tries ≈ 1 min) said ONCE (ERROR, root + cause), `recordsParked` / `recordParks`
  counted, every session kept in the journal of the sessions in the air, a session that journal does not hold (`SessionJournal.holds`, new
  default; `FileSessionJournal` answers it) written to the park sink (`RecordStores.parkInto`, the file CDR journal) — at the park and for each
  session handed in while parked; the next commit UN-PARKS, said once (`recordUnparks`); the close leaves what it could not commit the same
  way. C4 green; 11 R3 mutants red; the reactor from clean **456/0/0/7**. Commit **1bf4a25**. Next: R4 (the switch ledger). Blocked: nothing.
- 2026-10-23 18:50 · **R4 done** (§D) — answered, nothing changed: mem-ledger as the seed pins it (1.0.0-SNAPSHOT) cannot keep the settled
  reference — the settle deletes the tier's reserve row (routesphere-core's move), `PackageAccount` holds only the last move, the `MemLedger`
  API reads no history, and `ClosedTiers` (the first answer per reference) dies with the process; so after a restart a second settle moves
  nothing and answers OWED, and that stays (proof: ChainOnTheLedgerTest.aSessionEndedAgainAfterARestart…). Report §4 + three roads for the
  owner. Commit: this one (R4, docs only). Next: R5 (the road choice, the leftovers, C2, C7). Blocked: nothing.
