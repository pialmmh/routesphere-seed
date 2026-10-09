package com.telcobright.seed.sessionflow.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.seed.sessionflow.dependencies.LedgerSettings;
import com.telcobright.seed.sessionflow.dependencies.ReturnPace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * orchestrix's "return by reference": {@code POST …/partners/{pid}/charge/return {"reference": "<the charge's>"}} gives
 * back exactly what that reference charged, to the bucket it was drawn from. It is idempotent by the reference, so a
 * return may be asked again at any time.
 *
 * <table>
 *   <tr><th>the road answers</th><th>what it means here</th></tr>
 *   <tr><td>200</td><td>returned (first or repeated): closed</td></tr>
 *   <tr><td>404 {@code NO_SUCH_CHARGE}</td><td>nothing was charged under the reference: nothing to undo, closed</td></tr>
 *   <tr><td>409 {@code RETURN_NEEDS_AN_OFFICER}</td><td>not returned and never asked again: one {@code officer} line</td></tr>
 *   <tr><td>409 (any other), 5xx, no answer, unreachable</td><td>asked again with the same reference, then {@code owed}</td></tr>
 *   <tr><td>any other 404</td><td>this orchestrix has no return road: {@code owed}, as before the road existed</td></tr>
 *   <tr><td>401 / 403, any other 4xx</td><td>refused: {@code owed}, with the road's words</td></tr>
 * </table>
 *
 * <p>A return never holds a call: {@link #later} writes one {@code returning} line and hands the ask to one worker
 * thread; {@link #now} asks once on the caller's thread (the call's end, where the settlement must say what happened)
 * and hands a slow road to the worker too. What the worker is still asking when the process stops is in the journal
 * and is asked again at the next start ({@link #resumeOpen}).
 */
final class ReturnRoad {

    /** Builds the request of one prepaid road: the bearer, the tenant, JSON. The ledger's own, so both speak alike. */
    @FunctionalInterface
    interface Requests {
        HttpRequest of(String url, String json, long waitMs);
    }

    enum Outcome { RETURNED, NOT_CHARGED, NEEDS_AN_OFFICER, NO_ROAD, REFUSED, ASK_AGAIN }

    /** @param balanceAfter the bucket's balance after the return, when the road said it */
    record Answer(Outcome outcome, BigDecimal balanceAfter, String words) {
        boolean closed() { return outcome == Outcome.RETURNED || outcome == Outcome.NOT_CHARGED; }
    }

    static final String NO_SUCH_CHARGE = "NO_SUCH_CHARGE";
    static final String RETURN_NEEDS_AN_OFFICER = "RETURN_NEEDS_AN_OFFICER";

    private static final Logger log = LoggerFactory.getLogger(ReturnRoad.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LedgerSettings settings;
    private final HttpClient http;
    private final Requests requests;
    private final OwedJournal journal;
    private final ReturnPace pace;
    private final ScheduledThreadPoolExecutor worker;
    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicBoolean saidNoRoad = new AtomicBoolean();

    /** @param asked how many times this return was asked; @param unsure the reserve got no answer (a late charge is possible) */
    private record Task(OwedJournal.Entry entry, int asked, boolean unsure, boolean rechecked) {
        Task again() { return new Task(entry, asked + 1, unsure, rechecked); }
        Task recheck() { return new Task(entry, asked, unsure, true); }
    }

    ReturnRoad(LedgerSettings settings, HttpClient http, Requests requests, OwedJournal journal, ReturnPace pace) {
        this.settings = settings;
        this.http = http;
        this.requests = requests;
        this.journal = journal;
        this.pace = pace;
        this.worker = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "ledger-returns");
            t.setDaemon(true);
            return t;
        });
        this.worker.setRemoveOnCancelPolicy(true);
    }

    /** The returns the journal left open (a process that stopped while it was asking) are asked again. */
    void resumeOpen() {
        List<OwedJournal.Entry> open = journal.open();
        if (open.isEmpty()) return;
        log.info("ledger: {} return(s) were still being asked when the process stopped — asked again now ({})", open.size(), journal.file());
        open.forEach(e -> hand(new Task(e, 0, false, false), 0));
    }

    /** The reserve must go back and nobody waits for it: one {@code returning} line, then the worker asks. */
    void later(OwedJournal.Entry entry, String why, boolean unsure) {
        if (!askable(entry, why)) return;
        if (waiting.get() >= pace.mostWaiting()) {
            journal.owe(entry, why + " — " + pace.mostWaiting() + " returns are waiting already: not asked");
            return;
        }
        journal.note(OwedJournal.RETURNING, entry, why);
        hand(new Task(entry, 0, unsure, false), 0);
    }

    /**
     * The reserve must go back and the caller says what happened: ONE ask, on the caller's thread. A road that does not
     * take it now is asked again by the worker; the answer is then {@link Outcome#ASK_AGAIN}.
     */
    Answer now(OwedJournal.Entry entry, String why) {
        if (!askable(entry, why)) return new Answer(Outcome.REFUSED, null, "the partner has no accounting partner");
        Answer answer = ask(entry);
        switch (answer.outcome()) {
            case RETURNED, NOT_CHARGED -> log.debug("ledger: the reserve {} went back at once ({})", entry.reference(), answer.words());
            case ASK_AGAIN -> {
                journal.note(OwedJournal.RETURNING, entry, why + " — " + answer.words() + ": asked again");
                hand(new Task(entry, 1, false, false), pace.firstWaitMs());
            }
            default -> end(new Task(entry, 1, false, false), answer, why);
        }
        return answer;
    }

    private boolean askable(OwedJournal.Entry entry, String why) {
        if (entry.billingAccount() != null && entry.reference() != null) return true;
        journal.owe(entry, why + " — the partner has no accounting partner (or the reserve no reference): the return road cannot be asked");
        return false;
    }

    private void hand(Task task, long afterMs) {
        waiting.incrementAndGet();
        worker.schedule(() -> attempt(task), afterMs, TimeUnit.MILLISECONDS);
    }

    private void attempt(Task waited) {
        waiting.decrementAndGet();
        Task task = waited.again();
        try {
            Answer answer = ask(task.entry());
            if (answer.outcome() == Outcome.ASK_AGAIN && task.asked() < pace.tries()) {
                hand(task, task.asked() == 1 ? pace.firstWaitMs() : pace.laterWaitMs());
            } else if (answer.outcome() == Outcome.NOT_CHARGED && task.unsure() && !task.rechecked()) {
                hand(task.recheck(), pace.recheckMs());        // a charge that got no answer may still land: believe it only twice
            } else {
                end(task, answer, null);
            }
        } catch (RuntimeException e) {
            log.error("ledger: the return of {} failed in the worker — its line in {} stays open and is asked again at the next start: {}",
                task.entry().reference(), journal.file(), e.toString());
        }
    }

    /** The last line of a return's story. */
    private void end(Task task, Answer answer, String why) {
        OwedJournal.Entry e = task.entry();
        String said = why == null ? answer.words() : why + " — " + answer.words();
        switch (answer.outcome()) {
            case RETURNED -> {
                log.info("ledger: the reserve {} ({} {}) went back to partner {} of {}", e.reference(), e.amount(), e.uom(), e.partnerId(), e.tenant());
                journal.note(OwedJournal.RETURNED, e, said);
            }
            case NOT_CHARGED -> {
                log.info("ledger: nothing was charged under {} — nothing to give back", e.reference());
                journal.note(OwedJournal.NOT_CHARGED, e, said);
            }
            case NEEDS_AN_OFFICER -> {
                log.error("OFFICER: the reserve {} ({} {}, partner {} of {}) is NOT returned by the ledger: {} — an officer decides. Written to {}",
                    e.reference(), e.amount(), e.uom(), e.partnerId(), e.tenant(), answer.words(), journal.file());
                journal.note(OwedJournal.OFFICER, e, said);
            }
            case NO_ROAD -> {
                if (saidNoRoad.compareAndSet(false, true)) {
                    log.warn("ledger: orchestrix at {} has no return road ({}): what must go back is journaled as owed, as before", settings.baseUrl(), answer.words());
                }
                journal.owe(e, "the ledger has no return road — " + said);
            }
            case REFUSED -> journal.owe(e, "the return road refused — " + said);
            case ASK_AGAIN -> journal.owe(e, "the return road did not take it after " + task.asked() + " ask(s) — " + said);
        }
    }

    /** One ask. Never throws for what the road or the network did: that is an {@link Answer}. */
    private Answer ask(OwedJournal.Entry entry) {
        String url = settings.roads() + "/partners/" + entry.billingAccount() + "/charge/return";
        String body = JSON.createObjectNode().put("reference", entry.reference()).toString();
        HttpResponse<String> response;
        try {
            response = http.send(requests.of(url, body, settings.readTimeoutMs()), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Answer(Outcome.ASK_AGAIN, null, "no answer from the return road: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(Outcome.ASK_AGAIN, null, "interrupted while asking the return road");
        }
        return answerOf(response.statusCode(), response.body(), settings.tokenVar());
    }

    /** The road's answer, read. Package-visible: the table of this class is tested here with no network. */
    static Answer answerOf(int status, String body, String tokenVar) {
        String code = OrchestrixLedger.errorCode(body, status);
        String words = OrchestrixLedger.errorMessage(body);
        if (status == 200 || status == 201) return new Answer(Outcome.RETURNED, balanceAfter(body), "returned");
        if (status == 404 && NO_SUCH_CHARGE.equals(code)) return new Answer(Outcome.NOT_CHARGED, null, "nothing was charged under this reference");
        if (status == 404) return new Answer(Outcome.NO_ROAD, null, "404 " + oneLine(words));
        if (status == 409 && RETURN_NEEDS_AN_OFFICER.equals(code)) return new Answer(Outcome.NEEDS_AN_OFFICER, null, oneLine(words));
        if (status == 409 || status >= 500) return new Answer(Outcome.ASK_AGAIN, null, status + " " + code + " " + oneLine(words));
        if (status == 401 || status == 403) return new Answer(Outcome.REFUSED, null, status + ": the bearer in " + tokenVar + " is refused");
        return new Answer(Outcome.REFUSED, null, status + " " + code + " " + oneLine(words));
    }

    private static BigDecimal balanceAfter(String body) {
        try {
            JsonNode n = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
            return n.hasNonNull("balanceAfter") ? new BigDecimal(n.get("balanceAfter").asText()) : null;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /** The road's words as one short line: they go into a journal line and a log line. */
    private static String oneLine(String words) {
        if (words == null) return "";
        String line = words.replaceAll("\\s+", " ").trim();
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }
}
