# P1-E · stream F — the framework round: the extension contract, the bootstrap kit, naming, the tenant-created handler (report)

Branch `p1-rules` (worktree `routesphere-seed/worktrees/p1-rules`), from f41eace (stream D's four commits + the brief). Nothing pushed;
nothing written into `~/.m2`; no database server, broker or prime-context reached (in-process fakes, Kafka's mocks, H2 in memory only).
**Status: items 0–11 done (53bd001: worktree and clean copy 501/0/0/3); CONTRACT DONE at c992262; item 12 done; item 13 (a new reseller tier on the switch ledger, the architect's) next.**

| | tests / failures / errors / skipped |
|---|---|
| the reactor on f41eace, before any change (from `clean`) | **465 / 0 / 0 / 3** |
| after item 0 (from `clean`) | **434 / 0 / 0 / 3** |
| item 1, a git-archive clean copy of 4a8b22a (offline, under the lock) | **438 / 0 / 0 / 3** |
| **CONTRACT DONE** — item 5's c992262 on a git-archive clean copy | **452 / 0 / 0 / 3** |
| items 0–11 — 53bd001, the worktree from `clean` and a git-archive clean copy (offline, under the lock) | **501 / 0 / 0 / 3** both |

## 0 · The commits

| commit | item |
|---|---|
| 1caa152 | **item 0** — the records road removed (R13) |
| 4a8b22a | **item 1** — `identifyTenant` forced, with the door helpers |
| 891326b | **item 2** — `identifyPartner`, `partnerAbove`, `byPartnerId` / `byPartnerName` |
| c1f5043 | **item 3** — the facts' `SessionStart` sample refused at build |
| 1a3c83c | **item 4** — one supervisor class per lane, the one engine door |
| c992262 | **item 5** — the builder `RoutesphereApp` (the SMS sample made with it) — **CONTRACT DONE** |
| 88d721f | **item 6** — the kit from ONE bootstrap url |
| 0ad4b7d | **item 7** — `seed-naming`, the topics made at the start |
| 7b89e30 | **item 8** — a tenant created live (`tenant_created`) |
| 6f821f6 | **item 9** — the prepaid piece written down, its pins |
| b36db3d | **item 10** — the ad sample answers at `/shown` |
| 53bd001 | **item 11** — the docs |

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

## Item 5 · the builder (R5) — CONTRACT DONE

- **`api/RoutesphereApp`**: `RoutesphereApp.<C>builder(String name)` → `Builder<C>` with the forced answers `serviceGroup(int)`,
  `identifyTenant((facts, find) -> root)`, `identifyPartner((ctx, candidate, find) -> tier)`, `rateAtLevel((ctx, tier, partner) -> rate)`,
  `routing(ctx -> plan)`, `delivered((ctx, outcome) -> yes/no)`, `refundPolicy(ctx -> policy)`, `rerouteActionFor(cause -> action)` — each
  REQUIRED: `build(kit)` refuses with every missing one named and its question ("[SmsSupervisor] every lane states its rules (R2) — the builder
  has no serviceGroup(int) — the CDR's service group; no identifyTenant(…) — which root operator owns this door? …"). The optional hooks
  (the call's default when not given): `buildTask`, `selectCandidates`, `authorize`, `applyRootRules`, `confirmAdmission`, `settleOutcome`,
  `billedUnits`, `rateNextWindow`, `onSignalingProgress`, `onAnswered`, `onServiceEnded`, `fillCdr`, `buildSdr`, `onEnded`. A lane that needs
  more (the voice's slots, the WiFi's renewal) subclasses — R5's "big lanes subclass; small lanes use the builder".
- **The helpers from a lambda:** the two identify answers get the base's lookups as their LAST argument (`find.byListener(…)`,
  `find.byPartnerId(ctx, id)`) — the brief's `Function<RoutesphereFacts,String>` / `BiFunction<C,Candidate,Tier>` shapes plus the `Lookups`
  argument it offered ("through a Helpers argument"); three small named interfaces carry the three-argument answers (`TenantRule`,
  `PartnerRule`, `RateRule`, plus `TierGate` and `CdrFill` for the optional ones).
- **`api/AppFlow`** (package-private): the flow `build(kit)` answers — a NAMED subclass rather than an anonymous one (readable, one file); its
  only state is the kit and the sealed builder (the lane's rules, the pool rule). The lambdas run under the base's `safely` / `guarded` like
  overrides (a throw at admission refuses the candidate `INTERNAL_ERROR` — pinned). **Sealed:** once built, a setter refuses ("the application
  is built: its answers are sealed"); the same builder may build the flow of another kit.
- **The proof — the SMS sample as a builder:** `SmsFlow` is now a small class whose `SmsFlow.of(kit, partnerByUser, ratePerSegment,
  smscsByRoot, refundOnNonDelivery)` builds the lane; its rules are lambdas and four small methods (`rootByTheDoor`, `taskOf`, `everySegment`,
  `theRootsSmscs`, `nextSmscWhenCongested`, `whatTheReceiptSaid`). `SmsFlowTest` (7) and `SmsSubmitTest` (7) green; **`SmsFlowTest` changed in
  ONE line** (`SmsFlow flow = new SmsFlow(…)` → `RoutesphereFlow<SmsContext> flow = SmsFlow.of(…)` + its import) — no expectation;
  `SmsSubmitTest` unchanged. AdFlow, VoiceFlow, WifiFlow stay subclasses.
- **Red first:** `RoutesphereAppTest` (5) — red by compile, then green. **Mutants (5, red):** a missing `rerouteActionFor` passes; the answers
  never sealed; a given `buildTask` ignored; the lambdas not handed the lookups; the SMS lane without its `settleOutcome` (2 money scenarios
  of `SmsFlowTest` red: the refund on non-delivery charged).

## Item 6 · the kit from ONE bootstrap url (R8)

- **The door:** `RoutesphereKit.fromBootstrap(BootstrapSource source, String app)` (= `…(source, app, KitMakers.onTheClasspath())`). The
  application's yml holds `prime-context.bootstrap-url` and `prime-context.token-env` (the variable's NAME) — `BootstrapSources.fromYml(yml,
  System::getenv)` reads both (nested or dotted keys) and nothing else. Both doors stay: the samples build their kits by hand.
- **`spi/BootstrapSource`** — `envelope()`, `origin()` (prime-context's `scheme://host:port`: the tree road `get-specific-tenant-root` is
  there), `where()` (never the token). `internal/HttpBootstrapSource`: GET with `Authorization: Bearer <token read by its variable at every
  ask>`, `If-None-Match` with the last etag (the header, else the body's `etag`), a 304 answers the envelope held; an empty token variable is
  refused naming it (S20); a non-2xx or no answer → `BootstrapUnavailable` in words. `testkit/MapBootstrapSource` for tests (a JSON resource,
  swappable, asks counted).
- **`api/Deployment`** (+ `api/RootResources` with `SwitchDb`, `KafkaRoad`, `Ledger`) read by `internal/DeploymentReader` from §C.1's
  `config.routesphere`. **Refusals in words:** another app's url ("the bootstrap url serves the app 'ad-sphere', not 'wifi-sphere'"); no
  `config.routesphere`; no roots; a served root without its block; a root without `kafka.bootstrap` (its CDR has no road) or `journal.dir`; a
  rule for a root not served; a ledger kind no maker answers. **Defaults:** pool / threads / timers from the base's defaults; the ledger kind
  `switch` (R12, item 9); the zone Asia/Dhaka; `globalTimeoutSec` = the sum of the state timers + 60 s; `reservePeriodSec` 0. A password by
  reference (`env:<NAME>`) is read from the environment, else the envelope's `secrets`; a literal password is refused.
- **What the kit gets per served root** (`internal/BootstrapRoad`, kept for item 8): its tree (`spi/TreesMaker` → `spi/LiveTrees`), its doors
  (`internal/LiveRules`: the rules swapped whole when a root is added), its ledger (`spi/LedgerMaker` by kind → `internal/PerRootLedger`: every
  tier's money on its OWN root's ledger, a tier of no served root a `LedgerFault`), its CDR road (`internal/PerRootCdrSink`: `cdr_<root>` on the
  root's own Kafka, one producer per bootstrap address; a root with no road = one ERROR, never another root's topic), its journal of the
  sessions in the air (`internal/PerRootJournal`: `<root dir>/sessions-in-the-air.jsonl`, a session's lines to its root's file — named by its
  first reserve, else its records' root; the next start reads every root's leftovers). **The start names every resource it got** — one INFO,
  one line per root: "link3: tree v0, doors [listen 10.10.188.2:7071 → link3], ledger switch on jdbc:postgresql://…/routesphere schema link3,
  CDR cdr_link3 on 10.10.188.2:9092, redis redis://10.10.188.2:6379, dir /var/lib/ad-sphere/link3" (pinned by the test).
- **The makers** (`dependencies/KitMakers`): `onTheClasspath()` finds the trees maker and the ledger makers by `java.util.ServiceLoader` —
  **seed-tenant `PrimeContextTrees`** (`TenantTrees` over the tree road at the bootstrap's origin, the doorbell on the platform Kafka; every root
  loaded before the kit is made, a root that cannot be is refused; `serve(root)` / `reload(root)` for item 8) and **seed-switch-ledger
  `SwitchLedgerMaker`** (a MemLedger over the root's `switch-db` — url, user, password by reference — with the two tables of every tier schema of
  the root's tree, its WAL and queue in `<root dir>/ledger`, STANDALONE; `SwitchLedgers.memLedger` over it; one connection per use by
  `DriverManager` unless the host hands its own pooled `DataSource` maker); the `orchestrix` ledger is built in (`ledger: {kind: orchestrix,
  url, token-env, tenant, profile}`, its owed journal in the root's directory); the real Kafka clients (`internal/RealKafkaClients`); a file
  journal per root's directory. **Why ServiceLoader:** seed-tenant and seed-switch-ledger depend on seed-sessionflow, so the kit cannot name
  them; the classpath carries them and a missing one is refused in words ("the ledger kind 'switch' has no maker on the classpath — add
  seed-switch-ledger"). The application's code stays one line.
- **`testkit/FakeKafka`** (for this round and P2): a `MockProducer` / `MockConsumer` per bootstrap and an admin (a JDK proxy of Kafka's
  `Admin` doing `createTopics`: made once, then "exists"; a cluster scripted to refuse refuses) — no broker on the box.
- **The §C samples** are `seed-sessionflow/src/test/resources/bootstrap/deployment.json` and `tenant-created.json` (sha256 e1ca4f43… and
  63f396e2…). **One change from the brief's text:** §C.1 shows link3's block as `{"…": "…"}` placeholders; the pinned file fills link3 like
  btcl (schema link3, the same Kafka and Redis, `/var/lib/ad-sphere/link3`) so the whole kit can be built from it. The prime-context agent
  pins THESE bytes (through the architect).
- **Tests:** `KitFromBootstrapTest` (11: the whole kit from the sample, the start's words, each root's CDR road, a tier's money on its own
  root's ledger, the default ledger kind, six refusals), `HttpBootstrapSourceTest` (3: the token, the 304, the words; prime-context a JDK
  `HttpServer` in the JVM on an ephemeral loopback port), seed-tenant `PrimeContextTreesTest` (5), seed-switch-ledger `SwitchLedgerMakerTest`
  (4: a REAL MemLedger over H2 in memory, as `LedgerLab`'s). `LedgerLab` gained `url()` (a test helper). **Red first:** all by compile.
  **Mutants (9, red):** no roots passes; a root without its block passes on; a rule for a root not served passes; another app's url passes;
  every tier's money on one root's ledger; the etag never sent back; the ledger kind with no default; a root prime-context cannot load passes;
  the switch ledger without its database passes.

## Item 7 · naming and auto-creation (R9)

- **The module `seed-naming`** (plain Java, no dependency; in the reactor before `seed-sessionflow`, in the bom): `api/TenantNames`
  (`withSeedRoot(seedRoot)` / `strict()`: `tierDb(root, parentDb, partnerId)` = `<root>_res_<id>`, nested `<parent>_<id>`; the seed root's
  legacy `res_<id>` (LOOSE); `parentOf(root, tierDb)` by the root's prefix, never by counting underscores; `isTierOf`; `rootOf(db, roots)` —
  prime-context's `RootNaming` copied with its citation; the seed root's NAME comes from the bootstrap, `Deployment.tenantNames()` reads
  `config.routesphere.naming.seed-root`), `api/Topics` (`platform(name, root)` = `<name>_<root>`; `app(app, Direction, name, root)` =
  `<app>_<in|out>_<name>_<root>`; `checked(topic)`: Kafka's legal characters, ≤ 249, no `.` — Kafka's metrics read `.` as `_`, so twins would
  collide; `TENANT_CREATED` = `tenant_created`), `api/RedisKeys.of(root, app, name)` = `<root>:<app>:<name>` (a part with `:` or a space
  refused), `api/Direction` (`in`, `out`). Why a module and not seed-tenant's package: the engine (seed-sessionflow) makes the topics, and
  seed-tenant depends on seed-sessionflow.
- **`dependencies/TopicProvisioner.ensure(admin, topics, partitions, replication, where)`** through Kafka's `Admin.createTopics`: every name
  checked first (refused before the cluster is asked), each named once; `TopicExistsException` = it was there (fine); any other failure (no
  right, no answer within 10 s) = refused, said ONCE in one WARN naming every refused topic, and the start goes on (the brokers may make a
  topic at its first message). Answers `Ensured(created, existed, refused)`.
- **At the start** (`RoutesphereEngine`, before the registry is built) on a kit made from the bootstrap: for every served root
  `cdr_<root>` (the switch publishes), `config_event_loader_<root>` (the doorbell it listens to) and the lane's own — a new hook
  **`topicsOf(root)`** (base default: none; the builder's optional `topicsOf(root -> …)`), on the root's own Kafka with its partitions and
  replication. A kit built by hand makes none. The CDR road of a bootstrap kit now names its topic by `Topics.platform("cdr", root)`.
  Not made: `cdr_summary_ping_<root>` (billing-core's), `tenant_created` (prime-context's) — the switch neither publishes them nor owns them.
  Redis needs nothing made: the key is the namespace.
- **Red first:** the naming tests (10) on stub classes — all 10 red, then green; `TopicProvisionerTest` (3), `TopicsAtStartTest` (2) by
  compile. **Mutants (12, red):** the seed root's legacy prefix ignored; the parent by the first underscore (SURVIVED first → a three-level
  assertion added → red); a word as a tier's id; a `.` in a topic; the length limit off by one (SURVIVED first → a 250-character assertion
  added → red); a `:` in a Redis part; an app's topic without its direction; a topic already there taken for a refusal; a name asked twice;
  the engine never starts the road; the lane's own topics ignored; a name not checked before the cluster is asked.

## Item 8 · a tenant created live (R10)

- **`api/TenantCreated`** — §C.2 read (`root`, `tierDb`, `parentDb`, `partnerId`, `kind`, `apps`, `at`; unknown fields ignored; no root =
  refused in words); `isRoot()` (`kind: root`), `names(app)` (no list = every app), `identity()` (kind:root:tierDb — the same tenant created
  twice is one event).
- **`internal/TenantCreatedListener`** — subscribes `Topics.TENANT_CREATED` through the kit's Kafka (`KafkaClients.consumer(platformKafka,
  <app>-tenant-created-<uuid>)`: its own group, so every process hears every event), one daemon thread, `pollOnce` for tests; a message that
  is not §C.2's is one WARN and the next is read; `close()` wakes the consumer and joins.
- **The road's `onTenantCreated(event)`** (≤ 10 lines of named steps): another application's or a seen event → nothing; a new ROOT →
  `serveTheNewRoot`: the bootstrap read again (the source's If-None-Match makes it cheap); the root must be in its roots now, else one WARN
  and the event is forgotten so a later word is heard; then the root's tree (`LiveTrees.serve`), its roads (ledger, CDR road, journal — the
  same `makeTheRootsRoads` as at start), its doors (`LiveRules.replace` with the new rules) and its topics (item 7); one INFO with the root's
  resources. A new reseller TIER of a served root → `LiveTrees.reload(root)` (the doorbell's road; the next session takes the new snapshot);
  of a root not served → nothing. No restart anywhere: the engine resolves the root of every session through the kit's live parts.
- **Not done here (a gap, see below):** a new tier's SCHEMA for the switch ledger — the MemLedger of a root was built over the tiers it had.
- **Tests:** `TenantCreatedLiveTest` (6: the sample message read; a new root on the topic served live end to end — through the running
  listener, the fake's MockConsumer — its door, tree, ledger and topics; the same event twice once; a new tier reloads its root once;
  another app's and a tier of a root not served are nothing; a root not served yet, then heard again), `TenantCreatedListenerTest` (1). The
  fake Kafka's consumer is a paced `MockConsumer` (Kafka's answers a poll at once: a listener's loop would spin a core).
- **Red first:** by compile. **Mutants (6, red):** the same event served twice; another application's tenant served; a root not served yet
  never heard again; the new root's doors not added; the listener never started; a bad message stops the batch.

## Item 9 · the prepaid piece, documented and defaulted (R12)

- No new money code: `reserveBalance` → `finalizeCost` already IS the one reserve / finalize. The README's new §5.1 "The prepaid piece" says
  the two apis (reserve at admission, every tier, one unit at its rate; finalize at the final state: delivered → rate × billed units, the
  reserve back and the difference the final adjustment — an overrun is an extra debit, the call's rule; not delivered → the lane's refund
  rule), the SMS's case (the refund rule returns a failed message's whole reserve), idempotent per (session, tier).
- **The default ledger from the bootstrap = `switch`** (seed-switch-ledger): item 6's `DeploymentReader` (a root's `ledger.kind` not said →
  `switch`), pinned by `KitFromBootstrapTest.theLedgerKind_isTheSwitchWhenNotSaid` and mutant M6-7.
- **The pins:** `base/PrepaidPieceTest` — an answered call (one tier, rate 1.00, billed 0.25 of the unit) pays 0.25 and 0.75 of its reserve
  comes back; `samples/sms/SmsPrepaidPieceTest` (2) — a message no SMSC took and an accepted one not delivered (refund by config) return
  their whole reserve at all three tiers. **No red first** (the item adds no behaviour — they pin what the base does). **Mutants (2, red):**
  the call charged its whole reserve whatever its units; a message not delivered charged whatever the lane's rule.

## Item 10 · the ad sample answers at /shown (the owner's ruling)

- **`AdPlayback`:** `/shown` publishes the `Established` (CONNECTING → ACTIVE; the creative, the media kind, 0 seconds watched); the beacons
  (`BEACON`) and `/viewed` (`VIEWED`, the seconds watched) are `SignalingProgress` in ACTIVE (statewalk 4.1 takes them there); every end after
  `/shown` — `/abandoned` (`ServiceEnd ABANDONED SUBSCRIBER`), the stall timer (`STALLED TIMER`), the supervisor's `Stop` / `Reject`
  (`SUPERVISOR`), `/credited`, the credit window — is a `ServiceEnd`: the view is SETTLED, never released. Before `/shown` nothing changed
  (an end is a `SignalingFailed`). New helper `watchedSecondsOf(SignalingProgress)`; the class javadoc's diagram marks CONNECTING / ACTIVE.
- **`AdFlow`:** `onAnswered` marks the view `shown` (delivered = shown, unchanged); `onSignalingProgress` takes the hand-over (OFFERED), and
  the seconds watched from BEACON / VIEWED, and marks `viewed` on VIEWED (new `AdContext.viewed`); `taskCompleted` = SUCCEEDED **and**
  viewed (a view only shown does not complete its campaign task — the base's default would have completed it now that it SUCCEEDS).
- **Changed test expectations (before → after):**

| test | before | after |
|---|---|---|
| `AdPlaybackTest.a_whole_view_…` after /shown | `CONNECTING/SHOWING` | `ACTIVE/SHOWING`, the answer read there (creative c-77, codec video, **0** watched) |
| same, after two beacons | `CONNECTING/SHOWING` | `ACTIVE/SHOWING` (2 × `onProgress:BEACON`, unchanged) |
| same, after /viewed | `ACTIVE/VIEWED` + the answer read there with **15** watched | `ACTIVE/VIEWED` + one `onProgress:VIEWED` |
| `AdPlaybackTest.an_abandoned_view_fails_without_a_second_creative` → renamed `a_view_abandoned_after_it_was_shown_ends_settled_without_a_second_creative` | `onFailed:ABANDONED:0`, `release`, `record:FAILED:ABANDONED` | `onEnd:ABANDONED:SUBSCRIBER`, `settle:SUCCEEDED:…`, `record:SUCCEEDED:ABANDONED`, no `release` (the wire and "no re-route" unchanged) |
| `AdPlaybackTest.the_show_timer_…` the stall after /shown | `record:FAILED:STALLED` | `onEnd:STALLED:TIMER`, `record:SUCCEEDED:STALLED` |
| `AdFlowTest.a_view_shown_then_abandoned_…` | balance 99.40 ("shown = consumed … even though it failed") | balance 99.40 unchanged; + outcome `SUCCEEDED`; + the task `FAILED` (never watched to its end) |

  Unchanged: the never-shown timer (`FAILED NOT_SHOWN`, the unshown rule), the deadlines (TIMEOUT_CONNECTING while offered;
  MAX_DURATION_REACHED once viewed, settled), the credit window, the stop in the credit window, the restore in the credit window
  (`ACTIVE/VIEWED` on both nodes), every balance of `AdFlowTest`.
- **Red first:** 4 of 15 (`AdPlaybackTest` 3, `AdFlowTest` 1) on the old machine with the new expectations; then green. **Mutants (4, red):**
  /shown a progress again (6 red); an abandon after /shown a failure again; a task completed when only shown; the answer not marking the view
  shown (money and the restore red).

## Item 11 · docs

`seed-sessionflow/README.md` rewritten: §1 the classes (the builder, the supervisor class, `Lookups`, the deployment); §2 one package per
application (R6's six files), a subclass, the builder, the kit from the bootstrap and the one engine door; §3 the contract table — every
method forced ■ / final / default with the call's answer, one line each; §4.1 naming (R9); §4.2 the bootstrap (R8) and a tenant created live
(R10); §5.1 the prepaid piece (R12, item 9); §7 the packages; §9 the history. `RoutesphereFlow`'s class javadoc follows (two ways to write a
lane, one package, the kit) — the drift guard still finds exactly the nine ■. The root README names `seed-naming` and the framework.

## Item 12 · the application's WHOLE config from the bootstrap (R8; added by the architect after the report)

- **The module `seed-bootstrap-config`** (depends on seed-sessionflow for the bootstrap's one road; SmallRye Config **provided** —
  `smallrye-config-core` 3.13.4, Quarkus 3.26.1's, ad-sphere's platform; `smallrye-common-*` pinned to 2.13.8 in the module's
  dependencyManagement, because SmallRye 3.13.4 asks 2.13.7, which this box's repository does not hold — Quarkus 3.26.1 runs 2.13.8).
  Nothing new in seed-sessionflow.
- **`dependencies/BootstrapConfigSourceFactory`** — a SmallRye `ConfigSourceFactory` registered by `META-INF/services` (a Quarkus app gets it
  by adding the dependency): reads `prime-context.bootstrap-url` and `prime-context.token-env` from the lower sources, fetches the envelope
  through **item 6's road** (`BootstrapSources.http`: the token read by its variable's name, `If-None-Match`), serves the envelope's `config`
  as ONE source (`internal/EnvelopeConfigSource`) at **ordinal 275** — above the jar's files (250), below the environment (300) and `-D`
  (400). `internal/FlatConfig`: a nested map `a.b.c`, a list `a.b[0]`, a map in a list `a.b[0].c`, a key holding a dot quoted
  (`quarkus.log.category."com.telcobright".level`, as SmallRye's YAML source writes it), a null no property.
- **Behaviour:** no bootstrap url = no source (the application's own files; the kit's own door still refuses a missing url when it is used);
  a url without its token's variable name / an empty token variable / prime-context unreachable or refusing = **the start refused, exactly as
  the kit's door** (the same `BootstrapUnavailable` and words — the README says it). **Never a secret:** only `config` is served, the
  envelope's `secrets` never; a `password-ref` stays a reference.
- **Recommendation (not code):** a cached last-good copy of the envelope (written after every good read, read when prime-context does not
  answer, with its etag and age said loudly) would let a box restart while prime-context is down — the owner's call: today a start without
  prime-context is refused, which is the fail-closed reading of R8.
- **The new sample** `seed-sessionflow/src/test/resources/bootstrap/deployment-with-app-keys.json` (beside the pinned §C files, which are
  unchanged): the §C envelope with an application's own keys (`quarkus.http.*`, `adsphere.*` with a list and nested maps, `sdr.*`, `cdr.*`,
  `reports.*` with a `password-ref`, `routing.*`) and a `secrets` map that must never be served. The module copies that directory as its test
  resources (one place for the samples).
- **Tests:** `BootstrapConfigSourceTest` (7 — SmallRye built by hand over a map-backed bootstrap: the app's keys above the jar, a list and a
  dotted key, the environment and `-D` still win (and the source's ordinal 275), never a secret, no url = no source, the refusals in words,
  the ServiceLoader registration) and `FlatConfigTest` (1). **Red first:** by compile. **Mutants (8, red):** the source below the jar (240);
  above the environment (310); the secrets served; a list as one comma word; a dotted key not quoted; an unreachable bootstrap swallowed; no
  url refused instead of no source; the factory not registered.
- **For P2:** the app adds the dependency and its yml shrinks to the two lines; its own keys move into prime-context's app document (the
  envelope's `config`, beside `routesphere`); a key an operator must override on a box is an environment variable (300) or `-D` (400).
  Quarkus reads BUILD-time properties before any runtime source — those stay in the jar.

## Found after the final run (the final logs read)

The final worktree and copy logs carry one stack trace the baseline's did not: a MemLedger's WAL consumer of
`SwitchLedgerMakerTest` failing on "Database is already closed" after its test ended — the test never shut the MemLedger the maker
opened, and, the real gap behind it, **the bootstrap road's `close()` did not close the ledgers it made** (a MemLedger's threads would run
on after the engine's close). The tests were green. Fixed in the commit after the report (see "After the report" below).

## The findings for the prime-context round (the envelope, §C)

1. **The identification rules have two homes.** prime-context's `IdentificationContributor` serves them at `config.identification` as
   `[{kind, match, tenant}]`; §C pins `config.routesphere.identification.rules` with `{root, kind, match}`. The seed reads §C's home
   (`IdentificationRules.fromRoutesphere`, which also takes a bare list and `tenant` for `root`). prime-context should serve §C's home for a
   routesphere application.
2. **`timings.routingSec` cannot be applied** — statewalk's ROUTING timer is fixed (1 s, `SessionSupervisor.ROUTING_SEC`); the sample says 5.
   The seed reads it, says one WARN, and does not apply it. Drop it from §C (or statewalk makes it a setting).
3. **The seed root of the legacy names is not in §C.** btcl on the bed keeps `res_<id>`; the seed reads an optional
   `config.routesphere.naming.seed-root` (absent = every root strict) — prime-context knows it (`primecontext.admin-db`) and should serve it.
4. **The platform's Kafka is not in §C** (where `tenant_created` and the doorbell live); the seed reads an optional
   `config.routesphere.kafka.bootstrap`, else the first served root's Kafka.
5. **The zone is not in §C**: an optional `config.routesphere.zone`, Asia/Dhaka when absent. Other optional knobs read when present:
   `reservePeriodSec`, `reserveInitialDelaySec`, `slotReconcileSec`, `admissionReserveMs`, `globalTimeoutSec` (default: the timers' sum +
   60 s), `debug`; an `orchestrix` root's ledger words `url`, `token-env`, `tenant`, `profile`.
6. **The tree road** is read at the bootstrap url's origin (`POST <origin>/get-specific-tenant-root?name=<root>`, `FsController` at `/`),
   with no bearer (open today). If prime-context gates it, §C should say how.
7. **§C.1's link3 block was placeholders**; the pinned sample fills it like btcl's (see item 6).
8. **A partner by what the wire carries** (ip / prefix / msisdn / system id) is not on the tree the seed reads — item 2's finding.
9. **A new reseller TIER and the switch ledger:** `tenant_created` (kind reseller) reloads the root's tree, but a root's MemLedger was built
   over the tier schemas it had at start — the new tier's `packageaccount` rows are not loaded. MemLedger has `reloadDatabases()` /
   `autoDiscoverDatabases(prefix)`; wiring a tier event to it (or rebuilding that root's ledger between sessions) is the next round's —
   NOT done here (no MemLedger behaviour was proven for it, and the brief keeps the money code unchanged).

## Choices made where the brief left a road open

- **The makers by `ServiceLoader`** (item 6): seed-tenant and seed-switch-ledger depend on seed-sessionflow, so the kit cannot name them;
  the classpath carries them, a missing one is refused in words, and the application's code stays one line. `fromBootstrap(source, app,
  makers)` takes them by hand (tests, a host's pooled `DataSource`).
- **The helpers as one object** (`api/Lookups`, `kit.lookups()`), the base's final helpers one-line delegates, the builder's identify lambdas
  given it as their last argument (`find`) — one implementation for both ways of writing a lane.
- **A named `AppFlow`** instead of an anonymous subclass; **`SmsFlow.of(kit, …)`** (the lane made by the builder keeps its class name and
  its rules as small methods); **`seed-naming` a module** (the engine needs it; seed-tenant could not host it).
- **The journal per root** (`PerRootJournal`), because §C gives each root its own directory: a session's lines follow its first reserve's
  root (a free view: its records' root at the hand-over); the next start reads every root's leftovers. P2's brief (§E) names ONE file
  `<cdr.dir>/views-in-the-air.jsonl` — on the bootstrap kit the ad's journal is the per-root one (`<root dir>/sessions-in-the-air.jsonl`).
- **The topics the start makes:** `cdr_<root>` (the switch publishes), `config_event_loader_<root>` (it listens), the lane's
  `topicsOf(root)`. Not `cdr_summary_ping_<root>` (billing-core's) nor `tenant_created` (prime-context's).
- **`tenant_created`'s consumer group** is per process (`<app>-tenant-created-<uuid>`): every running application hears every event (R10's
  "every running application … loads the new root").
- **The bootstrap token** is read from the environment at every ask (a rotated token is taken without a restart).

## The numbers

| run | tests / failures / errors / skipped |
|---|---|
| f41eace, before (worktree, from clean) | 465 / 0 / 0 / 3 |
| item 0 (worktree, from clean) | 434 / 0 / 0 / 3 |
| item 1, clean copy of 4a8b22a | 438 / 0 / 0 / 3 |
| **CONTRACT DONE**, clean copy of c992262 | **452 / 0 / 0 / 3** |
| **53bd001** (items 0–11), the whole reactor in the worktree from `clean` (offline, under the lock, 01:45–01:47) | **501 / 0 / 0 / 3** |
| **53bd001**, a git-archive clean copy (offline, under the lock, no install, 01:47–01:49; the copy removed) | **501 / 0 / 0 / 3** |

Between the Maven runs the items were proven by direct runs (javac + the JUnit launcher, the module's classpath; no Maven, no lock): the
last of them sessionflow 190, samples 73, switch-ledger 54, tenant 19, naming 10 — 0 failures. One timing flake was seen once in a direct run
under load (`VoiceFlowTest.a_talking_call_renews_one_minute_per_period…`, the budget child's cut: `ACTIVE/ANSWERED` where
`ACTIVE/DISCONNECTING` was awaited); three reruns green; not seen under Maven. Mutants: 51 hand mutants (items 1–10), every one red (two survived first —
N2, N5 — and were made red by two stronger assertions; one, M4-1, first ran on stale classes and was rerun red).

## Open questions for the owner / the architect

1. **A new reseller tier's schema on the switch ledger** (finding 9): wire `tenant_created` (reseller) to the root's MemLedger
   (`reloadDatabases()`), or rebuild that root's ledger between sessions — whose round, and does prime-context send the event only after the
   tier's tables exist?
2. **P2's journal:** on a kit made from the bootstrap the ad's journal of the views in the air is per root (`<root dir>/sessions-in-the-air.jsonl`),
   not P2 §E's one `<cdr.dir>/views-in-the-air.jsonl` — the P2 brief should follow.
3. **The ad at /shown (item 10):** a view abandoned or stalled after `/shown` now SUCCEEDS and pays its reserve (shown = delivered, the
   ruling); its campaign task is FAILED unless the view ran to `/viewed`. Confirm the task rule with the owner.
4. **The §C sample bytes** (`deployment.json` e1ca4f43…, `tenant-created.json` 63f396e2…) are the contract: the architect hands them to the
   prime-context agent (link3's block filled, see item 6; the optional keys of findings 3–5 are NOT in the sample).
