# seed-bootstrap-config — the application's whole config from prime-context (R8)

The owner's R8: an application's own yml holds ONE thing, the bootstrap url. `RoutesphereKit.fromBootstrap` (seed-sessionflow) reads the
framework's part of the envelope (`config.routesphere`); this module serves the REST of it — the application's own keys (`adsphere.*`,
`sdr.*`, `cdr.*`, `reports.*`, `routing.*`, `quarkus.http.*`, …) — to the application's MicroProfile / SmallRye Config.

## The application's yml — two lines

```yaml
prime-context:
  bootstrap-url: http://10.10.188.2:7300/api/v1/bootstrap/platform/ad-sphere
  token-env: AD_SPHERE_BOOTSTRAP_TOKEN           # the NAME of the variable holding the token, never the token
```

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
| the jar's `application.properties` / yml | 250 | no — the bootstrap wins |

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
