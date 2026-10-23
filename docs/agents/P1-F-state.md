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
- 2026-10-24 05:25 · **item 2 done** — `RoutesphereKit.toBuilder()`: a builder holding every part of the kit, so a kit made from the
  bootstrap takes the lane's own sinks before it is used (`fromBootstrap(source, app, makers).toBuilder().sdr(..).tasks(..).build()`);
  the trees, doors, ledger, CDR road, journal, clock, zone, settings and the road itself kept (the engine on the new kit starts the road
  and its close closes it); refused in words once an engine runs on the kit (`attachSessions` marks it). Red first (compile: no
  toBuilder); LaneSinksOnTheBootstrapTest 4/4 (every part the same object, a session's SDR in the lane's sink with its CDR on cdr_btcl
  and the engine's close closing the road's producer and listener, a kit by hand carried whole, a kit in use refused); 10 mutants, 10 red;
  seed-sessionflow 197/0/0/0 direct (193 + 4). Decision: ONE door (toBuilder) rather than fromBootstrap overloads with two sinks — it also
  takes P2's local CDR journal beside the root's road (P2's kitOnTheBootstrap in one chain). Next: item 3.
- 2026-10-24 05:33 · **item 3 done** — `FanOutCdrSink` (`CdrSinks.all`) overrides `publish(root, callId, tiers)` and hands the session's
  root to every sink (a root-less call stays root-less; one sink's failure still never stops the next, on both roads). Red first:
  CdrFanOutTest 3/3 red — among them a per-root Kafka sink behind the fan-out published NOTHING before (topic per root, no root given);
  green after; 5 mutants, 5 red; seed-sessionflow 200/0/0/0 direct. (One mutant run was cut by a shell timeout: the source was checked
  intact, the module recompiled, the five run again from the start.) Next: item 4.
