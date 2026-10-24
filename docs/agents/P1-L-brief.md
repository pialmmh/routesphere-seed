# P1-L — the seed: the no-root log is injection-safe, the machine's NO_TENANT road is said too, a written password-ref is never printed (READY)

Written 2026-10-24 17:47 +06 by the architect (Opus) after reviewing P1-K item 5 (`internal/NoRootAtTheDoor`). Branch
`p1-follow`, already fast-forwarded to routesphere-flow **ab91c44** (P1-K merged; gate 595/0/0/3). Commits `P1-L <n>: …`, state lines
under `## P1-L`, a P1-L section in the report. Additive.

## Items
1. **Log injection.** `NoRootAtTheDoor` writes values that can come from the request into the WARN line: `facts.sessionId()` (the ad's
   session id is built from the request's MAC: `ad-<tenant>-<mac>-<ms>-<n>`), the ingress's toString, and a failed rule's exception text.
   A value carrying CR/LF would write a forged log line. Every value the door logger writes is cleaned in ONE place: control characters
   (CR, LF, TAB and the rest of C0/C1, U+2028/2029) replaced by a visible escape (`\n` → `\\n`, others `\\uXXXX`), and capped (say
   the cap, e.g. 200 characters, with "…" when cut). Red first: a session id with "\n[app] forged" gives two lines today; after, one.
   Mutants on the cleaning and the cap. Apply the same helper wherever the seed logs a value from a request's facts (grep the seed's
   `log.warn/info/error` lines that take facts values; list them in the report; clean those that do).
2. **The machine's NO_TENANT road is said too** (your P1-K open point 1; the owner's rule covers it): a session whose facts carry no root
   and whose lane's rule names none at `startSession` is refused NO_TENANT as today AND said through the SAME door logger (same window
   per door, same `refusedNoRoot` count). An ingress that followed the two lines never launches a no-root session, so nothing is counted
   twice — prove it (a test: the door refuses, nothing is launched, the count is 1). Red first: today the start's refusal has no WARN line.
3. **A written password-ref is never printed.** `RootResources` and `SwitchDb` (and any record that holds a password-ref) print a ref that
   is not `env:<NAME>` as `<not an env: reference — hidden>` in their toString (P1-K stopped repeating it in refusals; finish the job).
   Red first; a mutant.
4. Docs (README's ingress section: the cleaning and the cap; the history line), the reactor in the worktree, a git-archive clean copy
   (offline, under the lock), the report section.

## Rules (as before)
Every mvn under the lock with -o; never install into ~/.m2; never push; no sudo/systemctl; never pkill -f; passwords only from the
environment; red first; mutants restored with a fresh mtime; a state line after every item. P2d runs on ad-sphere in parallel and PC-0023
item 7 on prime-context: keep lock holds short.
