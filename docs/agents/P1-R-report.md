# P1-R · stream R — the records road (report)

Branch `p1-records` (worktree `routesphere-seed/worktrees/p1-records`), from `routesphere-flow` c32e374 (the brief). Nothing pushed;
nothing written into `~/.m2`; no database server, no broker reached (H2 in process only). Draft — kept current per item.

| | |
|---|---|
| the reactor on c32e374, before any change | **430 / 0 / 0 / 7** (tests / failures / errors / skipped) |
| after R1 | **446 / 0 / 0 / 7** |

## 0 · The commits

| commit | item |
|---|---|
| (R1) | **R1** §A — the hooks, `RecordStatement`, `RecordId`, the records stores, the default `CdrTableSql`, the default summary entry, C6 |

## 1 · R1 — §A, the application's part

**What is new** (seed-sessionflow):

| file | what |
|---|---|
| `api/RecordStatement` | one prepared statement `(sql, params)`, every value a parameter, the table qualified by the tier (`<tier>.cdr`); `RecordStatement.probe(...)` is a query that answers a row when the record is written already |
| `api/RecordId` | a number the writer hands out under the batch lock, `(seed, key)`: billing-core's `MaxIdSeededAutoIncrementManager`; one key = one number in a try, so the INSERT and the summary entry carry the same IdCall |
| `dependencies/RecordStores`, `RecordStore`, `RecordDialect` | one store per root (`root`, a pooled `DataSource`, MYSQL / POSTGRESQL, the rows' `switchId`); `RoutesphereKit.builder().records(...)` |
| `internal/CdrColumns` | billing-core's `cdr` row of one tier record: the 104 legacy columns (+ the wire's six on PostgreSQL), each value as billing-core's ingest maps it (§1.1) |
| `internal/CdrTableSql` | the default: **the probe** of billing-core's key in the tier, then **a plain INSERT** (§1.2) |
| `internal/SummaryEntries` | the default entry: billing-core's `Entry` (`{"Cdr", "Chargeables"}`), the pre-rated customer leg of a stated group (§1.3) |
| `internal/SummaryBlob` | the row's `data`: JSON → gzip → base64, a time as billing-core's mapper writes it (`[y,M,d,H,m(,s)]`), a RecordId as its number |
| `internal/StatementRunner`, `IdBook` | how one record's statements run on the batch's connection: the once rule (below), the ids of one try |
| `RoutesphereFlow` | `cdrStatements(ctx, record)` (default `CdrTableSql` on the store of the record's ROOT — the first node of its hierarchy), `sdrStatements(ctx, outcome, sdr)` (default none), `summaryEntryOf(ctx, record)` (default the entry); `ctx` is null for a record left in the air |
| `testkit/RecordTables` | billing-core's tables on H2 for every product's tests (PostgreSQL mode: the 110 columns of billing-tables.sql + `ux_cdr_call`; MySQL mode: the 104, the by-hand `ux_cdr_uniquebillid` when asked) |

**The once rule** (`RecordStatement`, `StatementRunner`): a probe that answers a row = written before — nothing more of the record runs and
it is not summed; otherwise the record's first change decides: a row changed = written now (summed), none changed (a lane's own
`INSERT IGNORE` / `ON CONFLICT DO NOTHING`) = written before, the rest not run.

### 1.1 · The columns (every source cited)

The column order is billing-core's `cdr.ExtInsertColumns` (cdr.java:134-149, identical at d74d466 and f5df47d) + `CdrRowSql.WireColumns`
(CdrRowSql.java:23-24); the PostgreSQL DDL (billing-tables.sql:31-142 @ d74d466) has exactly these 110 in this order (checked by a diff, and
`CdrTableSqlTest.theInsertNamesBillingCoresColumns…` holds the lab's table equal to the INSERT's list). Values, as `CdrEventPreprocessor.Map`
(lines 178-243 @ f5df47d, the head of `postgres-ad-call`, which contains d74d466):

| column | the value written | billing-core source (ingest: `CdrEventPreprocessor.java` @ f5df47d; DDL: `billing-tables.sql` @ d74d466) |
|---|---|---|
| SwitchId | the store's switchId (billing.mediation.switch-id; 0 = unset) | Map :180 · DDL :32 |
| IdCall | the writer's RecordId: max(IdCall) of cdr and cdrerror + 1, under the batch lock | CdrPipeline.java:64 · MaxIdSeededAutoIncrementManager.java:22-24 · DDL :33 |
| SequenceNumber | sequenceNo | Map :181 · DDL :34 |
| FileName | `switch:records` (billing-core writes `kafka:cdr`) | Map :191 · DDL :35 |
| ServiceGroup | a stated 30 / 101 as given, else 0 | Map :184 (StatedServiceGroups) · DDL :36 |
| IncomingRoute | incomingRoute, else receiverIp | Map :214 · DDL :37 |
| OriginatingIP | callerIp | Map :208 · DDL :38 |
| OriginatingCalledNumber | originatingCalledNumber | Map :206 · DDL :41 |
| TerminatingCalledNumber | terminatingCalledNumber | Map :207 · DDL :42 |
| OriginatingCallingNumber | originatingCallingNumber | Map :204 · DDL :43 |
| TerminatingCallingNumber | terminatingCallingNumber | Map :205 · DDL :44 |
| PrePaid | isPrepaid | Map :222 · DDL :45 |
| DurationSec | durationSec | Map :202 · DDL :46 |
| EndTime | endTime | Map :198 · DDL :47 |
| ConnectTime | answerTime | Map :197 · DDL :48 |
| AnswerTime | answerTime | Map :193 · DDL :49 |
| ChargingStatus | ad view: shown (answerTime) → 1; else durationSec > 0 → 1 | Map :203, ChargingStatusOf :259-262 · DDL :50 |
| PDD | pdd | Map :219 · DDL :51 |
| AreaCodeOrLata | hangupCause | Map :217 · DDL :53 |
| OutgoingRoute | outgoingRoute, else callerIp | Map :215 · DDL :57 |
| TerminatingIP | receiverIp | Map :209 · DDL :58 |
| StartTime | startTime | Map :192 · DDL :61 |
| InPartnerId | inPartnerId | Map :220 · DDL :62 |
| CustomerRate | callRatePerMinBDT | Map :229 · DDL :63 |
| OutPartnerId | outPartnerId | Map :221 · DDL :64 |
| MatchedPrefixCustomer | matchPrefixCustomer | Map :223 · DDL :68 |
| MatchedPrefixSupplier | supplierPrefix | Map :224 · DDL :69 |
| InPartnerCost | inPartnerCost | Map :232 · DDL :70 |
| OutPartnerCost | supplierCost | Map :234 · DDL :71 |
| CostAnsIn | costAnsIn | Map :236 · DDL :72 |
| CostIcxIn | costIcxIn | Map :235 · DDL :73 |
| RevenueAnsOut | revenueAnsOut | Map :237 · DDL :76 |
| RevenueIgwOut | revenueIgwOut | Map :238 · DDL :77 |
| AnsPrefixOrig | ansPrefixOrig | Map :228 · DDL :82 |
| AnsIdOrig | ansIdOrig | Map :227 · DDL :83 |
| AnsPrefixTerm | ansPrefixTerm | Map :226 · DDL :84 |
| AnsIdTerm | ansIdTerm | Map :225 · DDL :85 |
| Codec | channelReadCodecName | Map :218 · DDL :110 |
| RoundedDuration | durationSec on a stated group (no plan rounds a pre-rated record), else null | SfPreRated.java:59-62 · DDL :120 |
| Duration1 | durationSec on a stated group (no plan rounds a pre-rated record), else null | SfPreRated.java:59-62 · DDL :125 |
| UniqueBillId | callId | Map :185 · DDL :130 |
| AdditionalMetaData | additionalMetaData (the wire's JSON object) | Map :187, MetaDataOf :274-276 · DDL :131 |
| SignalingStartTime | startTime | Map :201 · DDL :135 |
| ResellerHierarchy | resellerHierarchy | Map :188 · DDL :136 |
| ChannelCallUuid | channelCallUuid | Map :186 · DDL :137 |
| HangupCause | hangupCause | Map :216 · DDL :138 |
| InPartnerUom | inPartnerUom | Map :230 · DDL :139 |
| IdPackageAccount | idPackageAccount | Map :231 · DDL :140 |
| PackageAmount | packageAmount | Map :233 · DDL :141 |

The other 62 columns billing-core's mapping leaves null, and so does the default (DDL line): OPC (39), OriginatingCIC (40), CountryCode (52), ReleaseDirection (54), ReleaseCauseSystem (55), ReleaseCauseEgress (56), DPC (59), TerminatingCIC (60), SupplierRate (65), MatchedPrefixY (66), UsdRateY (67), Tax1 (74), IgwRevenueIn (75), RevenueIcxOut (78), Tax2 (79), XAmount (80), YAmount (81), ValidFlag (86), PartialFlag (87), ReleaseCauseIngress (88), InRoamingOpId (89), OutRoamingOpId (90), CalledPartyNOA (91), CallingPartyNOA (92), AdditionalSystemCodes (93), AdditionalPartyNumber (94), ResellerIds (95), ZAmount (96), PreviousRoutes (97), E1Id (98), MediaIp1 (99), MediaIp2 (100), MediaIp3 (101), MediaIp4 (102), CallReleaseDuration (103), E1IdOut (104), InTrunkAdditionalInfo (105), OutTrunkAdditionalInfo (106), InMgwId (107), OutMgwId (108), MediationComplete (109), ConnectedNumberType (111), RedirectingNumber (112), CallForwardOrRoamingType (113), OtherDate (114), SummaryMetaTotal (115), TransactionMetaTotal (116), ChargeableMetaTotal (117), ErrorCode (118), NERSuccess (119), PartialDuration (121), PartialAnswerTime (122), PartialEndTime (123), FinalRecord (124), Duration2 (126), Duration3 (127), Duration4 (128), PreviousPeriodCdr (129), Category (132), SubCategory (133), ChangedByJobId (134).

### 1.2 · billing-core's once rule, followed where it differs from the brief

The brief: `INSERT IGNORE` / `ON CONFLICT DO NOTHING` and "affected = 1". billing-core (`MySqlCdrBatchRunner.DropWhatMustNotBeBilledAgain`,
:212-231): under the tenant's batch lock it **drops a later copy inside the batch** (first copy wins, :235-243) and **drops what is already
written** — a `SELECT <key> … IN (…)` in the key's tables (:251-287; the key `IdempotencyKey`: PostgreSQL `ChannelCallUuid` in `cdr` AND
`cdrerror`, MySQL `UniqueBillId` in `cdr`, IdempotencyKey.java:25-30) — then a **plain INSERT** (CdrWriter.java:64); the unique index is
only the backstop. Followed: the default is the probe of that key (in the same tables) + a plain INSERT. Why not the brief's form:
(1) a MySQL tier's unique index is applied by hand (`cdr_idempotency.sql`) and may be missing — the probe holds without it
(`CdrTableSqlTest.mysql_…withAndWithoutTheByHandUniqueIndex`); (2) MySQL's `IGNORE` also turns a value the column cannot take into a warning
and a changed row, where billing-core's batch fails; (3) PostgreSQL's `ux_cdr_call` is `(ChannelCallUuid, StartTime)` (billing-tables.sql:147)
while billing-core's key is `ChannelCallUuid` alone. The brief's "affected" rule stays the writer's for a lane's own statements.

### 1.3 · The summary entry

billing-core's `Entry(cdr Cdr, List<acc_chargeable> Chargeables)` (Entry.java:16) serialized by `SummaryOutboxWriter`'s mapper
(SummaryOutboxWriter.java:43-46: JavaTimeModule, NON_NULL, so a time is an ARRAY — probed with Jackson 2.19.2: `[2026,10,2,21,14,4]`, the
seconds left out when 0). The default's `Cdr` = the 110 by billing-core's names (its primitives SwitchId, IdCall, SequenceNumber, ServiceGroup
always written); `Chargeables` = for a stated group ONE customer leg exactly as `SfPreRated.ChargeableOfWhatWasSettled` (SfPreRated.java:65-82):
BilledAmount = PackageAmount when paid in units else InPartnerCost, idBilledUom = InPartnerUom (none → BDT unless paid in units), Quantity =
DurationSec in TF_s, unitPriceOrCharge = CustomerRate, Prefix, description `nc`, uniqueBillId, idEvent = IdCall, transactionTime = StartTime,
servicefamily 30, direction 1; for any other group no leg (billing rates a call — the switch cannot). summary-service reads it with its own
steps (`SummaryEntriesTest.summaryServiceReadsIt…`: `OutboxCodec.decode` + `CdrBlobMapper` into its `AdCallEntry`/`AdCallCdr`/`Chargeable`).

### 1.4 · R1 tests, reds, mutants

Tests (seed-sessionflow `records/`): `CdrTableSqlTest` 6 (C6), `SummaryEntriesTest` 5, `RecordHooksTest` 3, `StatementRunnerTest` 2.

| mutant (deliberate break, restored by re-applying the file, byte-identical) | red |
|---|---|
| R1-M1 the probe never says "written" | CdrTableSqlTest ×3 (postgres once, mysql once, cdrerror) |
| R1-M2 a first change of 0 rows counts as written | StatementRunnerTest.aLanesOwnInsertThatChangesNothing… (was GREEN first: test added, then red) |
| R1-M3 the PostgreSQL probe forgets `cdrerror` | CdrTableSqlTest.postgres_aRecordWhoseKeyBillingCorePutInCdrerror…, RecordHooksTest.theDefaultCdrStatements… |
| R1-M4 the IdCall seed forgets `cdrerror` | CdrTableSqlTest.theIdCall_isSeededUnderTheLock… |
| R1-M5 ConnectTime from the end | CdrTableSqlTest.postgres_theRecordIsWrittenOnce… |
| R1-M6 OutgoingRoute keeps a blank | CdrTableSqlTest.postgres_theRecordIsWrittenOnce… |
| R1-M7 a time always with its seconds | SummaryEntriesTest.aTimeOnAWholeMinute… (was GREEN first: test added, then red) |
| R1-M8 the leg's money from the rate | SummaryEntriesTest ×2 |
| R1-M9 every tier name taken into the SQL | CdrTableSqlTest.aTierThatIsNotAPlainSchemaName… |
| R1-M10 a MySQL store written as PostgreSQL | CdrTableSqlTest.mysql_…, RecordHooksTest.theDefaultCdrStatements… |
| R1-M11 the entry's IdCall not the INSERT's | SummaryEntriesTest.theEntryCarriesTheIdCallItsInsertWasGiven… |
| R1-M12 a stated group not stamped (Duration1) | CdrTableSqlTest.postgres_theRecordIsWrittenOnce… |
| R1-M13 a RecordId a new number at every ask | StatementRunnerTest.aRecordId_isOneNumberPerKeyInATry… |

A harness fault, caught and fixed (and named by the architect the same hour): the first restores kept the file's OLD mtime (`shutil.copy2`),
so Maven kept the last mutant's class; the plain run after them went red (Duration1 null = R1-M12's class). Fixed: a restore writes a fresh
mtime; every source of seed-sessionflow was touched and recompiled before the measured runs. The 446 are clean: each mutated spot has a test
that went red under its mutant and is green in that run.

## 9 · Findings and questions for the owner (so far)

1. **One `summary_affected` row per tier per batch, not per batch.** summary-service reads the outbox of ONE schema per drain (JdbcOutboxStore.java:116,
   unqualified tables on the tier's connection) and sums it into that tier's tables (`tup_tenant` = the schema, AdSummaryBean.java:31-33); billing-core
   writes one row per tenant batch (CdrProcessor.java:110-138). A batch of the root's writer holds several tiers, so it writes one row in each tier
   it wrote. (R2)
2. **The batch lock is billing-core's per-tier lock** — `billing_batch_<schema>` (MySqlCdrBatchRunner.java:143), the guard that makes each schema's
   outbox ids commit-ordered (:131-137, billing-tables.sql:208-210) — taken for every tier of the batch, in name order. (R2)
3. **FileName** is `switch:records`, not billing-core's `kafka:cdr` (its topic marker, CdrEventPreprocessor.java:189-191): a report that filters
   on `kafka:cdr` does not see the switch's rows. The owner's word if `kafka:cdr` is wanted.
4. **MySQL: a rotated tier's spans share one key.** billing-core's MySQL key is `UniqueBillId` = `callId` (:185); the spans of a tier that rotated
   its account (O4: `<sid>.<n>`) share the session's callId, so billing-core — and the MySQL default — keeps the first span only. PostgreSQL keys
   by `ChannelCallUuid` (`<sid>.<n>`) and keeps every span. A question for the voice lane before it moves to this road.
5. **SG 101** (a WiFi session): billing-core projects the meta into the WiFi columns (`WifiColumns.project`, :241, BC-0008, after d74d466); the
   default does not — a WiFi lane on this road overrides `cdrStatements` (or a later round adds it).
6. **Proven on H2 only.** The SQL ran on H2 2.2.224 in PostgreSQL and MySQL modes; no real server was reached (rule 3).
