# P1-D · stream D — every lane states its rules; one supervisor class per lane; two records-road fixes (report)

Branch `p1-rules` (worktree `routesphere-seed/worktrees/p1-rules`), from `routesphere-flow` 35c955f (the brief). Nothing pushed; nothing
written into `~/.m2`; no database server, no broker reached (H2 and Kafka's `MockProducer` in process only). **Status: in progress.**

| | |
|---|---|
| the reactor on 35c955f, before any change | **461 / 0 / 0 / 3** (tests / failures / errors / skipped) |
| after §6 (e5c0949) | docs only |
| after §5 (fa23e74), from `clean` | **462 / 0 / 0 / 3** |

## 0 · The commits

| commit | item |
|---|---|
| e5c0949 | **§6** — `sdrStatements`' javadoc: once by your own key |
| fa23e74 | **§5** — the Kafka road's leftovers carry their root |

## 6 · §6 — the doc gap

`sdrStatements`' javadoc now says what P1-R §9 item 11 found: write the SDR rows ONCE by their own key — a probe
(`RecordStatement.probe`) or `ON CONFLICT DO NOTHING` (`INSERT IGNORE` on MySQL); a session ended a second time (a restore from an older
row, §L.1) runs them again, and a plain INSERT that meets its duplicate rolls back the whole batch — every session in it — on every try, so
the writer parks (and the batch never commits: a poison batch). The README's records section (§5.1) says the same. No code, no test.

## 5 · §5 — the Kafka road's leftovers carry their root

`SessionCdr.publishALeftover` (was line 176) now publishes `cdr().publish(CdrAssembler.rootOf(tiers.get(0)), callId, tiers)` — the root
from the records' hierarchy (`root > … > tier`), exactly as a live session's road names it (line 56 takes `AdmissionChain.rootDbOf(ctx)`;
a leftover has no context, its records carry the same root as their hierarchy's first node).

- **Red first:** `SessionsInTheAirTest.theLeftoversOfADeadProcess_reachTheirRootsTopic_onAPerRootKafkaSink_bothKinds` — a process dies
  with one session never handed over (`a-2`, its record the entry tier at 0.00) and one handed over (`h-2`, three tiers charged as
  reserved); the next start publishes on `KafkaCdrSink.perRoot(MockProducer, "cdr_")`. Before: nothing published, two ERRORs "the CDR of
  session a-2 names no root … NOT published". After: `cdr_btcl:a-2`, `cdr_btcl:h-2`.
- **Mutant:** the leaf's tier for the root (`tiers.get(0).tenant` → `cdr_res_44_7`) — red.
- The test's helper `flowOn(Path, RecordingCdrSink)` now takes any `CdrSink` (the only change to an existing line of that test).

## Open questions for the owner / the architect

(none yet)
