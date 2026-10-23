# routesphere-seed

The Telcobright service seed: the proven parts of routesphere — per-tenant/
per-channel **config**, cached DB entities (**chronicle-db-cache** / "neymar
cache"), **omniqueue**, **statewalk** (4.0.0) — packaged so a new product starts
small and stays small. First consumer: **wifi-sphere**.

- `seed-bom` — import this, pick à la carte.
- `seed-config` — the routesphere tenant/profile/channel config convention as a
  dependency-light library (snakeyaml + slf4j only; Quarkus or plain JVM).
- `seed-config-client` — the config doorbell (Kafka + Redis notifications → one debounced re-fetch).
- `seed-routing` — the common REQUEST ROUTING of every switch (call, SMS, payment, later ad): dialplan by
  default, or a named ROUTING POLICY — a DB entity with one JSON document — picked in config. See
  `seed-routing/README.md`.

- `seed-naming` — the names every application resolves the same way (R9): a reseller tier's database (prime-context's RootNaming
  rule), the platform topics `<name>_<root>`, an application's own `<app>_<in|out>_<name>_<root>`, Redis keys `<root>:<app>:<name>`.
- `seed-bootstrap-config` — the application's WHOLE config from prime-context's bootstrap (R8): a SmallRye `ConfigSourceFactory` at
  ordinal 275 over the same road as the kit; the application's yml is two lines (the url and the token's variable name).
- `seed-sessionflow` — `RoutesphereFlow`, the ONE base flow — the FRAMEWORK every routesphere application extends (P1, P1-E): the
  handler of statewalk 4.1's final `SessionSupervisor` — the root by the lane's door (`identifyTenant`), the payer (`identifyPartner`),
  the reseller tiers leaf to root, routing, the prepaid piece through one ledger port, rerouting by cause, the finish (one CDR message per
  session, a record per tier, on `cdr_<root>`); a lane is a subclass or a `RoutesphereApp` builder with its own supervisor class; the kit
  from ONE bootstrap url (`RoutesphereKit.fromBootstrap`), roots added live. See `seed-sessionflow/README.md`.
- `seed-sessionflow-samples` — the acceptance side of the base, test code only (nothing ships): the four signaling machines as statewalk
  4.0 `MachineSpec`s with their fake channels and decoders (AdPlayback, EslCall, WifiGateway, SmsSubmit) and the four `RoutesphereFlow`
  samples (AdFlow, VoiceFlow, WifiFlow, SmsFlow) with the scenarios every sample runs — two roots, 1–3 tiers, credit, the finish, a crash.
  The model for P2–P5. Report: `docs/agents/P1-T-report.md`.
- `seed-switch-ledger` — the SWITCH LEDGER on the base: the base's `LedgerPort` over the switch's own MemLedger (package accounts, one
  reserve row per tier per session, settled exactly at the end), the call switch's account order, the one credit primitive, the orphan
  reaper, the call's C14 remainder (`WindowRenewal`); the bootstrap's default `switch` ledger (`SwitchLedgerMaker`, found on the
  classpath). The base never depends on it. See `seed-switch-ledger/README.md`.

Read `docs/DESIGN.md` for the decisions (why plain libs, not Quarkus
extensions), the rules (dependency direction, promote-don't-copy), and the
roadmap.

```bash
mvn clean install          # builds bom + config, runs the tests
```

Using it in a product:

```xml
<dependencyManagement><dependencies>
  <dependency>
    <groupId>com.telcobright</groupId><artifactId>seed-bom</artifactId>
    <version>1.0-SNAPSHOT</version><type>pom</type><scope>import</scope>
  </dependency>
</dependencies></dependencyManagement>
<dependencies>
  <dependency><groupId>com.telcobright</groupId><artifactId>seed-config</artifactId></dependency>
</dependencies>
```

```java
TenantConfigRegistry reg = TenantConfigRegistry.load();   // -Dseed.config.dir=/etc/wifi-sphere to override
TenantProfile t = reg.active();
int maxHours = (int) t.get("wifi.session.maxHours");
var queueCfg = t.channel("omniqueue", "omniqueue-main");
```
