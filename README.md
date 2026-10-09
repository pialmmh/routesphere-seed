# routesphere-seed

The Telcobright service seed: the proven parts of routesphere — per-tenant/
per-channel **config**, cached DB entities (**chronicle-db-cache** / "neymar
cache"), **omniqueue**, **statewalk-v2** — packaged so a new product starts
small and stays small. First consumer: **wifi-sphere**.

- `seed-bom` — import this, pick à la carte.
- `seed-config` — the routesphere tenant/profile/channel config convention as a
  dependency-light library (snakeyaml + slf4j only; Quarkus or plain JVM).
- `seed-config-client` — the config doorbell (Kafka + Redis notifications → one debounced re-fetch).
- `seed-routing` — the common REQUEST ROUTING of every switch (call, SMS, payment, later ad): dialplan by
  default, or a named ROUTING POLICY — a DB entity with one JSON document — picked in config. See
  `seed-routing/README.md`.

- `seed-sessionflow` — the BASE CALL PROCESSING PIPELINE of every switch: one base class (`SessionFlow`) runs a voice call,
  an SMS and an ad view through the same multi-tenant flow — a pooled machine, identify the partner, the tenant chain
  (authorize, rate, reserve), route, signal, settle, one CDR message per call. An application overrides only its own
  steps. See `seed-sessionflow/README.md`.

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
