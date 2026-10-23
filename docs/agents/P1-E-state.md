# P1-E · stream F — state

**CONTRACT DONE** (items 0–5: c992262, committed 2026-10-24 00:29) — see the line of that time below.

One line per item, newest last. Times are Dhaka (+06).

Times corrected 2026-10-24 02:23 (by `date`): each item line now carries its commit's time from git (`git log --format=%ci`); the
lines were first written with guessed times, and items 8–11 carried times later than the real clock (02:30–03:20). From the resume on
every time is read with `date` first.

- 2026-10-23 23:05 · START — worktree `p1-rules` at f41eace (stream D's four commits + the brief); the reactor there **465/0/0/3** (offline,
  under the lock, 2 min 18 s). Order: items 0 → 11. Running: item 0.
- 2026-10-23 23:25 · **item 0 done** (1caa152) — the records road removed (R13): 25 main files, the `records/` tests, `AdRecordsTest`, three sample
  scene helpers, the P1-R brief and state; `cdrStatements` no longer forced; the CDR sink required again. The reactor from clean
  **434/0/0/3** (31 tests deleted, named in the report). Next: item 1 (`identifyTenant` forced).
- 2026-10-24 00:08 · **item 1 done** (4a8b22a) — `identifyTenant(RoutesphereFacts)` abstract, the five door helpers (`api/Lookups`, `kit.lookups()`),
  the facts-class refusal noted; red first (the class not named), 4 mutants red; modules green (direct runs: sessionflow 151, samples 69,
  switch-ledger 50). The clean-copy reactor of 4a8b22a runs in the background (the lock is shared with two other agents).
- 2026-10-24 00:12 · **item 2 done** (891326b) — `identifyPartner(ctx, candidate)` (was identifyEntryPartner), `partnerAbove(tier)` (was
  identifyPartner(tier)), the helpers `byPartnerId` / `byPartnerName` (`RootTree.entryNamed`, a name two partners share names nobody); red
  first (the helpers missing), 4 mutants red; sessionflow 155, samples 69, switch-ledger 50 green. Next: item 3 (the facts' sample at build).
- 2026-10-24 00:18 · **item 3 done** (c1f5043) — the engine refuses at start a signaling machine that shows no `SessionStart` sample of RoutesphereFacts
  (in words, both cases); LabCall + the four sample specs show theirs; red first (FactsSampleAtBuildTest 3/3, SpecSamplesTest 1/1), 3
  mutants red; sessionflow 158, samples 70, switch-ledger 50 green (one timing flake of VoiceFlowTest's budget-child test in a direct run
  under load, 3 reruns green). Item 1's clean copy (4a8b22a): **438/0/0/3**. Next: item 4 (one supervisor class per lane).
- 2026-10-24 00:22 · **item 4 done** (1a3c83c) — `api/RoutesphereSupervisor<C>` (thin: the flow, the contexts, name() = the flow's, spec()); the ONE
  door `RoutesphereEngine.of(supervisor)` (`of(flow, contexts)` gone); `AdSupervisor`, `CallSupervisor`, `SmsSupervisor`, `WifiSupervisor`,
  `LabSupervisor`; `FlowBed` takes the lane's class (`c -> new AdSupervisor(flow, c)`); red first (the classes missing), 2 mutants red;
  sessionflow 158, samples 71, switch-ledger 50 green. Next: item 5 (the builder) → CONTRACT DONE.
- 2026-10-24 00:29 · **item 5 done** (c992262) — `api/RoutesphereApp` (the builder: `builder(name)`, the seven forced answers + `serviceGroup` REQUIRED
  and refused in words, 15 optional hooks, sealed once built) + `api/AppFlow` (the flow it builds); the SMS sample rewritten with the
  builder (`SmsFlow.of(kit, …)`), `SmsFlowTest` / `SmsSubmitTest` green with one line of `SmsFlowTest` changed (the flow's type); red first
  (compile), 5 mutants red; sessionflow 163, samples 71, switch-ledger 50 green.

CONTRACT DONE — items 0–5 (the records road gone; identifyTenant / identifyPartner forced with helpers; the facts' sample at build; one
supervisor class per lane; the builder). The architect may merge up to item 5's commit and start the ad-sphere port on it. Running next:
item 6 (the kit from ONE bootstrap url).
- 2026-10-24 about 00:37 · the contract head **c992262** on a git-archive clean copy (offline, under the lock): **452/0/0/3** (started
  right after 00:29, 468 s with the lock wait).
- 2026-10-24 00:40 · **item 6 done** (88d721f) — `RoutesphereKit.fromBootstrap(source, app[, makers])`: §C's envelope → `api/Deployment` (roots,
  settings, zone, rules, each root's `RootResources`, the platform Kafka), refusals in words; per root its tree (`spi/TreesMaker` →
  seed-tenant's `PrimeContextTrees`), ledger (`spi/LedgerMaker`: `switch` → seed-switch-ledger's `SwitchLedgerMaker`, `orchestrix` built in),
  CDR road (`cdr_<root>` on its own Kafka), journal (its own directory); `spi/BootstrapSource` (`BootstrapSources.http` with the token by
  its variable and If-None-Match/304; `testkit/MapBootstrapSource`); `testkit/FakeKafka`; the §C samples at
  `seed-sessionflow/src/test/resources/bootstrap/`. Red first (compile), 9 mutants red; sessionflow 177, samples 71, switch-ledger 54,
  tenant 19 green. Next: item 7 (naming + topic auto-creation).
- 2026-10-24 01:03 · **item 7 done** (0ad4b7d) — module **`seed-naming`** (`TenantNames` = prime-context's RootNaming rule, copied with citation, the
  seed root by the bootstrap's word `naming.seed-root`; `Topics.platform` / `Topics.app` with Kafka's rules; `RedisKeys.of`; `Direction`);
  `dependencies/TopicProvisioner.ensure` (Admin.createTopics, idempotent, a refusing cluster said once); the engine's start makes every
  served root's `cdr_<root>`, `config_event_loader_<root>` and the lane's `topicsOf(root)` (a new hook, base + builder);
  `Deployment.tenantNames()`. Red first (naming 10/10 on stubs, the rest by compile), 12 mutants red (2 survivors made red by two stronger
  assertions); naming 10, sessionflow 183, samples 71, switch-ledger 54, tenant 19. Next: item 8 (tenant_created live).
- 2026-10-24 01:11 · **item 8 done** (7b89e30) — `api/TenantCreated` (§C.2 read), `internal/TenantCreatedListener` (subscribes `tenant_created` on the
  platform Kafka, its own group; a bad message said, the next read), the road's `onTenantCreated`: a new ROOT this app serves → the
  bootstrap read again, the root's tree / doors / ledger / CDR road / journal / topics made, served live; a reseller TIER → its root's tree
  reloaded; the same event twice = once; another app's = nothing; a root the bootstrap does not serve yet = WARN, heard again later.
  Red first (compile), 6 mutants red; sessionflow 190, samples 71, switch-ledger 54, tenant 19. Next: item 9 (the prepaid piece).
- 2026-10-24 01:15 · **item 9 done** (6f821f6) — no new money code: the README's "prepaid piece" (reserve / finalize, cost → refund → adjustment, the
  SMS = the refund rule, the default ledger `switch` from the bootstrap — pinned in item 6); `PrepaidPieceTest` (an answered call pays
  rate × units, the rest back) and `SmsPrepaidPieceTest` (a failed SMS returns its whole reserve, two ways) — green on the existing code (no
  red to see: the item adds no behaviour), 2 mutants red. Next: item 10 (the ad answers at /shown).
- 2026-10-24 01:21 · **item 10 done** (b36db3d) — the ad sample answers at /shown: `AdPlayback` emits `Established` on /shown (CONNECTING → ACTIVE),
  the beacons and /viewed are progress in ACTIVE, every end after /shown is a `ServiceEnd` (settled, never released); `AdFlow`:
  delivered = shown (set by the answer), the seconds watched from the progress, a task completes only when viewed. Expectations
  changed (before → after, the report lists them); red first (4 of 15), 4 mutants red; samples 73 green.
- 2026-10-24 01:25 · **item 11 done** (53bd001) — `seed-sessionflow/README.md` rewritten (the contract table: forced ■ / final / default, one line
  each; the one-package recipe; the subclass and the builder; naming; the bootstrap and live tenants; the prepaid piece), the class javadoc
  of `RoutesphereFlow` follows (two ways to write a lane, one package, the kit), the root README (seed-naming, the framework). Docs only;
  the drift guard green. Next: the whole reactor in the worktree, then the clean copy of the head.
- 2026-10-24 01:45–01:49 · the final runs of **53bd001** (the run script's own `date` lines): the whole reactor in the worktree from clean
  **501/0/0/3** (130 s), a git-archive clean copy of 53bd001 (offline, under the lock, no install) **501/0/0/3** (124 s); the copy removed.
- 2026-10-24 02:22 · resumed after the session limit (the owner switched accounts). The final runs had ended before the limit (their logs
  in the scratchpad: `final-worktree.log`, `final-copy.log`, both EXIT 0). Times corrected (above). Running: the report committed, then a
  clean copy of that head (its diff to 53bd001 is docs only).
- 2026-10-24 02:50 · **item 12 done** (the architect's, added at 02:2x) — module **`seed-bootstrap-config`**: a SmallRye `ConfigSourceFactory`
  (ServiceLoader) that reads `prime-context.bootstrap-url` / `prime-context.token-env` from the lower sources, fetches the envelope through
  item 6's road (`BootstrapSources.http`), serves `config` flattened (`a.b.c`, `a.b[0]`, a dotted key quoted) at ordinal 275; never a
  secret; no url = no source; unreachable / refused = the start refused as the kit's door does. SmallRye 3.13.4 provided (Quarkus 3.26.1's),
  smallrye-common pinned 2.13.8 (2.13.7 is not on this box). New sample `bootstrap/deployment-with-app-keys.json` beside the pinned §C files
  (unchanged). Red first (compile), 8 mutants red; module 8/0 (direct run). Decision: the reactor + clean copy run once after item 13
  (covering 12 and 13), the lock being shared by five agents tonight. Next: item 13 (a new reseller tier on the switch ledger) + the
  road's ledger close (found after the final run).
- 2026-10-24 02:58 · **item 13 done** (the architect's answer to Q1) — `LedgerPort.tierAdded(tierDb)` (a default that does nothing:
  orchestrix and the fakes unchanged); the road, on `tenant_created` kind reseller, tells THAT root's ledger first, then reloads the tree;
  seed-switch-ledger's `OwnedMemLedgerPort` loads ONE tier schema once, with no money move of the root meanwhile (a read/write gate: verbs
  shared, the load alone), through `AddTierLive` = mem-ledger's `addDatabase(schema)` (fba1346), found at run time because the pinned jar
  (built 2026-10-07) predates it — said in words when absent. Decision: not `reloadDatabases()` — MySQL's `SHOW DATABASES` only, every new
  database under the prefix. With it the road's `close()` closes the ledgers it made (found after the final run): a closed engine leaves
  no MemLedger thread (end to end on H2). Red first (compile), 11 mutants (10 red, 1 equivalent: the check before the gate only spares the
  lock); sessionflow 193, samples 73, switch-ledger 61, tenant 19, naming 10, bootstrap-config 8 (direct runs). Next: the whole reactor
  and a clean copy of the head under the lock, then the report.
- 2026-10-24 03:24–03:28 · the final runs of **a0cf3a8** (items 0–13; the run script's own `date` lines): the whole reactor in the worktree
  from clean **518/0/0/3** (127 s), a git-archive clean copy (offline, under the lock, no install) **518/0/0/3** (124 s); the copy removed;
  the MemLedger stack trace of 53bd001's logs gone.
- 2026-10-24 03:30 · **DONE** — items 0–13 committed (CONTRACT DONE c992262; the head a0cf3a8; this docs commit after it); the report
  `docs/agents/P1-E-report.md` final. The worktree's target directories and this agent's scratch files removed. Nothing running. Next for
  the architect: merge; P2 ports ad-sphere on it (the report's "For P2" lines); the open points of the report (the envelope's findings for
  the prime-context round, mem-ledger's `addDatabase` pin).

