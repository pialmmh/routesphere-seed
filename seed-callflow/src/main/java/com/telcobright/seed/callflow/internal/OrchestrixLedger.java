package com.telcobright.seed.callflow.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.callflow.api.TierSettlement;
import com.telcobright.seed.callflow.dependencies.LedgerSettings;
import com.telcobright.seed.callflow.spi.LedgerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
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
 *   <tr><td>settle</td><td>the tier pays everything it reserved: no call. It pays less: the rest must go back</td></tr>
 *   <tr><td>release</td><td>everything must go back</td></tr>
 * </table>
 *
 * <p><b>"Must go back".</b> orchestrix has no "return by reference" road yet, and road 15 is an officer's credit that a
 * service must never use. So what must go back is written to the {@link OwedJournal} — one durable line and one ERROR —
 * and the settlement says it is not closed. When orchestrix has the road, it is called here and the journal stays empty.
 *
 * <p>Road 16's answers: {@code 200/201} the reserve; {@code 402} nobody can pay (empty); {@code 409/404/400/422} a refusal
 * with the body's code; {@code 401/403/5xx}, no connection, or a second timeout: a {@link LedgerFault}. A read timeout
 * retries the SAME reference once: the road is idempotent by reference.
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

    /** @param environment the process environment by variable NAME ({@code System::getenv} in production) */
    public OrchestrixLedger(LedgerSettings settings, Function<String, String> environment, OwedJournal owed) {
        this.settings = settings;
        this.bearer = settings.bearer(environment);
        this.owed = owed;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.connectTimeoutMs())).build();
        log.info("ledger: orchestrix {} tenant {} profile {} (bearer from {}; connect {} ms, read {} ms); what cannot be returned is journaled in {}",
            settings.baseUrl(), settings.tenant(), settings.profile(), settings.tokenVar(), settings.connectTimeoutMs(), settings.readTimeoutMs(), owed.file());
    }

    @Override
    public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) {
        String account = billingAccountOf(level);
        HttpResponse<String> answer = post(settings.roads() + "/partners/" + account + "/charge", chargeOf(level, amount, reference), reference);
        int status = answer.statusCode();
        if (status == 200 || status == 201) return Optional.of(reservationOf(parse(answer.body(), reference)));
        if (status == 402) return cannotPay(level, amount, reference, answer);
        if (status == 409 || status == 404 || status == 400 || status == 422) throw new LedgerRefusal(errorCode(answer.body(), status), errorMessage(answer.body()));
        throw new LedgerFault("the ledger answered " + status + " to the reserve of partner " + level.getPartnerId() + " (ref " + reference + ")"
            + (status == 401 || status == 403 ? " — the bearer in " + settings.tokenVar() + " is refused" : ""));
    }

    @Override
    public TierSettlement settle(LevelAdmission level, BigDecimal charged) {
        BigDecimal toReturn = TierSettlement.reservedOf(level).subtract(charged);
        if (toReturn.signum() <= 0) return TierSettlement.of(level, charged, level.getBalanceAfter());
        owed.owe(level, toReturn, "settled at " + charged + ": the rest of the reserve");
        return TierSettlement.owed(level, charged, "the ledger has no return road: " + toReturn + " is journaled as owed");
    }

    @Override
    public void release(LevelAdmission level, String why) {
        owed.owe(level, TierSettlement.reservedOf(level), "released: " + why);
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

    /** One POST. A read timeout is retried ONCE with the same body (the same reference), then it is a fault. */
    private HttpResponse<String> post(String url, ObjectNode body, String reference) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(settings.readTimeoutMs()))
            .header("Authorization", "Bearer " + bearer)
            .header("X-Tenant-Id", settings.tenant())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();
        for (int attempt = 1; ; attempt++) {
            try {
                return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (HttpTimeoutException e) {
                if (attempt > 1) throw new LedgerFault("the ledger timed out twice on " + url + " (ref " + reference + ")", e);
                log.warn("ledger: {} timed out after {} ms (ref {}) — retrying the same reference once", url, settings.readTimeoutMs(), reference);
            } catch (ConnectException e) {
                throw new LedgerFault("the ledger is unreachable at " + settings.baseUrl() + ": " + e.getMessage(), e);
            } catch (IOException e) {
                throw new LedgerFault("the ledger call failed on " + url + " (ref " + reference + "): " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LedgerFault("interrupted while calling the ledger (ref " + reference + ")", e);
            }
        }
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
    private static String errorCode(String body, int status) {
        JsonNode n = lenient(body);
        if (n.path("error").hasNonNull("code")) return n.path("error").get("code").asText();
        if (n.hasNonNull("code")) return n.get("code").asText();
        return status == 409 ? "CONFLICT" : status == 404 ? "NOT_FOUND" : "VALIDATION";
    }

    private static String errorMessage(String body) {
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
