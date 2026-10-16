# ARCH-0087 item 2 — the consumers against the seed build at statewalk 3.3.0

The two consumers of seed-sessionflow, each from a READ-ONLY `git archive` of its `origin/main` (never a working tree), each run offline
twice with the SAME tails per consumer: BEFORE (the private repository `~/.m2/arch-bed-repo` serves seed-sessionflow 1.0-SNAPSHOT at
statewalk 3.2.0) and AFTER (`~/.m2/seed-sw33-repo`, this branch's reactor installed at commit adc3ce0, chained FIRST). The resolved
statewalk is proven by the offline `dependency:build-classpath` of each run (the jar's path), not by inference.

| consumer | archive | before: resolved | before: suite | after: resolved | after: suite |
|---|---|---|---|---|---|
| routesphere `callflow-v3-voice` | origin/main 11d81b2d | `~/.m2/arch-bed-repo/…/seed-sessionflow-1.0-SNAPSHOT.jar` (3.2.0 pom) + `~/.m2/arch-bed-repo/…/statewalk-3.2.0.jar` | **99 / 0 / 0 / 0** (17:33:08–17:34:19) | `~/.m2/seed-sw33-repo/…/seed-sessionflow-1.0-SNAPSHOT.jar` (3.3.0 pom) + `~/.m2/repository/…/statewalk-3.3.0.jar` | **99 / 0 / 0 / 0** (17:43:38–17:44:33) |
| ad-sphere | origin/main 3f9cb73 | `~/.m2/arch-bed-repo/…/seed-sessionflow-1.0-SNAPSHOT.jar` (3.2.0 pom) + `~/.m2/arch-bed-repo/…/statewalk-3.2.0.jar` | **585 / 0 / 0 / 17** = default-test 574/0/0/17 + jvm-in-utc 4 + pc-listens 2 + no-ffmpeg 5 (17:42:43–17:48:13) | `~/.m2/seed-sw33-repo/…/seed-sessionflow-1.0-SNAPSHOT.jar` (3.3.0 pom) + **`~/.m2/arch-bed-repo/…/statewalk-3.2.0.jar`** — see the finding | **585 / 0 / 0 / 17** = 574/0/0/17 + 4 + 2 + 5, identical per class (17:48:36–17:53:37) |

New red after the pin: **none** (the STOP condition never fired).

## The finding on ad-sphere: its statewalk stays 3.2.0 until P2 lifts it in ad-sphere's OWN resolution

`dependency:tree -Dverbose -Dincludes=com.telcobright:statewalk` on the AFTER repository:

```
com.telcobright:ad-sphere:jar:1.0.0
+- com.telcobright:sphere-kit:jar:1.0.3:compile
|  \- com.telcobright:statewalk:jar:3.2.0:compile
\- com.telcobright:seed-sessionflow:jar:1.0-SNAPSHOT:compile
   \- (com.telcobright:statewalk:jar:3.3.0:compile - omitted for conflict with 3.2.0)
```

`sphere-kit 1.0.3` (ad-sphere `pom.xml:37`, declared before `seed-sessionflow` at `:43`) carries `<statewalk.version>3.2.0</statewalk.version>`
(`sphere-kit-1.0.3.pom:19`); both paths are depth 2, so Maven keeps the first declaration. The AFTER suite therefore ran seed-sessionflow's 3.3.0-compiled
classes on the 3.2.0 jar — green because 3.3.0's API is additive and seed references none of its new symbols — but **the ad lane does not receive the
SW-T1 fixes from seed's pin alone.** P2 must lift statewalk in ad-sphere's own resolution too: a sphere-kit at 3.3.0 (orchestrix-v2's kit, and
`cloud-base.sh`'s `KIT=` pin), or ad-sphere declaring statewalk 3.3.0 itself (a direct dependency or `dependencyManagement` — nearest wins).

**Diagnostics for P2.** `-Dstatewalk.version=3.3.0` on the command line changes nothing (Maven does not apply the session's properties to a
dependency's pom — tried, the tree stayed at 3.2.0, the run stopped). The **DIAG copy** (`~/seed33-work/consumers/ad-sphere-DIAG/`: the archive with ONE added
`dependencyManagement` line, statewalk 3.3.0 — the P2-shaped change, scratch-only, never committed; head `~/.m2/seed33-ad-diag-repo`, the AFTER tails;
`logs/item2-adsphere-DIAG-dm330.log`): **585 / 0 / 0 / 17** = 574/0/0/17 + 4 + 2 + 5 (17:58:09–18:04:11), the 129 classes **identical per class** to BEFORE and to the natural AFTER, with `~/.m2/repository/…/statewalk-3.3.0.jar` on the classpath (tree: sphere-kit's statewalk `version managed from 3.2.0` → 3.3.0; `unzip -l … | grep PersistenceMode` = 1). So ad-sphere `main` is green on the real 3.3.0 — P2 can move the kit/pin without fear of its Java suite.
