# routesphere-seed — the good parts of routesphere, as a seed for new products

**Ratified by the user 2026-08-07.** Routesphere grew big; new products must
start small and stay small. This repo is the framework the next products grow
from — first consumer: **wifi-sphere**.

## What the seed contains

| Pillar | Artifact | State |
|---|---|---|
| Per-tenant / per-profile / per-channel **config system** | `seed-config` (this repo) | ✅ built + 5 tests — a clean reimplementation of the routesphere convention |
| **Cached DB entities** ("neymar cache": in-memory + chronicle-queue WAL + gRPC API) | `chronicle-db-cache` via `seed-bom` | ⚠️ binary-only — jar in ~/.m2 (2025-09-01), SOURCE NOT ON THIS MACHINE. Locate + repo-home before new work depends on it |
| **omniqueue** | `omni-queue` via `seed-bom` | existing artifact, re-exported |
| Session **state machines** | `statewalk-v2` via `seed-bom` | existing; wifi machines already ride it |
| mem-ledger (predecessor cache, still used) | `mem-ledger` via `seed-bom` | existing |

## The hosting model (ratified 2026-08-07): ONE Quarkus instance per product

A product — wifi-sphere on a server, or the shipped box — is **one Quarkus
build**: a single instance hosting the pillars as **CDI beans** (the session
registry bean, the bridge bean, the sweeper, the cache, the queue), exactly the
`EslCallRegistry` / `SmsV2Registry` pattern routesphere already uses. Shipping
to a box = building that same project (JVM or Quarkus native), not composing
separate daemons. Standalone runners (like the wifi pilot's shaded jar) are
dev/pilot conveniences, not the product form.

## The decision: plain libraries, NOT Quarkus extensions (for now)

The single-Quarkus-host model makes this cleaner, not weaker:

1. **A plain library slots into the one Quarkus host as a bean** with a 5-line
   producer — no extension machinery needed for that.
2. **Extensions buy build-time processing we don't need yet** (build-time
   config, native-image hints, dev-mode). The day the box uses a Quarkus
   NATIVE build, extension wrappers earn their keep — and can be ADDED then
   without changing the library APIs.
3. **Cost:** an extension = deployment + runtime module + build steps per lib;
   pay it when native demands it, not before.

A Quarkus host wires seed-config with a 5-line producer:

```java
@ApplicationScoped
public class SeedConfigProducer {
    @Produces @Singleton
    TenantConfigRegistry registry() { return TenantConfigRegistry.load(); }
}
```

## The config convention (seed-config)

Identical to routesphere's — same files work in both worlds:

```
application.properties        tenants[0].name / .enabled / .profile · active.tenant
                              (system properties -D… always win)
config/tenants/<t>/<p>/
  profile-<p>.yml             the entry point   → registry.active().get("wifi.session.maxHours")
  channels/<concept>/<i>.yml  per-protocol instances → active().channel("omniqueue","omniqueue-main")
  <any-other>/<i>.yml         e.g. pipelines/   → active().section("pipelines","sms-pipelines")
```

Plus one seed addition routesphere lacks: an **external config dir**
(`-Dseed.config.dir=/etc/wifi-sphere` or `SEED_CONFIG_DIR`) that overrides the
classpath file-by-file — how a deployed service gets its box config without
rebuilding the jar. Deploy-side stays the rs-config-deploy convention
(tenant-conf-v2 INI + remote-deploy).

Reload: the registry is immutable; a change = a new `load()`. The
config-manager doorbell (Redis/Kafka `config_event_loader`) triggers that
reload — the client for it is the seed's next planned module
(`seed-config-client`, extraction of routesphere-core's `ConfigEventConsumer`).

## The rules that keep products small

1. **Dependency direction is law:** seed libs never import a product; products
   never import each other's jars. Products integrate via contracts only
   (Redis/Kafka streams, gRPC, REST).
2. **A product imports `seed-bom` and picks à la carte** — wifi-sphere takes
   config + cache + omniqueue; it never inherits ESL/FreeSWITCH/call machinery.
3. **Promote, don't copy:** anything two products need moves INTO the seed.
   That rule is what stops the next routesphere-core from growing.
4. Products keep the code-master package shape (api/spi/publishes/…); the
   VARIANT-PACKAGE rule governs multi-impl seams.

## Roadmap

- [x] seed-bom + seed-config (2026-08-07)
- [ ] wifi-sphere scaffold (user creates; seed-config + statewalk-v2-wifi move in)
- [ ] seed-config-client — config-manager doorbell client (reload trigger)
- [ ] chronicle-db-cache source located + repo-homed under the seed umbrella
- [ ] Quarkus extension wrappers — only when the box's Quarkus NATIVE build demands them
