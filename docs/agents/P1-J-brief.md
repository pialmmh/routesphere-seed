# P1-J — the seed: accounts written after a tier exists reach the switch ledger; the ledger's own login (READY)

Written 2026-10-24 14:3x +06 by the architect (Opus). Branch `p1-follow` from routesphere-flow **206e7f0** (fast-forward it first:
`git merge --ff-only 206e7f0` in your worktree). Commits `P1-J <n>: …`, state lines under `## P1-J`, a P1-J section in the report.
Additive; every existing signature kept; the pinned §C samples (deployment.json e1ca4f43…, tenant-created.json 63f396e2…) stay
byte-identical with prime-context — a new shape goes in a NEW sample file.

Source: ad-sphere P2c's end-to-end run against prime-context 8f8d922 (read only: `ad-sphere/worktrees/p2-adflow/docs/ad-as-call/
P2-report.md` "P2c 4", findings G1 and G3; the run's logs ~/p2-work/logs/pc-e2e-report-run8.txt).

## Items
1. **G1 — accounts written after a tier's `tenant_created` reach the ledger.** Today the switch ledger merges new rows
   (`MemLedger.reloadNewEntities`, P1-G 3) only when a tier is ADDED. The only possible order is: prime-context's road makes the tier
   (the event), THEN the BSS writes its accounts (a package purchase) → prime-context's tree is rebuilt and the doorbell
   `config_event_loader_<root>` rings → the tree shows the accounts, but the ledger answers ACCOUNT_NOT_FOUND (P2c run 8: 204) until another
   tier is added or the process restarts. Required: every reload of a root's tree that the doorbell (or a `tenant_created`, or the pending
   road) causes ALSO merges the root's new rows into its switch ledger — under the port's write gate, ghosts taken out as in
   `NewRowsOfATier`, coalesced (a burst of rings = one merge; say the bound, e.g. at most one merge per root per N seconds, N by config with
   a default), never on the session's hot path. Say the cost on the pinned jar (it reads both tables of every tier schema whole) and from
   mem-ledger 0f04624 on (the high-water mark). Red first: P2c's order on the testkit — a tier added, THEN its account written, a ring →
   the reserve answers ACCOUNT_NOT_FOUND today; after: it pays. Mutants on the merge and on the coalescing.
2. **G3 — the switch ledger's own login (additive).** prime-context grants the app's role (`ad_sphere`: the app's tables) and the switch
   ledger's role (the money tables: packageaccount, packageaccountreserve) separately and refuses one role for both ("a role is never
   another's"). The seed has ONE `switch-db` login per root, which the app also uses (ad-sphere's tree profile), so the ledger wrote as
   `ad_sphere` → "permission denied for table packageaccountreserve" (P2c run 5; the lab worked around it with a role MEMBERSHIP). Add an
   OPTIONAL `ledger.db {user, password-ref[, url, schema]}` for a root whose ledger is the switch ledger (url/schema default to switch-db's):
   when present, `SwitchLedgerMaker` connects with it; when absent, switch-db as today (no deployment changes on the day). The
   DeploymentReader reads it (a NEW sample `bootstrap/deployment-with-ledger-login.json`); `RootResources` gains it without breaking its
   record's existing constructor (a second constructor). The password-ref is read from the environment only, as switch-db's (the secrets
   ruling). Red first: a reader test and a maker test (the maker given both logins uses the ledger's). prime-context accepting the key is a
   separate round (PC-0023, on the owner's word) — say in the README that until it lands, a deployment document cannot carry it.
3. **Docs**: the README's switch-ledger section (the merge on every ring, its bound and cost; the two logins and why — separation of
   duties at the database, ONE writer of money per root), the history line.
4. The reactor in the worktree, then a git-archive clean copy (offline, under the lock); the report section.

## Rules (as before)
Every mvn under the lock with -o; never install into ~/.m2; never push; no sudo/systemctl; never pkill -f; passwords only from the
environment; red first; mutants restored with a fresh mtime; a state line after every item; timing-sensitive tests proven under a CPU hog.
P2 is idle; no other agent needs the lock now except the architect's gates.
