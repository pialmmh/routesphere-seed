# P1-E · stream F — the framework round: the extension contract, the bootstrap kit, naming, the tenant-created handler (report)

Branch `p1-rules` (worktree `routesphere-seed/worktrees/p1-rules`), from f41eace (stream D's four commits + the brief). Nothing pushed;
nothing written into `~/.m2`; no database server, broker or prime-context reached (in-process fakes, Kafka's mocks, H2 in memory only).
**Status: in progress.**

| | tests / failures / errors / skipped |
|---|---|
| the reactor on f41eace, before any change (from `clean`) | **465 / 0 / 0 / 3** |
| after item 0 (from `clean`) | **434 / 0 / 0 / 3** |
| item 1, a git-archive clean copy of 4a8b22a (offline, under the lock) | **438 / 0 / 0 / 3** |

## 0 · The commits

| commit | item |
|---|---|
| 1caa152 | **item 0** — the records road removed (R13) |
| 4a8b22a | **item 1** — `identifyTenant` forced, with the door helpers |
| 891326b | **item 2** — `identifyPartner`, `partnerAbove`, `byPartnerId` / `byPartnerName` |

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

## Item 1 · `identifyTenant` forced, with helpers (R1, R2)

- `identifySiblingLevelTenants(SessionFacts)` → **`protected abstract String identifyTenant(RoutesphereFacts facts)`** (■ in the class table).
  `onStart` asks it through `rootOf(ctx)`: facts that are not `RoutesphereFacts` → refused `NO_TENANT` with a history line naming the class
  ("the session's facts are <class>, not RoutesphereFacts: they name no ingress, so no root"); a throw → `NO_TENANT` (the base's `safely`).
- **The helpers** (final, one line each): `byListener(ip, port)` / `byListener("ip:port")`, `byEslSocket(host, port)` / `byEslSocket("host:port")`,
  `byVlan(gateway, svlan)`, `byTopic(name)`, `byIngress(ingress)`. They live ONCE in **`api/Lookups`** (the kit makes one: `kit.lookups()`),
  so a builder lane (item 5) reaches the same code. The "ip:port" overloads were added because the samples' facts carry the address as one word
  (the brief's form is the two-argument one). A blank or null door answers null (never an exception).
- **The lanes:** AdFlow `byListener(listen)`, VoiceFlow `byEslSocket(esl)`, WifiFlow `byVlan(gateway, svlan)`, SmsFlow `byTopic` for the Kafka
  door else `byListener`, LabFlow and LedgerFlow `byIngress(facts.ingress())`.
- **Red first:** `IdentifyTenantTest` (4 tests) — `factsThatAreNotRoutesphereFacts_areRefusedNoTenant_andTheHistoryNamesTheirClass` red on the
  code before the note (Maven run, 1 failure of 7); then green.
- **Mutants (4, each red, each restored with `shutil.copyfile`):** the class note removed; `identifyTenant` given a default (the ingress rule)
  → the drift guard red; a door no rule names answered a default root → the helpers test red; the lane's null overruled by the kit's rules →
  `aLaneThatNamesNoRoot` red.
- **Tests touched:** `RoutesphereFlowDriftTest.FORCED` (+ `identifyTenant`); `RootFirstTest`'s javadoc (the name).

## Item 2 · `identifyPartner` (R2)

- `identifyEntryPartner(ctx, candidate)` → **`identifyPartner(ctx, candidate)`** (abstract; the candidate stays — the ad's payer depends on it).
  The above-the-leaf default `identifyPartner(Tier)` → **`partnerAbove(Tier)`** (the `res_<id>` rule unchanged). The admission's trace words
  follow the names: `IDENTIFY_PARTNER` (the leaf, was `IDENTIFY_ENTRY_PARTNER`) and `PARTNER_ABOVE` (was `IDENTIFY_PARTNER`) — no test read them.
- **Helpers** (final, in `Lookups`): `byPartnerId(ctx, Integer id)` (= the old `entryOfPartner`, which is gone; a null id = null) and
  `byPartnerName(ctx, name)` (new `RootTree.entryNamed`: the ONE partner of that name in the session's own tree, no case; a name two partners
  share names nobody — D5's rule for ids, applied to names).
- **Red first:** `IdentifyPartnerTest` (4 tests) — red by compile (the helpers missing), then green. **Mutants (4, red):** the first of two
  partners with one name wins; the name compared with its case; `byPartnerId` without the no-id guard; `identifyPartner` given a default.
- **Tests touched (the rename only, no expectation changed):** the anonymous `LabFlow`s of `AdmissionBudgetTest`, `AdmissionWalkTest`,
  `RootFirstTest`, `RoutingByCandidateTest` (`identifyEntryPartner` → `identifyPartner`, `entryOfPartner` → `byPartnerId`); `LabFlow`,
  `LedgerFlow` and the four samples the same; the drift set.
- **The finding for the prime-context round (the brief: write it, do not invent it).** A partner by what the WIRE carries is NOT on the tree
  the seed reads: the voice lane keeps `partnerBySource` (`<root>|<source ip>`), the WiFi lane `partnerBySubscriber` (`<root>|<msisdn>`), the
  SMS lane `partnerByUser` (the bind's system id), the ledger sample `partnerByMac` — each a map of its own. rtc-domain's `DynamicContext`
  DOES have call-side indexes (`routeWisePartners`, `sipAccountWisePartners`, `prefixWisePartners`, `callerIdWisePartners`,
  `ipOrDomainWiseRoute`), but nothing says prime-context's tree road fills them per tier, and there is no index for a WiFi subscriber or an SMS
  system id. Proposal: prime-context serves, per tier, `ingressKey → partnerId` indexes (ip, prefix, msisdn, system-id) in the tree; then
  `byIpOrPrefix`, `bySubscriber`, `bySystemId` are one-line helpers like `byPartnerId`. Until then the lanes keep their maps.

## Item 3 · the facts' sample refused at build (R2)

- `RoutesphereEngine.Builder.start()` → `refuseASignalingWithoutItsFactsSample()` (statewalk 4.1's `MachineSpec.upSamples()`): no
  `SessionStart` sample → "[AdSupervisor] the signaling machine 'AdPlayback' shows no SessionStart sample — add .upSample(new SessionStart(<the
  lane's RoutesphereFacts>)) to its spec: … every session would be refused NO_TENANT"; a sample of other facts → "… its SessionStart sample
  carries <class>, not RoutesphereFacts …". With a store, statewalk 4.1 round-trips the sample at build — the four sample lanes' persisting
  scenarios (restores of the call and the SMS) passed it.
- Every spec shows its sample: `LabCall` (`LabFacts`, esl 10.10.199.9:8021), and `AdPlayback`, `EslCall`, `SmsSubmit`, `WifiGateway` — each
  `.upSample(new SessionStart(factsOf.apply(SAMPLE)))`, so the sample is exactly what the spec's own facts function makes (a spec given another
  facts function shows that one's facts, and is refused when they are not RoutesphereFacts).
- **Red first:** `FactsSampleAtBuildTest` (3) and `SpecSamplesTest` (1) all red on the code before (the first red of the two refusal tests was
  for statewalk's own reason — the test machine's state map was not valid; the map was fixed, and mutants M3-1/M3-2 show the reds for the
  check's own reason). **Mutants (3, red):** no sample passes; a sample of other facts passes; LabCall without its sample.
- No existing test changed.

## Item 4 · one supervisor class per lane (R6)

- **`api/RoutesphereSupervisor<C>`** (abstract, no logic): holds the flow and the contexts; `name()` = `flow.name()` (ONE name: statewalk's
  registered supervisor, its table, the log), `flow()`, `spec()` = `SessionSupervisor.spec(name(), contexts, flow)` — statewalk's final
  `SessionSupervisor` stays the machine (the 2026-10-05 ruling).
- **The one door:** `RoutesphereEngine.of(RoutesphereSupervisor<C>)`; `of(flow, contexts)` is gone, so no lane runs without its class.
- **The classes:** `AdSupervisor(AdFlow)`, `CallSupervisor(VoiceFlow)`, `SmsSupervisor(RoutesphereFlow<SmsContext>)` (the SMS flow is made by
  the builder from item 5 on), `WifiSupervisor(WifiFlow)` in the samples, `LabSupervisor(LabFlow)` in the base tests — each with `(flow)` and
  `(flow, contexts)` (a bed that keeps its contexts). `LedgerFlow` runs no engine (its tests call the handler), so it has no class.
- **`FlowBed`** takes the lane's class over the contexts it keeps: `new FlowBed<>(c -> new AdSupervisor(flow, c), AdContext::new, …)`.
- **Red first:** `SupervisorClassPerLaneTest` (1) — red by compile (no class), then green. **Mutants (2, red):** statewalk's spec named by the
  flow's class instead of the lane's name; the voice lane named `VoiceSupervisor` apart from its class.
- **Tests touched (the door only, no expectation changed):** `Lab`, `OneTablePerLaneTest`, `OneRecoveryRoadTest`, `CreditControlTest`,
  `FactsSampleAtBuildTest` (`of(new LabSupervisor(flow))`); the FlowBed sites of `AdFlowTest`, `VoiceFlowTest` (2), `VoiceFlowDoorbellTest`,
  `RestoreLostUpTest`, `SmsFlowTest`, `WifiFlowTest`.

## Open questions for the owner / the architect

(none yet)
