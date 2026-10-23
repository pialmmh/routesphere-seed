# seed-bootstrap-config — the application's whole config from prime-context (R8)

The owner's R8: an application's own yml holds ONE thing, the bootstrap url. `RoutesphereKit.fromBootstrap` (seed-sessionflow) reads the
framework's part of the envelope (`config.routesphere`); this module serves the REST of it — the application's own keys (`adsphere.*`,
`sdr.*`, `cdr.*`, `reports.*`, `routing.*`, `quarkus.http.*`, …) — to the application's MicroProfile / SmallRye Config.

## The application's yml — two lines, at RUN TIME only

```yaml
prime-context:
  bootstrap-url: http://10.10.188.2:7300/api/v1/bootstrap/platform/ad-sphere
  token-env: AD_SPHERE_BOOTSTRAP_TOKEN           # the NAME of the variable holding the token, never the token
```

**Quarkus runs this factory during its BUILD too.** `mvn package` reads the application's configuration to fix its build-time values,
and that reading discovers every `ConfigSourceFactory` on the classpath (Quarkus 3.26.1: `BuildTimeConfigurationReader.initConfiguration`
→ `ConfigUtils.configBuilder()` → `addDiscoveredSources()`; the ad-sphere port read it in the bytecode). A url the build can see makes the
build fetch prime-context: the build fails where the build box cannot reach it, and a build-time key the envelope carries would be fixed
into the jar.

The rule (the architect's, P1-F): the two lines live ONLY in a run-time location, where the build never looks — on the box:

1. **the box's external `config/application.yml`, beside the jar** — the two lines above, as the whole file. Quarkus reads
   `config/application.yml` from the process's WORKING directory at run time (SmallRye's YAML loader: `user.dir/config/application.yml`,
   ordinal 265), so the unit's `WorkingDirectory=` is the folder that holds the jar and its `config/`;
2. **or the unit's environment** — `Environment=PRIME_CONTEXT_BOOTSTRAP_URL=http://…` and
   `Environment=PRIME_CONTEXT_TOKEN_ENV=AD_SPHERE_BOOTSTRAP_TOKEN` (the environment source, ordinal 300; SmallRye maps
   `prime-context.bootstrap-url` to `PRIME_CONTEXT_BOOTSTRAP_URL`). The token itself comes from secreteer's `EnvironmentFile`, as every
   secret does.

**Never inside the jar** — the build reads every file in it: not in `src/main/resources/application.yml` or `.properties`, not in a
profile file packed into the jar. And never where the BUILD's own run-time sources look: the build — and every `@QuarkusTest` it runs —
is a JVM too, and reads its environment, a `.env` and a `config/application.properties` of ITS working directory (SmallRye 3.13.4's
default sources, which Quarkus's build reader adds) — so no `config/` folder and no `.env` in the project's directory, and no
`PRIME_CONTEXT_*` variable exported in the shell that builds.

### No build-time guard: there is no reliable, documented signal

A guard would need a documented word that tells a `ConfigSourceFactory` it runs inside Quarkus's build. None was found, so there is NO
guard — the rule above is the protection:

- SmallRye's `ConfigSourceContext` (3.13.4) gives `getValue`, `iterateNames`, `getProfiles` and `getConfigSources` — no phase. The profile
  is `prod` at the build and at the run of the same jar.
- Quarkus's `LaunchMode` (quarkus-core 3.26.1) names the mode, not the phase: `NORMAL` is "a normal production build", the same word when
  the jar runs. A plain SmallRye library does not see it without depending on quarkus-core.
- `ExecutionMode.UNSET` is not documented, and it is UNSET in any plain JVM too (this module's own tests).
- Anything else — which sources happen to be present, a property the Maven plugin happens to set — is not documented: a guess.

The documented way to keep a source out of the build is a Quarkus EXTENSION (a deployment module) that registers it for the run time
only (`RunTimeConfigBuilderBuildItem`, quarkus-core-deployment 3.26.1) — a recommendation for a later round, not code.

Add the dependency (a Quarkus application gets the source by `ServiceLoader`, nothing else to wire):

```xml
<dependency>
    <groupId>com.telcobright</groupId>
    <artifactId>seed-bootstrap-config</artifactId>
</dependency>
```

## What it does

`dependencies.BootstrapConfigSourceFactory` — a SmallRye `ConfigSourceFactory` (`META-INF/services/io.smallrye.config.ConfigSourceFactory`):

1. reads `prime-context.bootstrap-url` and `prime-context.token-env` from the lower sources (the jar's yml, the environment
   `PRIME_CONTEXT_BOOTSTRAP_URL`, a `-D`);
2. fetches the envelope through the SAME road as the kit (`BootstrapSources.http`: `Authorization: Bearer <the token read by its
   variable's name>`, `If-None-Match`);
3. serves the envelope's `config` flattened — a nested map `a.b.c`, a list `a.b[0]` (a map in a list `a.b[0].c`), a key holding a dot quoted
   (`quarkus.log.category."com.telcobright".level`) — as ONE config source at **ordinal 275**:

| source | ordinal | wins over the bootstrap? |
|---|---|---|
| `-D` system properties | 400 | yes |
| environment variables | 300 | yes — a documented override still wins |
| **the bootstrap (this module)** | **275** | — |
| the box's `config/application.yml` (the working directory) | 265 | no — it holds the two lines; an override goes in the environment or `-D` |
| the jar's `application.yml` / `application.properties` | 255 / 250 | no — the bootstrap wins |

## When prime-context does not answer

Exactly as the kit's door (`RoutesphereKit.fromBootstrap`): **the start is refused**, in the same words and with the same exception
(`BootstrapSource.BootstrapUnavailable`: "prime-context did not answer at <url>: …", "prime-context answered HTTP <code> for <url>"); a url
without its token's variable name, or an empty token variable, is refused naming it. There is no cached last-good copy (a recommendation for
a later round, not code). No `prime-context.bootstrap-url` at all = no source: the application runs on its own files (a test, a lab) — and
its kit's door, if it builds one from the bootstrap, still refuses the missing url.

## Never a secret

Only `config` is served. The envelope's `secrets` never become config values; a `password-ref: env:<NAME>` stays a reference, resolved
where it always was (the environment first, then the envelope's secrets — `RootResources.SwitchDb.password`).

## Tests

`BootstrapConfigSourceTest` builds SmallRye Config by hand over a map-backed bootstrap (no network): the app's keys above the jar, a nested
map and a list, the override order, never a secret, no url = no source, the refusal in words, the `ServiceLoader` registration.
`FlatConfigTest`: the names. The samples are seed-sessionflow's `src/test/resources/bootstrap/` (this module copies them as test
resources): the pinned §C files and `deployment-with-app-keys.json` (the §C envelope with an application's own keys).
