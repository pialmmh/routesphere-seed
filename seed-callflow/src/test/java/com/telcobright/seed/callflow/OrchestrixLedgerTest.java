package com.telcobright.seed.callflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.api.TierSettlement;
import com.telcobright.seed.callflow.dependencies.LedgerSettings;
import com.telcobright.seed.callflow.dependencies.Ledgers;
import com.telcobright.seed.callflow.spi.LedgerPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger port on orchestrix's prepaid roads, against a fake portal-api that serves the contract's shapes: the
 * reserve is road 16 on the partner's ACCOUNTING id; "cannot pay", a refusal and a fault are three different answers;
 * and what orchestrix cannot give back yet is written down as owed, never lost and never faked.
 */
class OrchestrixLedgerTest {

    record Seen(String path, String auth, String tenant, String body) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;
    HttpServer server;
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String answer = "{\"chargeAccount\":587,\"chargeUom\":\"BDT\",\"chargeUnits\":0,\"chargeBdt\":0.50,\"balanceBefore\":10.00,\"balanceAfter\":9.50}";
    final AtomicInteger slowCalls = new AtomicInteger();
    final Function<String, String> env = Map.of("PORTAL_API_TOKEN", "t0ken-not-a-secret")::get;

    @BeforeEach
    void up() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void down() { server.stop(0); }

    private void handle(HttpExchange x) throws IOException {
        String body;
        try (InputStream in = x.getRequestBody()) { body = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        seen.add(new Seen(x.getRequestURI().getPath(), x.getRequestHeaders().getFirst("Authorization"), x.getRequestHeaders().getFirst("X-Tenant-Id"), body));
        if (slowCalls.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            try { Thread.sleep(700); } catch (InterruptedException ignored) { }
        }
        byte[] out = answer.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, out.length);
        x.getResponseBody().write(out);
        x.close();
    }

    private Path owedFile() { return dir.resolve("owed-ledger.jsonl"); }

    private LedgerPort ledger() {
        LedgerSettings settings = new LedgerSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/", "PORTAL_API_TOKEN", "btcl", "ad-credit", 500, 300, "ad-sphere");
        return Ledgers.orchestrix(settings, env, owedFile(), Clock.fixed(Instant.parse("2026-10-03T04:00:00Z"), ZoneOffset.UTC));
    }

    /** The switch's partner 701 of res_44, linked to the accounting partner 9001. */
    private static LevelAdmission level(String billingAccountId) {
        Partner partner = new Partner();
        partner.setIdPartner(701);
        partner.setPartnerName("Unilever");
        partner.setBillingAccountId(billingAccountId);
        LevelAdmission level = new LevelAdmission(0, new Tenant("res_44"), partner, null);
        level.setUsageKind("video");
        level.setUsageSeconds(15);
        level.setReservedAmount(BigDecimal.ZERO);
        return level;
    }

    private static LevelAdmission reserved(String amount) {
        LevelAdmission level = level("9001");
        level.setDebitReference("ad-1#L0");
        level.setChargeAccountId(587L);
        level.setUom("BDT");
        level.setReservedAmount(new BigDecimal(amount));
        level.setBalanceAfter(new BigDecimal("9.50"));
        return level;
    }

    @Test
    void aReserveIsRoad16_onThePartnersAccountingId_withTheReferenceAsTheKey() throws Exception {
        Optional<LedgerPort.Reservation> held = ledger().reserve(level("9001"), new BigDecimal("0.50"), "ad-1#L0");

        assertThat(seen).hasSize(1);
        Seen call = seen.get(0);
        assertThat(call.path()).as("the accounting partner, never the switch's partner id 701").isEqualTo("/api/v1/prepaid/ad-credit/partners/9001/charge");
        assertThat(call.auth()).isEqualTo("Bearer t0ken-not-a-secret");
        assertThat(call.tenant()).isEqualTo("btcl");
        JsonNode body = JSON.readTree(call.body());
        assertThat(body.get("reference").asText()).isEqualTo("ad-1#L0");
        assertThat(body.at("/usage/kind").asText()).isEqualTo("video");
        assertThat(body.at("/usage/seconds").asInt()).isEqualTo(15);
        assertThat(body.at("/usage/rate/amount").decimalValue()).isEqualByComparingTo("0.50");
        assertThat(held).isPresent();
        assertThat(held.get().account()).isEqualTo(587L);
        assertThat(held.get().uom()).isEqualTo("BDT");
        assertThat(held.get().reserved()).isEqualByComparingTo("0.50");
        assertThat(held.get().balanceBefore()).isEqualByComparingTo("10.00");
        assertThat(held.get().balanceAfter()).isEqualByComparingTo("9.50");
        assertThat(held.get().repeated()).isFalse();
    }

    @Test
    void aUnitAccountAnswersUnits_andAReplayIsMarked() {
        answer = "{\"chargeAccount\":612,\"chargeUom\":\"AD_view\",\"chargeUnits\":1,\"chargeBdt\":0,\"balanceBefore\":40,\"balanceAfter\":39,\"repeated\":true}";

        LedgerPort.Reservation held = ledger().reserve(level("9001"), new BigDecimal("0.50"), "ad-2#L0").orElseThrow();

        assertThat(held.uom()).isEqualTo("AD_view");
        assertThat(held.reserved()).isEqualByComparingTo("1");
        assertThat(held.repeated()).isTrue();
    }

    @Test
    void cannotPay_aRefusal_andAFault_areThreeDifferentAnswers() {
        LedgerPort ledger = ledger();
        status = 402;
        answer = "{\"error\":{\"code\":\"INSUFFICIENT_BALANCE\",\"message\":\"nobody can pay\"}}";
        assertThat(ledger.reserve(level("9001"), new BigDecimal("1"), "ad-3#L0")).isEmpty();

        status = 409;
        answer = "{\"error\":{\"code\":\"PARTNER_INACTIVE\",\"message\":\"the partner is closed\"}}";
        assertThatThrownBy(() -> ledger.reserve(level("9001"), new BigDecimal("1"), "ad-4#L0"))
            .isInstanceOf(LedgerPort.LedgerRefusal.class).extracting(e -> ((LedgerPort.LedgerRefusal) e).code()).isEqualTo("PARTNER_INACTIVE");

        status = 500;
        assertThatThrownBy(() -> ledger.reserve(level("9001"), new BigDecimal("1"), "ad-5#L0")).isInstanceOf(LedgerPort.LedgerFault.class);

        status = 401;
        assertThatThrownBy(() -> ledger.reserve(level("9001"), new BigDecimal("1"), "ad-6#L0"))
            .isInstanceOf(LedgerPort.LedgerFault.class).hasMessageContaining("PORTAL_API_TOKEN");
    }

    @Test
    void aPartnerWithNoAccountingPartnerIsRefused_andNothingIsSent() {
        assertThatThrownBy(() -> ledger().reserve(level(null), new BigDecimal("0.50"), "ad-7#L0"))
            .isInstanceOf(LedgerPort.LedgerRefusal.class).extracting(e -> ((LedgerPort.LedgerRefusal) e).code()).isEqualTo("NO_BILLING_ACCOUNT");

        assertThat(seen).isEmpty();
    }

    @Test
    void aTimeoutRetriesTheSameReferenceOnce_thenItIsAFault() {
        LedgerPort ledger = ledger();
        slowCalls.set(1);
        assertThat(ledger.reserve(level("9001"), new BigDecimal("0.50"), "ad-8#L0")).isPresent();
        assertThat(seen).hasSize(2);
        assertThat(seen.get(1).body()).as("the retry is the same request").isEqualTo(seen.get(0).body());

        slowCalls.set(2);
        assertThatThrownBy(() -> ledger.reserve(level("9001"), new BigDecimal("0.50"), "ad-9#L0"))
            .isInstanceOf(LedgerPort.LedgerFault.class).hasMessageContaining("timed out twice");
    }

    @Test
    void anUnreachableLedgerIsAFault_neverABalanceAnswer() {
        LedgerPort ledger = ledger();
        server.stop(0);

        assertThatThrownBy(() -> ledger.reserve(level("9001"), new BigDecimal("0.50"), "ad-10#L0")).isInstanceOf(LedgerPort.LedgerFault.class);
    }

    @Test
    void aTierThatPaysAllItReservedSettlesWithNoCall() {
        TierSettlement settlement = ledger().settle(reserved("0.50"), new BigDecimal("0.50"));

        assertThat(seen).isEmpty();
        assertThat(settlement.closed()).isTrue();
        assertThat(settlement.charged()).isEqualByComparingTo("0.50");
        assertThat(settlement.returned()).isEqualByComparingTo("0");
        assertThat(owedFile()).doesNotExist();
    }

    @Test
    void whatMustGoBackIsWrittenDownAsOwed_notSentToAnOfficersRoad_andTheSettlementSaysSo() throws Exception {
        LedgerPort ledger = ledger();

        TierSettlement settlement = ledger.settle(reserved("0.50"), BigDecimal.ZERO);
        ledger.release(reserved("0.40"), "INSUFFICIENT_BALANCE");

        assertThat(seen).as("no road is called: road 15 is an officer's").isEmpty();
        assertThat(settlement.closed()).isFalse();
        assertThat(settlement.charged()).isEqualByComparingTo("0");
        List<String> lines = Files.readAllLines(owedFile());
        assertThat(lines).hasSize(2);
        JsonNode first = JSON.readTree(lines.get(0));
        assertThat(first.get("at").asText()).isEqualTo("2026-10-03T04:00:00Z");
        assertThat(first.get("reference").asText()).isEqualTo("ad-1#L0");
        assertThat(first.get("tenant").asText()).isEqualTo("res_44");
        assertThat(first.get("partnerId").asInt()).isEqualTo(701);
        assertThat(first.get("account").asLong()).isEqualTo(587L);
        assertThat(first.get("amount").decimalValue()).isEqualByComparingTo("0.50");
        assertThat(first.get("uom").asText()).isEqualTo("BDT");
        JsonNode second = JSON.readTree(lines.get(1));
        assertThat(second.get("amount").decimalValue()).isEqualByComparingTo("0.40");
        assertThat(second.get("why").asText()).contains("INSUFFICIENT_BALANCE");
    }
}
