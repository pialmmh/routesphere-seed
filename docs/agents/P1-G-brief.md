# P1-G — the seed: live tenants made safe (roots not served yet, tiers before trees, the parent's new rows), the naming table, the late config values (READY)

Written 2026-10-24 06:1x +06 by the architect, overnight (the owner asleep: "go with defendable defaults"). Continues stream F on the
SAME branch `p1-follow` (worktree `routesphere-seed/worktrees/p1-follow`), after P1-F's report commit — commits `P1-G <n>: …`, state
lines in `docs/agents/P1-F-state.md` under a `## P1-G` heading, a `P1-G` section in your report. Additive: new doors, every existing
signature kept; ad-sphere's port (P2) builds on 723f160 + P1-F and must not break.

Sources: prime-context PC-0022 (branch `framework-bootstrap` 8f8d922, READ ONLY — `docs/ad-as-call/PC-0022-done.md` §9 and §10,
`docs/facade/framework.md`) and ad-sphere P2's seed gaps 7–10 (`ad-sphere/worktrees/p2-adflow/docs/ad-as-call/P2-state.md`,
"Gaps for the seed", READ ONLY). Never cd into either worktree; read by absolute path.

## Items, in this order (stop at the cut-off wherever you are; each item stands alone)

1. **A new root that cannot be served yet is never half-served** (PC-0022 §9.3 / §10 #1). prime-context sends `tenant_created`
   (kind root) at the root's provisioning, BEFORE it serves the root: its tree road answers 404 `ROOT_NOT_SERVED` until tenant-master's
   switch row exists and prime-context's next build; and the deployment document may list the root only later (a PUT after the
   creation). Today `BootstrapRoad.serveTheNewRoot` → `LiveTenantTrees.serve` adds the root to `served` and then throws on the failed
   load: the root stays in `served` with no tree, the deployment/rules/roads not made, and nothing tries again; a not-listed root is
   forgotten for good. Required: a failed serve leaves NOTHING served (undo, and the `LiveTrees.serve` javadoc says so for a lane's own
   LiveTrees — ad-sphere's AdTrees follows in P2's next round); the root goes PENDING, tried again every
   `routesphere.pending-root.retry-sec` (default 30; each try = the bootstrap read again + the tree load), said ONCE when it goes pending
   (WARN, the reason in words: "not listed for <app> yet" or "prime-context does not serve its tree yet: <answer>"), once when served
   (INFO), and once when given up after `routesphere.pending-root.give-up-min` (default 120: "a restart, or a new tenant_created, serves
   it"). One daemon scheduler per road, closed by the engine's close. Red first: today's half-served state pinned by a test (a scripted
   source and trees answering ROOT_NOT_SERVED, then the tree); mutants on the undo and the retry.
2. **The tree never shows a tier its ledger lacks** (P2 gap 10). A reseller's provisioning rings the doorbell `config_event_loader_<root>`
   AND sends `tenant_created`; the event loads the ledger then reloads the tree, the doorbell reloads the tree ~3 s later (debounced) —
   safe today only because of the debounce: a doorbell reload FIRST shows the tier to sessions whose ledger cannot pay from it. Required:
   whatever reloads a root's tree (the event, the doorbell, the start), a tier the new tree holds that the root's ledger has not loaded is
   loaded into the ledger FIRST (`LedgerPort.tierAdded`), once per tier (the event and the ring together = one load); exposed as one step a
   lane's own LiveTrees can call too (say it in the README). Red first: a ring before the event → today the tier is shown, the ledger
   refuses; mutants.
3. **A new tier's account rows at its PARENT are loaded too** (P2 gap 8). `tierAdded` → `addDatabase(tier)` loads the tier's schema only;
   the parent's NEW `packageaccount` row (made with the tier) is not in the parent's cache → `ACCOUNT_NOT_FOUND` at the parent (pinned by
   ad-sphere's `AdTierCreatedPostgresIT.gap_…`). The pinned mem-ledger jar (1.0.0-SNAPSHOT, ~/.m2, 2026-10-07) HAS
   `MemLedger.reloadNewEntities()` on its API (javap; mem-ledger 0f04624 "by a high-water mark") — call it after `addDatabase`, no
   reflection needed for this one; say its cost (it reloads every database's new rows by the high-water mark). Red first.
4. **The naming table** (PC-0022 item 5, §9.4): copy prime-context's `src/test/resources/bootstrap/naming-cases.tsv` (framework-bootstrap
   8f8d922, sha256 `3dfb24f2414f593f…`, 23 cases: tierDb · parentOf · isTierOf, with and without a seed root) BYTE-IDENTICAL into
   seed-naming's test resources (cite the source and the sha256 in the test's javadoc); a test holds `TenantNames` to every case. A
   case the seed answers differently = a red you report (the rule is prime-context's `RootNaming`; do not change either side alone).
5. **`naming.seed-prefix`** (§9.1): the envelope's ONE addition is `config.routesphere.naming: {seed-root, seed-prefix}`; the reader takes
   `seed-root` today; take `seed-prefix` too (default `res_` when absent, said once when a seed root is named without it; a value that is
   not a name prefix refused in words). `TenantNames.LEGACY_PREFIX` stays the default.
6. **The late config values** (P2 gap 7): on Quarkus 3.26.1 item 12's values arrive ~10 s after an application's `@Startup` beans (P2's box
   run), so a bean reading its keys at start gets the jar's own; ad-sphere works around it (`AdBootstrap.configOver`). Find WHY (where
   Quarkus asks the factory, and when the fetch happens), then either make the values visible before the first `@Startup` bean, or write
   in the README exactly when they are visible and what an app must do — with the proof (a minimal offline Quarkus test if 3.26.1's
   artifacts resolve from the repositories you build with; else the reasoning with source lines).
7. **Docs** (README, the bootstrap section): the deployment tenant is the operator's tenant (btcl on the bed) — never `platform`
   (tenant-master reserves it, PC-0022 §9.7); a deployment's identification rules have ONE home, `config.routesphere.identification.rules`
   (prime-context serves nothing at the old home `identification` to a deployment); `tenant_created`'s `apps` are sorted and a `kind: root`
   message comes BEFORE the root is served (item 1); a root added live needs its door to be a listener the process already has (Quarkus
   binds at the start — a window step, P2 gap 9); the pinned samples stay byte-identical with prime-context's (deployment.json
   e1ca4f43…, tenant-created.json 63f396e2… — checked equal at 8f8d922 by the architect, 06:09).

## Rules (unchanged from P1-F)

EVERY mvn under `flock /home/mustafa/telcobright-projects/.one-maven.lock mvn -o …`; never install into `~/.m2`; never push; no sudo /
systemctl; never `pkill -f`; passwords only from the environment. Red first per item, mutants restored with a fresh mtime, a state line
after every item, the report section drafted early. A road that cannot reach its service is reported, not fixed. **The owner's PC
suspends at 10:00 +06: after 09:40 start no build that cannot end by 09:50; by 09:50 commit what is green, write the exact next step in
the state page, and hand back an interim report.**
