# P1-K — the seed: a password comes from the process environment only (the owner's ruling) (READY)

Written 2026-10-24 16:51 +06 by the architect (Opus) on the owner's word of 16:51 ("Environment only"), which re-affirms
his rule of 2026-09-24 (secreteer → the box's environment file; an app reads only its environment). Branch `p1-follow`, already
fast-forwarded by the architect to routesphere-flow **c17ffaa** (P1-J merged; gate 580/0/0/3). Commits `P1-K <n>: …`, state lines under
`## P1-K`, a P1-K section in the report.

## Items
1. **switch-db's password from the environment only.** Today `SwitchLedgerMaker.passwordOf` (SwitchLedgerMaker.java:82) reads
   `login.password(environment, deployment.secrets())`: the environment first, then the bootstrap envelope's `secrets` (values prime-context
   serves behind the token). Remove the fallback: `password-ref` resolves from the process environment ONLY, as the ledger's own login
   already does (P1-J). A variable that is not in the environment refuses the root's start in words naming the VARIABLE (never a value),
   as today's missing-password road does. Red first: a deployment whose switch-db password is in the envelope's `secrets` only — today it
   connects; after, it is refused in words. Find and remove every other road from the envelope's `secrets` to a credential (grep: the
   only one in main code at c17ffaa is that line; the record methods behind it — `SwitchDb.password(env, secrets)` — go or are kept
   unused only if a public signature needs them: say which). Mutant: the fallback put back → red.
2. **What the envelope's `secrets` still is to the seed**: say it in `Deployment`'s javadoc and the README's bootstrap section — the seed
   READS no secret from the bootstrap; the box's environment file is the one road (the secreteer). If `Deployment.secrets()` is read by
   nobody after item 1, deprecate it (keep the signature; the reader may keep parsing it) and say why.
3. Docs + the README history line; SwitchLedgerMakerTest's pin of the old behaviour changed to pin the new (say it).
4. The reactor in the worktree, then a git-archive clean copy of the head (offline, under the lock); the report section.

## Rules (as before)
Every mvn under the lock with -o; never install into ~/.m2; never push; no sudo/systemctl; never pkill -f; no password in any file,
command line or commit (a test's password is a generated value in the test's own environment map); red first; mutants restored with a
fresh mtime; a state line after every item. PC-0023 runs on prime-context in parallel (it may hold the lock up to ~45 min for its two
suites).
