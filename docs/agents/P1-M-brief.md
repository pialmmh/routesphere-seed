# P1-M — the seed: the log cleaner reaches seed-campaign, a password inside a URL is never printed, invisible characters are escaped (READY)

Written 2026-10-24 18:57 +06 by the architect (Opus) from P1-L's open points. Branch `p1-follow`, fast-forwarded by the
architect to the merge of P1-L on routesphere-flow (named in the launch message). Commits `P1-M <n>: …`, state lines under `## P1-M`, a
P1-M section in the report. Additive; `api.LogText` keeps its signature (lanes may already import it).

## Items
1. **One cleaner for every seed module.** seed-campaign sits below sessionflow (it depends on no other seed module), so its 5 lines
   (DefaultCampaignService:206, JdbcCampaignStore:260, QueuedCampaignStore:177 and :431, ChangeJournal:201 — your list) cannot reach
   `api.LogText`. Move the cleaner to the BOTTOM: a tiny module (`seed-logtext`, one class + its tests, no dependency of its own) that
   seed-campaign and seed-sessionflow both depend on; `com.telcobright.seed.sessionflow.api.LogText` stays as a thin delegate so no
   import breaks (say it in its javadoc). Clean the 5 lines. Red first: a task id with "\n" forges a line in seed-campaign today.
2. **A password inside a URL is never printed.** `BootstrapRoad.describe` and the records print a redis url or a JDBC url as written; a
   url that carries a password (`redis://:secret@host`, `redis://user:secret@host`, `jdbc:postgresql://host/db?password=secret`,
   `…;password=secret`, MySQL's `password=` in the query) would put it in the log. ONE helper (in the bottom module) hides the userinfo
   password and any `password`/`pwd`/`secret` parameter (case-blind) as `***` wherever the seed prints a url — the bootstrap line, every
   record's toString, every log line that prints one (grep; list them). The url used to CONNECT is unchanged. Red first per form; mutants.
3. **Invisible characters.** Escape as `\uXXXX` the Unicode format characters that reorder or hide what a terminal shows: the bidi
   controls U+202A–U+202E and U+2066–U+2069, the marks U+200E/U+200F/U+061C, the zero-width U+200B–U+200D, U+2060 and U+FEFF (or the
   whole Cf category — say which, and why). Red first; a mutant.
4. Docs (the sessionflow README's ingress section, the history line), the reactor in the worktree, a git-archive clean copy (offline,
   under the lock), the report section.

## Rules (as before)
Every mvn under the lock with -o; never install into ~/.m2; never push; no sudo/systemctl; never pkill -f; passwords only from the
environment (a test's secret is a generated value); red first; mutants restored with a fresh mtime; a state line after every item. P2d runs
on ad-sphere in parallel: keep lock holds short.
