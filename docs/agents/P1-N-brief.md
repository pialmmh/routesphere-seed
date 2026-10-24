# P1-N — the seed: three url forms whose password maskPassword still prints (READY)

Written 2026-10-24 ~20:15 +06 by the architect after reviewing P1-M (p1-follow 0446c4c, code 778531f). Branch `p1-follow`, from the
routesphere-flow head that merges P1-M (the architect fast-forwards p1-follow to it before you start). Same rules as P1-M.

## Why

The urls the seed prints come from prime-context's deployment documents, which never carry a secret (N53), so `LogText.maskPassword` is
defence in depth. But my probe of 778531f's LogText (compiled alone, no Maven) prints three common forms raw:

| input | today | required |
|---|---|---|
| `redis://secret@host:6379/0` (Lettuce reads a lone userinfo as the PASSWORD) | raw | `redis://***@host:6379/0` |
| `https://tok3n@host/x` (a bare token — your open point) | raw | `https://***@host/x` |
| `jdbc:mysql://u:pa;ss@h:3306/db` (Connector/J's authority runs past ';') | raw | `jdbc:mysql://u:***@h:3306/db` |
| `jdbc:mysql://u:pa/ss@h:3306/db` (unencoded '/', the connection fails but the log shows the password) | raw | `jdbc:mysql://u:***@h:3306/db` |
| `jdbc:mysql://address=(host=h)(port=3306)(user=u)(password=p)/db` (Connector/J's key-value host form) | raw | `…(user=u)(password=***)/db` |

These must stay as they are today: `jdbc:mysql://h:3306/db?user=a@b.com&password=p` → `…user=a@b.com&password=***` (an '@' in the query is not a
userinfo) · `jdbc:mysql://[::1]:3306/db` unchanged · `jdbc:mysql://u:p@[::1]:3306/db` → `u:***@[::1]:3306/db` · `redis://:p@ss@host/0` →
`redis://:***@host/0` · `jdbc:mariadb://u:p%40ss@h/db` → `u:***@h/db` · every P1-M test.

## The rule (the principle: an over-mask beats a leak — a log line may lose a user name, never show a password)

1. The userinfo ends at the LAST '@' before the first '?' or '#' (not at '/' or ';': a password may carry them unencoded). An '@' in a
   path (unusual) then over-masks — say it in the javadoc.
2. A userinfo WITHOUT ':' is masked whole (`***@host`): Lettuce's `redis://password@host`, a bare token. A user name alone is lost from
   the line; accepted.
3. A key-value block `(name=value)` whose name says password / passwd / pwd / secret is masked like a parameter (`(password=***)`).
4. An unencoded '?' or '#' inside a userinfo password still ends the search — every parser breaks on it; say it as accepted, no code.

## Items

1. The three forms in `seed-logtext` LogText.maskPassword. Red first: one test per row of the table above (rows "today raw"), and the
   "must stay" rows pinned. Mutants: the lone userinfo kept; ';' or '/' ending the userinfo search; the '(' form skipped — each red,
   restored with a fresh mtime.
2. Docs: the sessionflow README's maskPassword paragraph (the rule and its over-mask); the report section `## P1-N`, the state line.

## Gates

The whole reactor from clean in the worktree and on a git-archive clean copy, offline, no install, under the lock; statewalk 4.1.0 only;
the R-1 proofs green. **The Maven lock:** the prime-context agent may hold it for a UI rig (up to 45 min from ~20:2x) — wait for it;
never break it. Report by SubagentHandback (or SendMessage to main if that slot is used).
