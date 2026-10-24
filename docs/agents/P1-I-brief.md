# P1-I — the seed: a connect cut by the admission's budget says so; the budget test holds under load; the root's source guarded (READY)

Written 2026-10-24 13:5x +06 by the architect (Opus). Branch `p1-follow`, fast-forwarded by the architect to routesphere-flow 97afef3
(the owner's readable shape R14; gate 569/0/0/3). Commits `P1-I <n>: …`, state lines under `## P1-I` in P1-F-state.md, a P1-I section
in the report. Additive; every existing signature kept.

## The finding (the architect's gates of 9d97760 at 07:21 and of 97afef3 at 13:39, both under load)
`OrchestrixLedgerTest.aReserveNeverWaitsLongerThanTheAdmissionHasLeft_evenWhenItsOwnTimeoutIsLonger` red with
"the ledger is unreachable at http://127.0.0.1:<port>: no connection within 30000 ms" after ~0.2 s. Cause, from the code: Java's
`HttpClient` applies the REQUEST's timeout (`requestOf(…).timeout(waitMs)`, here the 200 ms the admission had left) to the connect phase
too; on a loaded machine the local connect did not finish inside 200 ms, the JDK threw `HttpConnectTimeoutException`, and
`OrchestrixLedger.post` (internal/OrchestrixLedger.java:211) words EVERY such exception with the configured `connectTimeoutMs` (30 000).
The CLASS is right — nothing was sent, so the money is certain: a `LedgerFault`, never a `NoAnswer` — only the words are wrong, and the
test's 200 ms budget is too tight for a loaded box.

## Items
1. **The words.** A connect that the admission's budget cut (the request's `waitMs` < `connectTimeoutMs`) says
   "no connection within the <waitMs> ms the admission had left"; one the configured connect timeout cut keeps today's words. Still a
   `LedgerFault` in both (nothing was sent). Red first: a connect that cannot complete — e.g. a local listening socket whose accept queue is
   full (backlog 1 and one connection never accepted), or the JDK's own behaviour proven another documented way — with a budget below the
   connect timeout; today's words name the 30 000 ms. A mutant on the branch.
2. **The test holds under load.** Keep the test's meaning ("gives up at the budget, not at its own timeout") with clocks far apart: the
   budget large enough that a loaded local connect finishes inside it (e.g. 1 000 ms), the server's answer later than the budget (e.g.
   3 s), the ledger's own read timeout much longer (e.g. 5 s), the elapsed bound between them. Then 20 runs of OrchestrixLedgerTest under an
   8-thread CPU hog (stopped by PID), before and after; name the numbers.
3. **The root's source, said.** `RoutesphereFacts.root()`'s javadoc and the sessionflow README's ingress section: the root is the lane's
   rule over the ingress (`engine.identifyRootLevelTenant`), NEVER a value the request carries (a body field, a header, a query) — a caller
   that could name the root could bill another operator. Docs only; the ad's round (P2c) pins it with a test on its side.
4. The whole reactor in the worktree, then a git-archive clean copy of the head (offline, under the lock); the report section.

## Rules (as P1-F/G/H)
Every mvn under the lock with -o; never install into ~/.m2; never push; no sudo/systemctl; never pkill -f; passwords only from the
environment; red first; mutants restored with a fresh mtime; a state line after every item. A road that cannot reach its service is
reported, not fixed.
