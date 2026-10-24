# P1-E — the framework round: the extension contract, the bootstrap kit, naming, the tenant-created handler (READY)

Written 2026-10-23 ~22:30 +06 by the architect. The owner's rulings of the same evening are the requirements:
routesphere `docs/architecture/routesphere-seed-flow-requirements.md` (R1–R13; Trilium *Routesphere → routesphere-seed-flow*).
Read that page FIRST — every item below cites its R-number.

## 0 · Where things are

- routesphere-seed branch **`p1-rules`** b68afda (worktree `worktrees/p1-rules`), from `routesphere-flow` 35c955f (statewalk **4.1.0**,
  the records road P1-R merged). Stream D's four commits are on it: `P1-D 6` (sdrStatements javadoc), `P1-D 5` (the Kafka leftovers carry
  their root — KEEP), `P1-D 1` (`delivered`, `refundPolicy`, `rerouteActionFor`, `cdrStatements` abstract + `RoutesphereFlowDriftTest`;
  the samples answer them). Reactor 462/0/0/3. You continue on this branch; commits `P1-E <n>: …`.
- The base: `seed-sessionflow/src/main/java/com/telcobright/seed/sessionflow/api/RoutesphereFlow.java` (the class javadoc's table),
  `RoutesphereEngine.java`, `RoutesphereFacts.java`, `dependencies/RoutesphereKit.java`, `api/SessionCdr.java`. Six subclasses: the samples
  `AdFlow` `VoiceFlow` `SmsFlow` `WifiFlow` (`seed-sessionflow-samples`), `LabFlow` (base tests), `LedgerFlow` (`seed-switch-ledger`).
  Signaling specs with a `SessionStart`: `AdPlayback`, `EslCall`, `SmsSubmit`, `WifiGateway`, `LabCall`.
- prime-context's bootstrap today: `GET /api/v1/bootstrap/{tenant}/{app}` (token-gated JSON envelope: `tenant`, `app`, `env`,
  `config` (merged map), `configYaml`, `secrets`, `etag`; `/config.yml`, `/config.json`, `/secrets.env`), javadoc in
  `prime-context/src/main/java/com/telcobright/primecontext/facade/api/BootstrapResource.java`. The identification rules are already
  contributed into every bootstrap (`IdentificationContributor`); the seed reads them with `IdentificationRules.fromBootstrap(map)`.
  The reseller naming rule: `prime-context …/config/dynamic/core/RootNaming.java` (`<root>_res_<id>`, nested `_<id>`; legacy `res_<id>`
  for the seed root). A prime-context agent builds the server side of items 6–8 in parallel (brief PC-0022); the contract between you is
  §C's envelope sample — pinned byte-for-byte in both repositories.

## A · Items, in this order

**Item 0 — the records road leaves (R13).** The owner corrected the ruling: a switch never writes a CDR table; it publishes to Kafka. Remove
P1-R whole: `internal/{RecordRoad, RecordWriter, RecordBatch, RecordParking, BatchLock, IdBook, PingRoad, StatementRunner, CdrTableSql,
CdrColumns, SummaryBlob, SummaryEntries, SessionRecords}`, `api/{RecordStatement, RecordId}`, `dependencies/{RecordStores, RecordStore,
RecordDialect, SummaryPings}`, `spi/SummaryPing`, `testkit/{H2Locks, RecordTables}`, the hooks `cdrStatements` / `sdrStatements` /
`summaryEntryOf`, `writeRecords` / `writeRecordsOf` / `storeOf`, `kit.records()` / `recordStores()`, the records branches of `SessionCdr`
and `RoutesphereEngine.close`, the tests under `seed-sessionflow/src/test/…/records/`, the sample's `AdRecordsTest` + `AdFlow.sdrStatements`,
the README's records section, `P1-R-*.md` docs (keep `P1-R-report.md` as history, add one line at its top: superseded, R13). The CDR sink
becomes required again at build ("the kit needs a CDR sink"). `RoutesphereFlowDriftTest`'s abstract set loses `cdrStatements`. Keep
`P1-D 5`. Reactor green; name every test you deleted and why.

**Item 1 — `identifyRootLevelTenant` forced, with helpers (R1, R2).** `identifySiblingLevelTenants(SessionFacts)` becomes
`protected abstract String identifyRootLevelTenant(RoutesphereFacts facts)`. `onStart`: facts that are not `RoutesphereFacts` → refused `NO_TENANT`
with a history line naming the class; ≤ ~10 lines of named steps. Helpers (final, in the base, one line each, over
`kit.resolver().tenantOf(Ingress)`): `byListener(ip, port)`, `byEslSocket(host, port)`, `byVlan(gateway, svlan)`, `byTopic(name)`,
`byIngress(Ingress)`. The lanes: AdFlow `byListener`, VoiceFlow `byEslSocket`, WifiFlow `byVlan`, SmsFlow `byListener`, LabFlow/LedgerFlow
`byIngress(facts.ingress())`. Red first: a lane that returns null is refused NO_TENANT; a facts class without `RoutesphereFacts` is named.

**Item 2 — `identifyPartner` (R2).** `identifyEntryPartner(ctx, candidate)` → **`identifyPartner(ctx, candidate)`** (the owner's name; the
candidate stays — the ad's payer depends on it). The above-the-leaf default `identifyPartner(Tier)` → `partnerAbove(Tier)` (the `res_<id>`
rule unchanged). Helpers (final): `byPartnerId(ctx, id)` (= today's `entryOfPartner`), `byPartnerName(ctx, name)`. What the tree does NOT
offer today (a partner by caller ip / number prefix for voice, by subscriber for WiFi) is NOT invented here: write the gap in the report as
a finding for the prime-context round (the tree would carry the index), the lanes keep their own maps as today.

**Item 3 — the facts' sample refused at build (R2).** As P1-D §2: `RoutesphereEngine…start()` refuses a signaling spec that shows no
`SessionStart` up-sample whose facts are `RoutesphereFacts` (statewalk 4.1 `MachineSpec.Builder.upSample` / `upSamples()`), in words. Every
spec in the seed adds its sample. Red first.

**Item 4 — one supervisor class per lane (R6).** As P1-D §3: `api/RoutesphereSupervisor<C>` (abstract, thin: the flow, the contexts,
`name()` = `flow.name()`, `spec()`); `RoutesphereEngine.of(RoutesphereSupervisor<C>)` the ONE door (`of(flow, contexts)` goes);
`AdSupervisor`, `CallSupervisor`, `SmsSupervisor`, `WifiSupervisor`, `LabSupervisor`; `FlowBed` takes the supervisor; a test pins each
sample's class name = its flow's name.

**Item 5 — the builder (R5).** `api/RoutesphereApp` (or `RoutesphereFlow.builder`): `RoutesphereApp.<C>builder(String name)`
`.serviceGroup(int)` `.identifyRootLevelTenant(Function<RoutesphereFacts,String>)` `.identifyPartner(BiFunction<C,Candidate,Tier>)`
`.rateAtLevel(…)` `.route(…)` `.delivered(…)` `.refundPolicy(…)` `.rerouteActionFor(…)` — the seven forced answers as lambdas, each
REQUIRED (`build()` refuses a missing one in words) — plus the optional hooks as lambdas (`buildTask`, `selectCandidates`,
`confirmAdmission`, `billedUnits`, `rateNextWindow`, `onAnswered`, `onServiceEnded`, `fillCdr`, `buildSdr`, …). `build(kit)` answers a
`RoutesphereFlow<C>` (an anonymous subclass); the helpers of items 1–2 are reachable from the lambdas through a `Helpers` argument or the
flow handed in. The lambdas run under the base's `safely`/`guarded` like the overrides. **Proof:** rewrite the sample `SmsFlow` with the
builder and keep `SmsFlowTest` / `SmsSubmitTest` green unchanged (the lane as a builder); AdFlow/VoiceFlow/WifiFlow stay subclasses.

**Item 6 — the kit from ONE bootstrap url (R8).** `RoutesphereKit.fromBootstrap(BootstrapSource source, String app)`: the app's yml
holds `prime-context.bootstrap-url` (+ the token's environment variable NAME, never a value) and nothing else. The kit reads the
deployment document (§C), then for every served root its resources: the identification rules (`IdentificationRules.fromBootstrap`), the
root's tree (`seed-tenant`'s prime-context source), the Kafka CDR sink (`cdr_<root>`), the ledger (`switch` = `seed-switch-ledger`'s
MemLedger port; `orchestrix` = the REST roads, for the transition), the journal, Redis, pool/threads/timings. `BootstrapSource` is an spi
(`HttpBootstrapSource` with `If-None-Match`/304, token from the environment; a `MapBootstrapSource` for tests). Refusals in words:
no roots, a root without its resources, a rule for a root not served. The §C sample is a test resource, read by a test that builds the
whole kit from it and names every resource it got. The sample lanes' tests keep building their kits by hand (both doors stay).

**Item 7 — naming and auto-creation (R9).** A small module `seed-naming` (or package `seed-tenant …/naming`): `TenantNames.tierDb(root,
parentDb, partnerId)` = prime-context's `RootNaming` rule (copied with citation, incl. the legacy `res_<id>` for the seed root — the root's
name of the legacy rule comes from the bootstrap, never hard-coded), `TenantNames.parentOf(root, tierDb)`; `Topics.platform(name, root)` =
`<name>_<root>`; `Topics.app(app, Direction, name, root)` = `<app>_<in|out>_<name>_<root>`; `RedisKeys.of(root, app, name)` =
`<root>:<app>:<name>`; validation (Kafka's legal characters, ≤ 249, no `.`/`_` collision). `TopicProvisioner.ensure(bootstrap, topics,
partitions, replication)` through Kafka's `AdminClient.createTopics` (idempotent: `TopicExistsException` is fine; a cluster that refuses is
said once and the start goes on — the brokers may auto-create). Called by the engine at start for every served root's topics (the CDR topic,
the app's own topics the lane lists through a hook `topicsOf(root)`), and again when a root is added live (item 8). Tests with an embedded
or mocked admin client (no real broker on this PC).

**Item 8 — a tenant created live (R10).** `TenantCreatedListener`: subscribes the platform topic `tenant_created` (key = the root; value
§C.2) through the kit's Kafka; on a message naming a root this app serves (or a new reseller tier of one) → the kit loads the root's
bootstrap (item 6), its tree and rules, ensures its topics (item 7), and the engine serves it — no restart; a tier under a served root → the
tree reload (the doorbell already does it). Idempotent (the same event twice = once). Tests with a fake source + `MockConsumer`.

**Item 9 — the prepaid piece, documented and defaulted (R12).** No new money code: `reserveBalance` → `finalizeTier` already is the one
reserve/finalize. Make it visible: a README section "the prepaid piece" (the two apis, cost → refund → adjustment, the SMS case = the refund
rule), and the default ledger from the bootstrap = `switch` (seed-switch-ledger). A test pins: a failed SMS returns its whole reserve; an
answered call pays rate × units and returns the rest.

**Item 10 — the ad sample answers at `/shown`** (D's item 9, the owner's ruling): `AdPlayback`: `Established` on `AdShown`; `/viewed` a
progress; AdFlow's `delivered` = shown. List every changed test expectation (before → after) in the report.

**Item 11 — docs.** `seed-sessionflow/README.md`: the contract table (forced / final / default, one line each), the builder, the
one-package recipe (R6's six files), naming, the bootstrap, the prepaid piece. The class javadoc of `RoutesphereFlow` follows.

After item 5 write `CONTRACT DONE` in `docs/agents/P1-E-state.md` — the architect merges that much and starts the ad-sphere port on it.

## B · Rules

- EVERY mvn under `flock /home/mustafa/telcobright-projects/.one-maven.lock mvn -o -q …` (other agents build on this PC); offline; the normal
  `~/.m2` resolves everything (statewalk 4.1.0 is there); never install into `~/.m2` (a private repository only by
  `~/arch-drafts/tools/private-m2.sh`). Never push; no sudo/systemctl; never `pkill -f`; never cd into another agent's worktree; never
  127.0.0.1:3306 / 7433; no real Kafka or database on this PC for this round (fakes and mocks).
- Behaviour of the existing lanes must not change except where an item says so; name every test you touched and why.
- Red first, then green; mutants on every new guard (the forced set, the sample check, the builder's refusals, the naming rules, the
  provisioner's idempotence, the listener's idempotence), each seen red, restored with a fresh mtime (copyfile, not copy2) or `mvn clean`.
- Commit per item (`P1-E <n>: …`, the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`); `docs/agents/P1-E-state.md` after
  EVERY item; `docs/agents/P1-E-report.md` drafted early and kept true (per item: the commit, the red seen, the mutants, run/failed/errors/skipped).
- House style: top-level functions ≤ ~10 lines of named steps; one primary type per file; small files.
- The whole reactor in the worktree, then on a git-archive clean copy of your head (offline, no install).

## C · The contract with prime-context — the envelope (pinned in both repositories)

C.1 `GET /api/v1/bootstrap/{deployment-tenant}/{app}` — today's envelope plus `config.routesphere`:

```json
{
  "tenant": {"id": 1, "code": "platform", "name": "Telcobright platform"},
  "app":    {"id": 7, "code": "ad-sphere", "name": "ad-sphere"},
  "env": "bed",
  "config": {
    "routesphere": {
      "roots": ["btcl", "link3"],
      "pool": 2000, "threads": 8,
      "timings": {"initSec": 5, "admittingSec": 10, "routingSec": 5, "connectingSec": 90, "activeMaxSec": 14400},
      "identification": {"rules": [
        {"root": "btcl",  "kind": "listen", "match": "10.10.188.2:7070"},
        {"root": "link3", "kind": "listen", "match": "10.10.188.2:7071"}
      ]},
      "root": {
        "btcl": {
          "switch-db": {"url": "jdbc:postgresql://10.10.188.2:5434/routesphere", "schema": "btcl", "username": "ad_sphere", "password-ref": "env:AD_SWITCH_DB_PASSWORD"},
          "kafka":  {"bootstrap": "10.10.188.2:9092", "partitions": 3, "replication": 1},
          "redis":  {"url": "redis://10.10.188.2:6379"},
          "ledger": {"kind": "switch"},
          "journal": {"dir": "/var/lib/ad-sphere/btcl"}
        },
        "link3": {"switch-db": {"…": "…"}, "kafka": {"…": "…"}, "redis": {"…": "…"}, "ledger": {"kind": "switch"}, "journal": {"dir": "/var/lib/ad-sphere/link3"}}
      }
    }
  },
  "secrets": {"AD_SWITCH_DB_PASSWORD": "…"},
  "etag": "…"
}
```

C.2 The `tenant_created` message (platform topic, key = the root):

```json
{"root": "link3", "tierDb": "link3_res_5", "parentDb": "link3", "partnerId": 5, "kind": "reseller", "apps": ["ad-sphere", "wifi-sphere"], "at": "2026-10-23T16:30:00Z"}
```
(`kind`: `root` for a new operator — then `tierDb` = the root, `parentDb` null, `partnerId` null.)

The sample files live at `seed-sessionflow/src/test/resources/bootstrap/deployment.json` and `tenant-created.json`; the prime-context agent
pins the same bytes. A change to either = both agents through the architect.

## D · Out of scope

ad-sphere (P2 starts on your CONTRACT DONE), prime-context (its own round), statewalk, billing-core, summary-service. No push, no install.
