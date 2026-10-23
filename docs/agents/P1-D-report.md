# P1-D · stream D — every lane states its rules; one supervisor class per lane; two records-road fixes (report)

Branch `p1-rules` (worktree `routesphere-seed/worktrees/p1-rules`), from `routesphere-flow` 35c955f (the brief). Nothing pushed; nothing
written into `~/.m2`; no database server, no broker reached (H2 and Kafka's `MockProducer` in process only). **Status: in progress.**

| | |
|---|---|
| the reactor on 35c955f, before any change | **461 / 0 / 0 / 3** (tests / failures / errors / skipped) |
| after §6 (e5c0949) | docs only |
| after §5 (fa23e74), from `clean` | **462 / 0 / 0 / 3** |
| after §1, from `clean` | **465 / 0 / 0 / 3** |

## 0 · The commits

| commit | item |
|---|---|
| e5c0949 | **§6** — `sdrStatements`' javadoc: once by your own key |
| fa23e74 | **§5** — the Kafka road's leftovers carry their root |
| (§1) | **§1** — every lane states its rules |

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

## 1 · §1 — every lane states its rules (decision 3)

- **Abstract now:** `delivered(C, Outcome)`, `refundPolicy(C)`, `rerouteActionFor(String)`, `cdrStatements(C, CdrEvent)` — with `name`,
  `serviceGroup`, `identifyEntryPartner`, `routing`, `rateAtLevel` the nine a lane must answer. Each javadoc says what every lane decides and
  gives the call's answer (delivered: the call — answered, the SMS — its delivery report, the ad — the view shown; refund: the call —
  `RefundPolicy.callsRule()`; reroute: the call's v1 table, and a lane with one hop and no retry answers FAIL_TERMINAL for every cause; the
  CDR: `cdrTableRowOf`). The null guards of the base stay (a null refund rule = the call's; a null action = FAIL_TERMINAL).
- **The old body is a final helper:** `protected final List<RecordStatement> cdrTableRowOf(CdrEvent record)` =
  `CdrTableSql.statementsOf(record, storeOf(record))`.
- **The lanes say their rules:** VoiceFlow, LabFlow, WifiFlow, LedgerFlow → `ctx.answered()` + `RefundPolicy.callsRule()`; AdFlow, WifiFlow,
  LedgerFlow → FAIL_TERMINAL for every cause (VoiceFlow and LabFlow keep the v1 table, SmsFlow its own); all six → `cdrTableRowOf(record)`.
  AdFlow and SmsFlow already stated `delivered` and `refundPolicy`.
- **Unchanged on purpose:** `reserveBalance`, `finalizeCost`, `settleOutcome`, `identifyPartner` keep their defaults;
  `iterateResellerTenants`, `finishSession` and the 14 `SessionHandler` methods (the 13 calls + `timings`) stay final.
- **The drift guard** `api/RoutesphereFlowDriftTest`: (1) the abstract methods of `RoutesphereFlow` are exactly the nine; (2) every
  non-static method of statewalk's `SessionHandler` is implemented FINAL, and so are (b) and (g); (3) the class javadoc marks exactly the
  nine with ■ (it reads the source file). The table got a first row (`name■ · serviceGroup■`) and a paragraph "Every lane states its rules".
- **Red first:** (1) and (3) red on the old code (the abstract set was the old five; no mark); (2) green from the start (it guards what was
  already true).
- **Mutants (5, each red, each restored with a fresh mtime):** `delivered` given back a default → (1) red; `finishSession` not final → (2)
  red; `settle` not final → (2) red; the ■ of `refundPolicy` removed → (3) red; a ■ on `identifyPartner` added → (3) red.
- **No existing test had to change:** every anonymous flow in the tests is a `LabFlow`, which states the rules.
- README §2 (the application example names the four new ones) and §3 (■ in the table, the paragraph) follow.

## Open questions for the owner / the architect

(none yet)
