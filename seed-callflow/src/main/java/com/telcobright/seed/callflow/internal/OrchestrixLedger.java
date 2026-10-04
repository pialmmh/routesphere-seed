package com.telcobright.seed.callflow.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.callflow.api.TierSettlement;
import com.telcobright.seed.callflow.dependencies.LedgerSettings;
import com.telcobright.seed.callflow.dependencies.ReturnPace;
import com.telcobright.seed.callflow.spi.LedgerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * The {@link LedgerPort} on orchestrix portal-api's prepaid roads — the ledger of a switch whose accounting lives in
 * orchestrix (on the Odoo database), as the ad switch's does.
 *
 * <table>
 *   <tr><th>verb</th><th>road</th></tr>
 *   <tr><td>reserve</td><td>road 16, {@code POST …/partners/{pid}/charge}: the usage carries the rated amount, the reference
 *       is the idempotency key. {@code {pid}} is the partner's {@code billing_account_id} (the Odoo partner), never the
 *       switch's own partner id</td></tr>
 *   <tr><td>settle</td><td>the tier pays everything it reserved: no call. It pays nothing: the whole charge goes back by the
 *       return road, asked once here so that the settlement says what happened. It pays a part: the rest is owed (the
 *       return road gives back a WHOLE charge by its reference, never a part)</td></tr>
 *   <tr><td>release</td><td>the whole charge goes back by the return road. It is asked by a worker, never on the call's
 *       thread: a refused candidate must not wait for the ledger</td></tr>
 * </table>
 *
 * <p><b>"Must go back".</b> {@link ReturnRoad}: {@code POST …/partners/{pid}/charge/return} with the CHARGE's reference;
 * idempotent, so a return may be asked again. Road 15 is an officer's credit and is never used by a service. What the
 * return road does not give back — it is not on this orchestrix, it refuses, it does not answer after the tries, or it
 * says an officer must decide — is one durable line in the {@link OwedJournal} and one ERROR, and the settlement says it
 * is not closed. A reserve that got no answer ({@code unsure}) is asked back too: the call was not charged on it.
 *
 * <p>Road 16's answers: {@code 200/201} the reserve; {@code 402} nobody can pay (empty); {@code 409/404/400/422} a refusal
 * with the body's code; {@code 401/403/5xx}, no connection, or no answer in time: a {@link LedgerFault}.
 *
 * <p><b>Time.</b> A reserve is given the time the call's admission has left. Each request waits the smaller of the read
 * timeout and that time. A request that got no answer is repeated ONCE with the SAME reference (the road is idempotent
 * by reference), and only when time is left. A reserve that ends with no answer is written to the journal as
 * {@code unsure}: the ledger may have taken the money after the switch stopped waiting.
 */
public final class OrchestrixLedger implements LedgerPort {

    private static final Logger log = LoggerFactory.getLogger(OrchestrixLedger.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The cause when a switch partner is not linked to an accounting partner. */
    public static final String NO_BILLING_ACCOUNT = "NO_BILLING_ACCOUNT";

    private final LedgerSettings settings;
    private final String bearer;
    private final HttpClient http;
    private final OwedJournal owed;
    private final ReturnRoad returns;

    /** @param environment the process environment by variable NAME ({@code System::getenv} in production) */
    public OrchestrixLedger(LedgerSettings settings, Function<String, String> environment, OwedJournal owed) {
        this(settings, environment, owed, ReturnPace.standard());
    }

    public OrchestrixLedger(LedgerSettings settings, Function<String, String> environment, OwedJournal owed, ReturnPace pace) {
        this.settings = settings;
        this.bearer = settings.bearer(environment);
        this.owed = owed;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.connectTimeoutMs())).build();
        this.returns = new ReturnRoad(settings, http, this::requestOf, owed, pace);
        log.info("ledger: orchestrix {} tenant {} profile {} (bearer from {}; connect {} ms, read {} ms); a reserve that must go back is asked of the return road {} times, and what it does not give back is journaled in {}",
            settings.baseUrl(), settings.tenant(), settings.profile(), settings.tokenVar(), settings.connectTimeoutMs(), settings.readTimeoutMs(), pace.tries(), owed.file());
        returns.resumeOpen();
    }

    @Override
    public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) {
        return reserve(level, amount, reference, Long.MAX_VALUE);
    }

    @Override
    public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) {
        String account = billingAccountOf(level);
        HttpResponse<String> answer = chargeOrGiveUp(level, amount, reference, account, withinMs);
        int status = answer.statusCode();
        if (status == 200 || status == 201) return Optional.of(reservationOf(parse(answer.body(), reference)));
        if (status == 402) return cannotPay(level, amount, reference, answer);
        if (status == 409 || status == 404 || status == 400 || status == 422) throw new LedgerRefusal(errorCode(answer.body(), status), errorMessage(answer.body()));
        throw new LedgerFault("the ledger answered " + status + " to the reserve of partner " + level.getPartnerId() + " (ref " + reference + ")"
            + (status == 401 || status == 403 ? " — the bearer in " + settings.tokenVar() + " is refused" : ""));
    }

    @Override
    public long slowestAnswerMs() { return settings.readTimeoutMs(); }

    /** Road 16. A request that was sent and never answered leaves an {@code unsure} line: nobody knows if the money moved. */
    private HttpResponse<String> chargeOrGiveUp(LevelAdmission level, BigDecimal amount, String reference, String account, long withinMs) {
        try {
            return post(settings.roads() + "/partners/" + account + "/charge", chargeOf(level, amount, reference), reference, withinMs);
        } catch (NoAnswer gaveUp) {
            noteUnsure(level, reference, amount, gaveUp.getMessage());
            throw new LedgerFault(gaveUp.getMessage(), gaveUp.getCause());
        }
    }

    private void noteUnsure(LevelAdmission level, String reference, BigDecimal amount, String why) {
        try {
            owed.unsure(level, reference, amount, why);
            returns.later(OwedJournal.Entry.of(level, reference, amount), "the reserve got no answer: whatever the ledger took under it goes back", true);
        } catch (RuntimeException e) {
            log.error("ledger: the unsure reserve {} could not be written to {} — the line is in the log above: {}", reference, owed.file(), e.toString());
        }
    }

    @Override
    public TierSettlement settle(LevelAdmission level, BigDecimal charged) {
        BigDecimal toReturn = TierSettlement.reservedOf(level).subtract(charged);
        if (toReturn.signum() <= 0) return TierSettlement.of(level, charged, level.getBalanceAfter());
        if (!oneWholeCharge(level, charged)) {
            owed.owe(level, toReturn, "settled at " + charged + ": the rest of the reserve (the return road gives back a whole charge, never a part)");
            return TierSettlement.owed(level, charged, toReturn + " of the reserve cannot go back by the return road: journaled as owed");
        }
        ReturnRoad.Answer answer = returns.now(OwedJournal.Entry.of(level, level.getDebitReference(), toReturn), "settled at " + charged);
        if (answer.closed()) return TierSettlement.of(level, charged, answer.balanceAfter() != null ? answer.balanceAfter() : level.getBalanceAfter());
        return TierSettlement.owed(level, charged, "the reserve did not go back at the settlement: " + answer.words());
    }

    /** The whole of a refused candidate's reserve goes back. Asked by the return road's worker: this call never waits. */
    @Override
    public void release(LevelAdmission level, String why) {
        BigDecimal reserved = TierSettlement.reservedOf(level);
        if (reserved.signum() <= 0) return;
        if (!oneWholeCharge(level, BigDecimal.ZERO)) {
            owed.owe(level, reserved, "released: " + why + " (several charges under one tier: the return road gives back one charge by its reference)");
            return;
        }
        returns.later(OwedJournal.Entry.of(level, level.getDebitReference(), reserved), "released: " + why, false);
    }

    /** What the return road can undo: ONE charge, whole. A tier that paid a part, or reserved in several windows, is not that. */
    private static boolean oneWholeCharge(LevelAdmission level, BigDecimal charged) {
        return charged.signum() == 0 && level.getReservationCount() <= 1;
    }

    // ── road 16 ─────────────────────────────────────────────────────────────

    /** The accounting partner of a switch partner. A partner that has none cannot be charged: a refusal, not a fault. */
    private static String billingAccountOf(LevelAdmission level) {
        String account = level.getPartner() == null ? null : level.getPartner().getBillingAccountId();
        if (account == null || account.isBlank()) {
            throw new LedgerRefusal(NO_BILLING_ACCOUNT, "partner " + level.getPartnerId() + " of " + level.getDbName() + " has no billing_account_id");
        }
        return account.trim();
    }

    private static ObjectNode chargeOf(LevelAdmission level, BigDecimal amount, String reference) {
        ObjectNode body = JSON.createObjectNode();
        ObjectNode usage = body.putObject("usage");
        usage.put("kind", level.getUsageKind() == null ? "image" : level.getUsageKind());
        usage.put("seconds", level.getUsageSeconds());
        ObjectNode rate = usage.putObject("rate");
        rate.put("amount", amount);
        rate.put("unit", "view");
        rate.put("resolutionSec", 1);
        rate.put("surchargeSec", 0);
        body.put("reference", reference);
        return body;
    }

    /** The road's answer keys, verbatim. A unit account answers units; a cash account answers money. */
    private static Reservation reservationOf(JsonNode n) {
        BigDecimal units = decimal(n, "chargeUnits");
        BigDecimal money = decimal(n, "chargeBdt");
        BigDecimal reserved = units != null && units.signum() > 0 ? units : money == null ? BigDecimal.ZERO : money;
        return new Reservation(longOf(n, "chargeAccount"), text(n, "chargeUom"), reserved, decimal(n, "balanceBefore"), decimal(n, "balanceAfter"),
            n.path("repeated").asBoolean(false));
    }

    private Optional<Reservation> cannotPay(LevelAdmission level, BigDecimal amount, String reference, HttpResponse<String> answer) {
        log.debug("ledger: partner {} cannot pay {} (ref {}): {}", level.getPartnerId(), amount, reference, errorMessage(answer.body()));
        return Optional.empty();
    }

    /** The request was sent (or may have been) and no answer came in the time there was. */
    private static final class NoAnswer extends RuntimeException {
        NoAnswer(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * One POST, answered within {@code withinMs}. Each try waits the smaller of the read timeout and the time left. A try
     * with no answer is repeated ONCE with the same body (the same reference) when time is left; then it is {@link NoAnswer}.
     */
    private HttpResponse<String> post(String url, ObjectNode body, String reference, long withinMs) {
        long startedNs = System.nanoTime();
        for (int attempt = 1; ; attempt++) {
            long waitMs = Math.max(1, Math.min(settings.readTimeoutMs(), leftOf(withinMs, startedNs)));
            try {
                return http.send(requestOf(url, body, waitMs), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (HttpConnectTimeoutException e) {
                throw new LedgerFault("the ledger is unreachable at " + settings.baseUrl() + ": no connection within " + settings.connectTimeoutMs() + " ms", e);
            } catch (HttpTimeoutException e) {
                long left = leftOf(withinMs, startedNs);
                if (attempt > 1) throw new NoAnswer("the ledger timed out twice on " + url + " (ref " + reference + ")", e);
                if (left <= 0) throw new NoAnswer("the ledger did not answer within the " + waitMs + " ms the admission had left on " + url + " (ref " + reference + ")", e);
                log.warn("ledger: {} did not answer within {} ms (ref {}) — repeating the same reference once, {} left", url, waitMs, reference,
                    left == Long.MAX_VALUE ? "no limit" : left + " ms");
            } catch (ConnectException | SSLHandshakeException e) {
                throw new LedgerFault("the ledger is unreachable at " + settings.baseUrl() + ": " + e.getMessage(), e);
            } catch (IOException e) {
                throw new NoAnswer("the ledger call failed on " + url + " (ref " + reference + "): " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NoAnswer("interrupted while calling the ledger (ref " + reference + ")", e);
            }
        }
    }

    private HttpRequest requestOf(String url, ObjectNode body, long waitMs) {
        return requestOf(url, body.toString(), waitMs);
    }

    /** One prepaid road's request: the bearer, the tenant, JSON. The return road asks with the same one. */
    HttpRequest requestOf(String url, String json, long waitMs) {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(waitMs))
            .header("Authorization", "Bearer " + bearer)
            .header("X-Tenant-Id", settings.tenant())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
    }

    /** What is left of the caller's time since the call began. No limit stays no limit. */
    private static long leftOf(long withinMs, long startedNs) {
        return withinMs == Long.MAX_VALUE ? Long.MAX_VALUE : withinMs - (System.nanoTime() - startedNs) / 1_000_000;
    }

    // ── the answer's JSON ───────────────────────────────────────────────────

    private static JsonNode parse(String body, String reference) {
        try {
            return JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            throw new LedgerFault("the ledger's answer is not JSON (ref " + reference + "): " + e.getMessage(), e);
        }
    }

    /** The error envelope {@code { error: { code, message } }}. */
    static String errorCode(String body, int status) {
        JsonNode n = lenient(body);
        if (n.path("error").hasNonNull("code")) return n.path("error").get("code").asText();
        if (n.hasNonNull("code")) return n.get("code").asText();
        return status == 409 ? "CONFLICT" : status == 404 ? "NOT_FOUND" : "VALIDATION";
    }

    static String errorMessage(String body) {
        JsonNode n = lenient(body);
        if (n.path("error").hasNonNull("message")) return n.path("error").get("message").asText();
        if (n.hasNonNull("message")) return n.get("message").asText();
        return body == null ? "" : body;
    }

    private static JsonNode lenient(String body) {
        try {
            return JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    private static Long longOf(JsonNode n, String field) { return n.hasNonNull(field) ? n.get(field).asLong() : null; }

    private static String text(JsonNode n, String field) { return n.hasNonNull(field) ? n.get(field).asText() : null; }

    private static BigDecimal decimal(JsonNode n, String field) { return n.hasNonNull(field) ? new BigDecimal(n.get(field).asText()) : null; }
}
