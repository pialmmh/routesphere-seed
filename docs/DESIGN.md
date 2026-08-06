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

## The decision: plain libraries, NOT Quarkus extensions (for now)

Considered and rejected for this stage. Reasons:

1. **Our services are not all Quarkus.** The wifi session runner is a plain-JVM
   shaded jar (and its box future is native-image). A Quarkus extension locks
   the seed to one host framework; a plain library serves every host.
2. **Extensions buy build-time processing we don't need yet** (config at build
   time, native-image hints, dev-mode integration). None of that blocks
   wifi-sphere today.
3. **Cost:** an extension = deployment module + runtime module + build steps
   per lib. That tax is worth paying once native-image builds of Quarkus
   services become real — the library API stays the same, so extensions can be
   ADDED later without breaking consumers.

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
- [ ] Quarkus extension wrappers — only when native-image demands them
