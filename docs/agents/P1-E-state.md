# P1-E · stream F — state

**CONTRACT DONE** (items 0–5, 2026-10-24 01:05) — see the line of that time below.

One line per item, newest last. Times are Dhaka (+06).

- 2026-10-23 23:05 · START — worktree `p1-rules` at f41eace (stream D's four commits + the brief); the reactor there **465/0/0/3** (offline,
  under the lock, 2 min 18 s). Order: items 0 → 11. Running: item 0.
- 2026-10-23 23:35 · **item 0 done** — the records road removed (R13): 25 main files, the `records/` tests, `AdRecordsTest`, three sample
  scene helpers, the P1-R brief and state; `cdrStatements` no longer forced; the CDR sink required again. The reactor from clean
  **434/0/0/3** (31 tests deleted, named in the report). Next: item 1 (`identifyTenant` forced).
- 2026-10-23 23:55 · **item 1 done** (4a8b22a) — `identifyTenant(RoutesphereFacts)` abstract, the five door helpers (`api/Lookups`, `kit.lookups()`),
  the facts-class refusal noted; red first (the class not named), 4 mutants red; modules green (direct runs: sessionflow 151, samples 69,
  switch-ledger 50). The clean-copy reactor of 4a8b22a runs in the background (the lock is shared with two other agents).
- 2026-10-24 00:12 · **item 2 done** — `identifyPartner(ctx, candidate)` (was identifyEntryPartner), `partnerAbove(tier)` (was
  identifyPartner(tier)), the helpers `byPartnerId` / `byPartnerName` (`RootTree.entryNamed`, a name two partners share names nobody); red
  first (the helpers missing), 4 mutants red; sessionflow 155, samples 69, switch-ledger 50 green. Next: item 3 (the facts' sample at build).
- 2026-10-24 00:30 · **item 3 done** — the engine refuses at start a signaling machine that shows no `SessionStart` sample of RoutesphereFacts
  (in words, both cases); LabCall + the four sample specs show theirs; red first (FactsSampleAtBuildTest 3/3, SpecSamplesTest 1/1), 3
  mutants red; sessionflow 158, samples 70, switch-ledger 50 green (one timing flake of VoiceFlowTest's budget-child test in a direct run
  under load, 3 reruns green). Item 1's clean copy (4a8b22a): **438/0/0/3**. Next: item 4 (one supervisor class per lane).
- 2026-10-24 00:45 · **item 4 done** — `api/RoutesphereSupervisor<C>` (thin: the flow, the contexts, name() = the flow's, spec()); the ONE
  door `RoutesphereEngine.of(supervisor)` (`of(flow, contexts)` gone); `AdSupervisor`, `CallSupervisor`, `SmsSupervisor`, `WifiSupervisor`,
  `LabSupervisor`; `FlowBed` takes the lane's class (`c -> new AdSupervisor(flow, c)`); red first (the classes missing), 2 mutants red;
  sessionflow 158, samples 71, switch-ledger 50 green. Next: item 5 (the builder) → CONTRACT DONE.
- 2026-10-24 01:05 · **item 5 done** — `api/RoutesphereApp` (the builder: `builder(name)`, the seven forced answers + `serviceGroup` REQUIRED
  and refused in words, 15 optional hooks, sealed once built) + `api/AppFlow` (the flow it builds); the SMS sample rewritten with the
  builder (`SmsFlow.of(kit, …)`), `SmsFlowTest` / `SmsSubmitTest` green with one line of `SmsFlowTest` changed (the flow's type); red first
  (compile), 5 mutants red; sessionflow 163, samples 71, switch-ledger 50 green.

CONTRACT DONE — items 0–5 (the records road gone; identifyTenant / identifyPartner forced with helpers; the facts' sample at build; one
supervisor class per lane; the builder). The architect may merge up to item 5's commit and start the ad-sphere port on it. Running next:
item 6 (the kit from ONE bootstrap url).
- 2026-10-24 01:15 · the contract head **c992262** on a git-archive clean copy (offline, under the lock): **452/0/0/3**.
- 2026-10-24 01:40 · **item 6 done** — `RoutesphereKit.fromBootstrap(source, app[, makers])`: §C's envelope → `api/Deployment` (roots,
  settings, zone, rules, each root's `RootResources`, the platform Kafka), refusals in words; per root its tree (`spi/TreesMaker` →
  seed-tenant's `PrimeContextTrees`), ledger (`spi/LedgerMaker`: `switch` → seed-switch-ledger's `SwitchLedgerMaker`, `orchestrix` built in),
  CDR road (`cdr_<root>` on its own Kafka), journal (its own directory); `spi/BootstrapSource` (`BootstrapSources.http` with the token by
  its variable and If-None-Match/304; `testkit/MapBootstrapSource`); `testkit/FakeKafka`; the §C samples at
  `seed-sessionflow/src/test/resources/bootstrap/`. Red first (compile), 9 mutants red; sessionflow 177, samples 71, switch-ledger 54,
  tenant 19 green. Next: item 7 (naming + topic auto-creation).
- 2026-10-24 02:05 · **item 7 done** — module **`seed-naming`** (`TenantNames` = prime-context's RootNaming rule, copied with citation, the
  seed root by the bootstrap's word `naming.seed-root`; `Topics.platform` / `Topics.app` with Kafka's rules; `RedisKeys.of`; `Direction`);
  `dependencies/TopicProvisioner.ensure` (Admin.createTopics, idempotent, a refusing cluster said once); the engine's start makes every
  served root's `cdr_<root>`, `config_event_loader_<root>` and the lane's `topicsOf(root)` (a new hook, base + builder);
  `Deployment.tenantNames()`. Red first (naming 10/10 on stubs, the rest by compile), 12 mutants red (2 survivors made red by two stronger
  assertions); naming 10, sessionflow 183, samples 71, switch-ledger 54, tenant 19. Next: item 8 (tenant_created live).
- 2026-10-24 02:30 · **item 8 done** — `api/TenantCreated` (§C.2 read), `internal/TenantCreatedListener` (subscribes `tenant_created` on the
  platform Kafka, its own group; a bad message said, the next read), the road's `onTenantCreated`: a new ROOT this app serves → the
  bootstrap read again, the root's tree / doors / ledger / CDR road / journal / topics made, served live; a reseller TIER → its root's tree
  reloaded; the same event twice = once; another app's = nothing; a root the bootstrap does not serve yet = WARN, heard again later.
  Red first (compile), 6 mutants red; sessionflow 190, samples 71, switch-ledger 54, tenant 19. Next: item 9 (the prepaid piece).
- 2026-10-24 02:45 · **item 9 done** — no new money code: the README's "prepaid piece" (reserve / finalize, cost → refund → adjustment, the
  SMS = the refund rule, the default ledger `switch` from the bootstrap — pinned in item 6); `PrepaidPieceTest` (an answered call pays
  rate × units, the rest back) and `SmsPrepaidPieceTest` (a failed SMS returns its whole reserve, two ways) — green on the existing code (no
  red to see: the item adds no behaviour), 2 mutants red. Next: item 10 (the ad answers at /shown).
