# P1-E · stream F — the framework round: the extension contract, the bootstrap kit, naming, the tenant-created handler (report)

Branch `p1-rules` (worktree `routesphere-seed/worktrees/p1-rules`), from f41eace (stream D's four commits + the brief). Nothing pushed;
nothing written into `~/.m2`; no database server, broker or prime-context reached (in-process fakes, Kafka's mocks, H2 in memory only).
**Status: in progress.**

| | tests / failures / errors / skipped |
|---|---|
| the reactor on f41eace, before any change (from `clean`) | **465 / 0 / 0 / 3** |
| after item 0 (from `clean`) | **434 / 0 / 0 / 3** |

## 0 · The commits

| commit | item |
|---|---|
| (item 0) | the records road removed (R13) |

## Item 0 · the records road leaves (R13)

The owner's correction: a switch never writes a CDR table; it publishes to Kafka and billing-core writes. P1-R is removed whole; stream D's
`P1-D 5` (a leftover's root on the Kafka road) is kept — `CdrAssembler.rootOf` stays for it.

- **Main code deleted (25 files):** `internal/{RecordRoad, RecordWriter, RecordBatch, RecordParking, BatchLock, IdBook, PingRoad,
  StatementRunner, CdrTableSql, CdrColumns, SummaryBlob, SummaryEntries, SessionRecords, KafkaSummaryPing}`, `api/{RecordStatement,
  RecordId}`, `dependencies/{RecordStores, RecordStore, RecordDialect, SummaryPings}`, `spi/SummaryPing`, `testkit/{H2Locks,
  RecordTables}`. (`KafkaSummaryPing` was not in the brief's list; it is the records road's ping, only `SummaryPings`/`PingRoad` used it.)
- **Main code reverted to its pre-P1-R shape:** `RoutesphereFlow` (the hooks `cdrStatements` / `sdrStatements` / `summaryEntryOf`, the
  helpers `writeRecords` / `writeRecordsOf` / `cdrTableRowOf` / `storeOf` gone; `finishSession` builds the SDR inside `WRITE_SDR` again;
  the javadoc table loses the records lines), `SessionCdr` (one road: the sink; a leftover still goes on its ROOT's road), `RoutesphereKit`
  (`records(...)`, `records()`, `recordStores()`, the one-road check gone; **the CDR sink is required again**: "the routesphere kit needs cdr
  (the CDR sink)"), `RoutesphereEngine.close` (no records writer to close), `FlowCounters` (the six records counters), `SessionJournal.holds`
  and `FileSessionJournal.holds`.
- **Samples and tests:** every lane's `cdrStatements` override removed (AdFlow, VoiceFlow, SmsFlow, WifiFlow, LabFlow, LedgerFlow) and
  `AdFlow.sdrStatements`; `World.kitOnRecords`; the scene helpers `CrashAfterTheCommit`, `CrashingJournal`, `WorldRecords` (only
  `AdRecordsTest` used them); the samples' test-scope H2 dependency (only the records tests used it); `Lab.btclTree()/tele2Tree()` back to
  package-private (P1-R had opened them for its `records` package).
- **Docs:** the README's §5.1 and the records rows, a history line; `docs/agents/P1-R-records-brief.md` and `P1-R-state.md` deleted;
  `P1-R-report.md` kept as history with one line at its top (superseded, R13).
- **`RoutesphereFlowDriftTest`:** the forced set loses `cdrStatements` (eight now; item 1 adds `identifyTenant`).

**Tests deleted (31), all the records road's own — the road they proved no longer exists:**

| class | tests | what it proved |
|---|---|---|
| `records/CdrTableSqlTest` | 6 | billing-core's `cdr` row SQL (MySQL + PostgreSQL) |
| `records/RecordWriterTest` | 6 | the writer's batches, one transaction, the lock, the ping after the commit |
| `records/SummaryEntriesTest` | 5 | the `summary_affected` entry shape |
| `records/RecordHooksTest` | 3 | the three hooks |
| `records/RecordRoadChoiceTest` | 3 | one road per lane at build |
| `records/RecordFailureTest` | 2 | rollback, park, unpark |
| `records/StatementRunnerTest` | 2 | the statement runner and its probe |
| `records/LeftoversThroughTheWriterTest` | 1 | the leftovers at start through the writer |
| `samples/ad/AdRecordsTest` | 3 | the ad's CDR + `ad_sdr` in one transaction |

(+ the helpers `records/{Records, RecordsLab, RecordingDataSource, SummaryServiceReader}`.)

**Test changed:** `RoutesphereKitTest.theTreesTheLedgerTheCdrSinkAndTheZone_haveNoDefault` — the CDR sink's refusal is pinned to its own
words ("needs cdr (the CDR sink)", and no word of a records road); it passed on the P1-R wording "cdr … or records" too, so it is tightened.

The reactor from `clean`: **434 / 0 / 0 / 3** (465 − 31).

## Open questions for the owner / the architect

(none yet)
