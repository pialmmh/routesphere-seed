# P1-F · stream F — state

The six seed gaps the ad-sphere port (P2) found (P2-state.md, "Gaps for the seed"), fixed ADDITIVELY: new doors, every existing
signature kept, no behaviour P2 relies on changed. Branch `p1-follow` from routesphere-flow 723f160.

One line per item, newest last. Times are Dhaka (+06), each read with `date` before it was written.

- 2026-10-24 05:11 · START — worktree `p1-follow` at 723f160; the reactor there from clean **518/0/0/3** (offline, under the lock,
  129 s, 05:07–05:09). Order: item 1 (the build-time rule, docs) → 2 (the lane's sinks on a bootstrap kit) → 3 (the fan-out keeps the root)
  → 4 (machinesBuilt, busy) → 5 (PoolStats.sessions) → 6 (FakeKafka on kafka-clients 3.7.1 and 4.0.0). Running: item 1.
- 2026-10-24 05:17 · **item 1 done** — docs only, NO guard (no reliable, documented signal: SmallRye's ConfigSourceContext has no phase,
  the profile is prod at the build and the run; Quarkus's LaunchMode NORMAL = "a normal production build", the same word at run time and
  not visible to a plain SmallRye library; ExecutionMode.UNSET undocumented and UNSET in any plain JVM; the documented road is a Quarkus
  extension's RunTimeConfigBuilderBuildItem — a recommendation, not code). The rule in seed-bootstrap-config/README.md (the build's reading
  path, the two run-time locations — the box's config/application.yml in the unit's working directory, ordinal 265, or the unit's
  environment PRIME_CONTEXT_BOOTSTRAP_URL / PRIME_CONTEXT_TOKEN_ENV — never in the jar, nor in the build's own config/, .env or shell) and
  in the sessionflow README's bootstrap section; one javadoc bullet on BootstrapConfigSourceFactory; the ordinal table gains the box's file
  (265) and the jar's yml at 255. seed-bootstrap-config 8/0/0/0 (direct). Next: item 2.
