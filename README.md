# routesphere-seed

The Telcobright service seed: the proven parts of routesphere — per-tenant/
per-channel **config**, cached DB entities (**chronicle-db-cache** / "neymar
cache"), **omniqueue**, **statewalk-v2** — packaged so a new product starts
small and stays small. First consumer: **wifi-sphere**.

- `seed-bom` — import this, pick à la carte.
- `seed-config` — the routesphere tenant/profile/channel config convention as a
  dependency-light library (snakeyaml + slf4j only; Quarkus or plain JVM).

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
