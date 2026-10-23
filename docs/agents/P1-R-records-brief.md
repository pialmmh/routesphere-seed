# P1-R — the records road: the application's own CDR/SDR SQL, written by the base in ONE transaction (READY)

Written 2026-10-23 14:50 +06 by the architect. The owner's ruling of 2026-10-23: "each application is responsible for generating
its own CDR/SDR — actually the SQL generation; the CDR writing + the input for summary data generation (in a single transaction, a
reusable base-class method) can be in the base class", and the three defaults confirmed: (1) the switch writes its tenant's database
directly — the Kafka → billing-core ingest is no longer in that lane's road (Kafka stays an optional sink); (2) the summary input is
billing-core's `summary_affected` outbox row + the `cdr_summary_ping`, in the same shape, so summary-service needs no change;
(3) `acc_chargeable` is the application's SQL's decision (the ad's does not need it).

## 0 · Where things are

- routesphere-seed branch `routesphere-flow` **75f601e** (code d4efd8a): `RoutesphereFlow` on statewalk 4.0.0, the samples, P1-F.
  Branch `p1-records` from it, worktree `worktrees/p1-records`. Build offline (`mvn -o -q test`); never two Maven at once; never
  `install` into `~/.m2`; never push; commit per item (`P1 R<n>:`); stop new items when told and write `docs/agents/P1-R-state.md`.
- Today's road in the base: `finishSession` → `SessionCdr.publish` → `kit.cdr()` (`CdrSink`: Kafka `cdr_<root>`, file journal,
  fan-out) → `markDone` in the in-the-air journal. `CdrEvent` (per tier, leaf first; one per span of a rotated tier) is the record's
  CONTENT and stays as it is — it is the ratified wire (`routesphere/docs/architecture/ad-is-a-call.md` §4).
- What billing-core does today, to copy the rules from (READ ONLY, `~/telcobright-projects/telcobright-billing-core/java/src/main/java/com/telcobright/billing/`):
  `mediation/cdr/CdrPipeline.java` (phase 1 qualify + dedupe, phase 2 write cdr + chargeables on ONE connection, the summary
  outbox), `data/MySqlCdrBatchRunner.java` (autocommit off, the per-tenant batch lock, commit/rollback), `mediation/cdr/SummaryOutboxWriter.java`
  (ONE `summary_affected` row per batch: entity_type `cdr`, op `add`, data = JSON → gzip → base64 of the batch's rated cdrs with
  their chargeable legs), `beans/SummaryChangeNotificationPublisher.java` (the ping: a Kafka message after the commit, on the ping's
  own thread, never holding a batch; a topic the brokers lack is never handed a ping). The reader: summary-service
  `runtime/internal/JdbcOutboxStore.java`, `ping/internal/PingListener.java`.
- The PostgreSQL side of the `cdr` table (the ad lane): billing-core branch `postgres-ad-call` (d74d466) and ad-sphere's schema.

## A · The hooks — the application's part (seed-sessionflow `api/RoutesphereFlow`)

- `RecordStatement(String sql, List<Object> params)` — one prepared statement; the table is qualified by the tier's schema/database
  (`<tier db>.cdr`), as billing-core writes per tier schema on one connection.
- `protected List<RecordStatement> cdrStatements(C ctx, CdrEvent record)` — the tier record's INSERT(s). **The base ships the
  default:** `CdrTableSql` = the call pattern's `cdr` table exactly as billing-core writes it (the column list and the JSON names of the
  contract; MySQL and PostgreSQL dialects; idempotent on (tenant, channelCallUuid): `INSERT IGNORE` / `ON CONFLICT DO NOTHING`). A
  lane overrides to add `acc_chargeable` (voice, later) or to write its own table.
- `protected List<RecordStatement> sdrStatements(C ctx, Outcome outcome, Object sdr)` — the lane's SDR row(s) in the same
  transaction; default none. (`buildSdr` stays the content hook; `SdrSink` stays for a lane that writes the SDR elsewhere.)
- `protected Object summaryEntryOf(C ctx, CdrEvent record)` — what goes into the summary blob for this record; default: the record
  itself in billing-core's blob shape (the rated cdr + its chargeable legs — read `SummaryOutboxWriter.Entry` and copy the shape, the
  field names case-insensitive on the reader).

## B · The writer — the base's part (`internal/RecordWriter`, reachable as the reusable method `writeRecords`)

- `kit.records(RecordStores)`: a `DataSource` per root (the application hands it in; H2 in the tests). seed-sessionflow stays
  driver-free (`java.sql` only; H2 is already a test dependency).
- Per root: ONE writer thread and a queue; batches of N records or T ms (settings; defaults 200 / 250 ms). One transaction per batch:
  autocommit off → the per-root batch lock (MySQL `GET_LOCK`, PostgreSQL `pg_advisory_xact_lock`, H2 none; the name as billing-core's
  so a billing-core writer on the same tenant serializes with us) → per session: the application's CDR statements per tier, its SDR
  statements → ONE `summary_affected` row holding only the records whose INSERT wrote a row (affected = 1: a replayed record is not
  summed twice — confirm billing-core's rule in CdrPipeline and copy it) → commit → the ping (after the commit, never holding the batch;
  the same topic/key shape as billing-core's publisher; a lane with no Kafka = no ping, said once) → the journal's `done` for every
  session of the batch.
- Failure: rollback; the batch is retried with backoff (1 s, 2 s, … 30 s); after the retry budget the writer PARKS, says it ONCE (ERROR,
  with the root and the cause), counts `recordsParked`, and the records stay in the in-the-air journal (nothing lost); a later success
  un-parks and says so once. A record the journal cannot hold is written to the file CDR journal as today (`CdrSinks.journal`).
- The finish chooses the road: a records store for the root → the writer; else `kit.cdr()` as today. Both at once is refused at build
  (one road per lane, like F3). `SessionCdr.markDone` moves to after the commit.
- The leftovers at start (`publishWhatWasLeftInTheAir`) go through the same writer.
- Threads: the writer blocks on JDBC — a platform thread per root, never a virtual carrier of statewalk's chains; `close()` drains
  (bounded) then parks what is left in the journal.

## C · Tests (seed-sessionflow + the samples; H2 per root with billing-core's `cdr` + `summary_affected` tables)

1. The ad sample ends views on two roots and 1/2/3 tiers → one `cdr` row per tier record in its tier's schema, the columns as the
   contract, ONE `summary_affected` row per batch whose blob decodes to exactly those records, the journal empty after the commit.
2. A crash between the commit and the journal's `done` (the samples' `Crash` switch) → at the next start the leftovers are written
   again: no second `cdr` row, no second summary entry.
3. 500 sessions ending at once on one root → one writer, every row exactly once, no deadlock, the batch count small.
4. A failing store → rollback, retries, PARKED once, nothing lost; the store back → written, UNPARKED once.
5. The SDR in the same transaction (the ad's `AdSdr` as a row); a failing SDR statement rolls the whole batch back.
6. The dialects: the default `cdr` INSERT on H2 in PostgreSQL mode and in MySQL mode (both idempotent).
7. The road choice: records store + CdrSink refused at build; Kafka-only lanes unchanged (the existing tests).
8. Mutants on the once rules and the lock; the reactor after every commit; a clean copy of the last code commit.

## D · R4 (small): the switch ledger's second end after a restart

seed-switch-ledger answers OWED ("no reserve row open … nothing moved") when a settled reserve is settled again after a restart.
If mem-ledger keeps the settled reference, answer the settled amount (idempotent) instead of OWED; if it cannot, say so in the report.

## E · Out of scope

No consumer (P2 moves ad-sphere onto this road), no push, no bed, billing-core / summary-service read only.
