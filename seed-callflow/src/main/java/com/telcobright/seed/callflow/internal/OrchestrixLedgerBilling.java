package com.telcobright.seed.callflow.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.callflow.api.LevelCharge;
import com.telcobright.seed.callflow.dependencies.LedgerSettings;
import com.telcobright.seed.callflow.spi.AdBillingPort;
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
 * The {@link AdBillingPort} on orchestrix portal-api's prepaid roads by the consumer contract
 * ({@code docs/ad-manager/design/prepaid-roads-consumer-contract.md}): road 16 DEBIT
 * ({@code POST …/partners/{pid}/charge}, the usage carries the rated amount, the reference is the idempotency key) and
 * road 15 CREDIT ({@code POST …/partners/{pid}/credit}).
 *
 * <ul>
 *   <li>{@code Authorization: Bearer <the value of the environment variable NAMED in the settings>} (S20), {@code X-Tenant-Id}.</li>
 *   <li>Connect AND read timeouts. A read timeout retries the SAME reference exactly once (the road is idempotent by
 *       reference, so a debit that landed answers {@code repeated: true}); a second timeout fails closed as
 *       {@link BillingSystemFault} — never a fake success, never a customer cause.</li>
 *   <li>{@code 402} → empty (nobody can pay: the next payer is tried); {@code 409 / 404 / 400} → {@link LedgerRefusal} with
 *       the body's {@code error.code}; {@code 401 / 403 / 5xx / no connection} → {@link BillingSystemFault}.</li>
 * </ul>
 
 *
 * @deprecated The ad-only shape of 2026-09-29. Since the base call pipeline (2026-10-03): it becomes an adapter of {@link com.telcobright.seed.callflow.spi.LedgerPort} (reserve, settle, release).
 *     Removed when ad-sphere has moved onto {@code CallFlow}.
 */
@Deprecated(since = "2026-10-03", forRemoval = true)
public final class OrchestrixLedgerBilling implements AdBillingPort {

    private static final Logger log = LoggerFactory.getLogger(OrchestrixLedgerBilling.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LedgerSettings settings;
    private final String bearer;
    private final HttpClient http;

    /** @param environment the process environment by variable NAME ({@code System::getenv} in production) */
    public OrchestrixLedgerBilling(LedgerSettings settings, Function<String, String> environment) {
        this(settings, environment, HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.connectTimeoutMs())).build());
    }

    OrchestrixLedgerBilling(LedgerSettings settings, Function<String, String> environment, HttpClient http) {
        this.settings = settings;
        this.bearer = settings.bearer(environment);
        this.http = http;
        log.info("ledger: orchestrix {} tenant {} profile {} (bearer from {}; connect {} ms, read {} ms)", settings.baseUrl(), settings.tenant(),
            settings.profile(), settings.tokenVar(), settings.connectTimeoutMs(), settings.readTimeoutMs());
    }

    @Override
    public Optional<LevelCharge> debit(LevelAdmission level, BigDecimal amount, String reference) {
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
        HttpResponse<String> r = post(settings.roads() + "/partners/" + level.getPartnerId() + "/charge", body, reference);
        int status = r.statusCode();
        if (status == 200 || status == 201) {
            JsonNode n = parse(r.body(), reference);
            return Optional.of(new LevelCharge(level.getLevelIndex(), level.getDbName(), level.getPartnerId(),
                longOf(n, "chargeAccount"), text(n, "chargeUom"), decimal(n, "chargeUnits"), decimal(n, "chargeBdt"),
                decimal(n, "balanceBefore"), decimal(n, "balanceAfter"), reference, n.path("repeated").asBoolean(false)));
        }
        if (status == 402) {
            log.info("ledger: partner {} cannot pay {} (ref {}): {}", level.getPartnerId(), amount, reference, errorMessage(r.body()));
            return Optional.empty();
        }
        if (status == 409 || status == 404 || status == 400 || status == 422) {
            throw new LedgerRefusal(errorCode(r.body(), status), errorMessage(r.body()));
        }
        throw new BillingSystemFault("ledger answered " + status + " to the debit of partner " + level.getPartnerId() + " (ref " + reference + ")"
            + (status == 401 || status == 403 ? " — the bearer in " + settings.tokenVar() + " is refused" : ""));
    }

    @Override
    public Optional<BigDecimal> credit(LevelCharge charge, String reference, String reason) {
        if (charge.chargeAccount() == null) {
            log.warn("ledger: no account to credit for partner {} ({}) — nothing sent", charge.partnerId(), reference);
            return Optional.empty();
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("accountId", charge.chargeAccount());
        body.put("amount", charge.amount());
        body.put("reason", reason + " [" + reference + "]");
        body.put("by", settings.by());
        HttpResponse<String> r = post(settings.roads() + "/partners/" + charge.partnerId() + "/credit", body, reference);
        int status = r.statusCode();
        if (status == 200 || status == 201) {
            JsonNode n = parse(r.body(), reference);
            JsonNode c = n.has("credit") ? n.get("credit") : n;
            return Optional.ofNullable(decimal(c, "balance"));
        }
        if (status == 409 || status == 404 || status == 400 || status == 422) {
            log.error("ledger: the credit of {} on account {} (partner {}, {}) was refused {}: {}", charge.amount(), charge.chargeAccount(), charge.partnerId(),
                reference, status, errorMessage(r.body()));
            return Optional.empty();
        }
        throw new BillingSystemFault("ledger answered " + status + " to the credit of partner " + charge.partnerId() + " (" + reference + ")");
    }

    /** One POST; a read timeout is retried ONCE with the same body (same reference), then fails closed. */
    private HttpResponse<String> post(String url, ObjectNode body, String reference) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(settings.readTimeoutMs()))
            .header("Authorization", "Bearer " + bearer)
            .header("X-Tenant-Id", settings.tenant())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();
        for (int attempt = 1; ; attempt++) {
            try {
                return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (HttpTimeoutException e) {
                if (attempt == 1) {
                    log.warn("ledger: {} timed out after {} ms (ref {}) — retrying the same reference once", url, settings.readTimeoutMs(), reference);
                    continue;
                }
                throw new BillingSystemFault("ledger timed out twice on " + url + " (ref " + reference + ")", e);
            } catch (ConnectException e) {
                throw new BillingSystemFault("ledger unreachable at " + settings.baseUrl() + ": " + e.getMessage(), e);
            } catch (IOException e) {
                throw new BillingSystemFault("ledger call failed on " + url + " (ref " + reference + "): " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BillingSystemFault("interrupted while calling the ledger (ref " + reference + ")", e);
            }
        }
    }

    private static JsonNode parse(String body, String reference) {
        try {
            return JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            throw new BillingSystemFault("the ledger's answer is not JSON (ref " + reference + "): " + e.getMessage(), e);
        }
    }

    /** The error envelope {@code { error: { code, message } }} (RULED); {@code message} / {@code reason} read as a fallback, never as the contract. */
    private static String errorCode(String body, int status) {
        try {
            JsonNode n = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
            JsonNode err = n.path("error");
            if (err.hasNonNull("code")) return err.get("code").asText();
            if (n.hasNonNull("code")) return n.get("code").asText();
        } catch (IOException ignored) { }
        return status == 409 ? "CONFLICT" : status == 404 ? "NOT_FOUND" : "VALIDATION";
    }

    private static String errorMessage(String body) {
        try {
            JsonNode n = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
            JsonNode err = n.path("error");
            if (err.hasNonNull("message")) return err.get("message").asText();
            if (n.hasNonNull("message")) return n.get("message").asText();
            if (n.hasNonNull("reason")) return n.get("reason").asText();
        } catch (IOException ignored) { }
        return body == null ? "" : body;
    }

    private static Long longOf(JsonNode n, String f) { return n.hasNonNull(f) ? n.get(f).asLong() : null; }
    private static String text(JsonNode n, String f) { return n.hasNonNull(f) ? n.get(f).asText() : null; }
    private static BigDecimal decimal(JsonNode n, String f) { return n.hasNonNull(f) ? new BigDecimal(n.get(f).asText()) : null; }
}
