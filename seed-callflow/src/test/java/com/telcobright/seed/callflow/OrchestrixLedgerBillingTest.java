package com.telcobright.seed.callflow;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.api.LevelCharge;
import com.telcobright.seed.callflow.dependencies.LedgerSettings;
import com.telcobright.seed.callflow.internal.OrchestrixLedgerBilling;
import com.telcobright.seed.callflow.spi.AdBillingPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Road 16 and road 15 against a fake portal-api serving the contract page's shapes (the shape of ad-sphere's LedgerTest):
 * the headers, the body, the answer's keys verbatim; 402 → empty; 409/404/400 → the body's code; 401/403/5xx/no connection →
 * BILLING_SYSTEM_ERROR; a timeout retries the SAME reference once, then fails closed. Never a fake success.
 */
class OrchestrixLedgerBillingTest {

    record Seen(String path, String auth, String tenant, String body) {}

    HttpServer server;
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String answer = "{\"chargeAccount\":587,\"chargeUom\":\"BDT\",\"chargeUnits\":0,\"chargeBdt\":0.50,\"balanceBefore\":10.00,\"balanceAfter\":9.50}";
    final AtomicInteger slowCalls = new AtomicInteger();          // the first N requests sleep past the read timeout
    final Function<String, String> env = Map.of("PORTAL_API_TOKEN", "t0ken-not-a-secret")::get;

    @BeforeEach
    void up() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());   // a slow call must not queue the retry behind it
        server.start();
    }

    @AfterEach void down() { server.stop(0); }

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

    private LedgerSettings settings() {
        return new LedgerSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/", "PORTAL_API_TOKEN", "btcl", "ad-credit", 500, 300, "ad-sphere");
    }

    static LevelAdmission level(int partner) {
        Tenant t = new Tenant("res_44");
        Partner p = new Partner();
        p.setIdPartner(partner);
        p.setPartnerName("Unilever");
        LevelAdmission l = new LevelAdmission(0, t, p, null);
        l.setUsageKind("video");
        l.setUsageSeconds(15);
        return l;
    }

    @Test
    void the_debit_speaks_road_16_with_the_bearer_by_name_the_tenant_and_the_reference_and_reads_the_answer_verbatim() {
        OrchestrixLedgerBilling ledger = new OrchestrixLedgerBilling(settings(), env);
        Optional<LevelCharge> c = ledger.debit(level(701), new BigDecimal("0.50"), "ad-1#L0");
        assertThat(c).isPresent();
        assertThat(c.get().chargeAccount()).isEqualTo(587L);
        assertThat(c.get().chargeUom()).isEqualTo("BDT");
        assertThat(c.get().chargeBdt()).isEqualByComparingTo("0.50");
        assertThat(c.get().amount()).isEqualByComparingTo("0.50");
        assertThat(c.get().balanceBefore()).isEqualByComparingTo("10.00");
        assertThat(c.get().balanceAfter()).isEqualByComparingTo("9.50");
        assertThat(c.get().repeated()).isFalse();
        assertThat(c.get().reference()).isEqualTo("ad-1#L0");
        Seen s = seen.get(0);
        assertThat(s.path()).isEqualTo("/api/v1/prepaid/ad-credit/partners/701/charge");
        assertThat(s.auth()).isEqualTo("Bearer t0ken-not-a-secret");
        assertThat(s.tenant()).isEqualTo("btcl");
        assertThat(s.body()).contains("\"kind\":\"video\"").contains("\"seconds\":15").contains("\"amount\":0.50").contains("\"unit\":\"view\"").contains("\"reference\":\"ad-1#L0\"");
    }

    @Test
    void a_replay_carries_repeated_and_402_means_this_payer_cannot_pay() {
        OrchestrixLedgerBilling ledger = new OrchestrixLedgerBilling(settings(), env);
        answer = "{\"chargeAccount\":587,\"chargeUom\":\"AD_view\",\"chargeUnits\":1,\"chargeBdt\":0,\"balanceBefore\":5,\"balanceAfter\":4,\"repeated\":true}";
        LevelCharge c = ledger.debit(level(701), BigDecimal.ONE, "ad-1#L0").orElseThrow();
        assertThat(c.repeated()).isTrue();
        assertThat(c.amount()).as("a unit bucket: the units are the amount").isEqualByComparingTo("1");
        status = 402;
        answer = "{\"error\":{\"code\":\"NO_BALANCE\",\"message\":\"nobody can pay\"}}";
        assertThat(ledger.debit(level(701), BigDecimal.ONE, "ad-2#L0")).isEmpty();
    }

    @Test
    void a_409_404_400_is_a_refusal_with_the_bodys_code_never_a_balance_cause() {
        OrchestrixLedgerBilling ledger = new OrchestrixLedgerBilling(settings(), env);
        status = 409;
        answer = "{\"error\":{\"code\":\"PARTNER_INACTIVE\",\"message\":\"partner 701 is inactive\"}}";
        assertThatThrownBy(() -> ledger.debit(level(701), BigDecimal.ONE, "ad-3#L0"))
            .isInstanceOf(AdBillingPort.LedgerRefusal.class).extracting(e -> ((AdBillingPort.LedgerRefusal) e).code()).isEqualTo("PARTNER_INACTIVE");
        status = 404;
        answer = "no such partner";
        assertThatThrownBy(() -> ledger.debit(level(4242), BigDecimal.ONE, "ad-4#L0"))
            .isInstanceOf(AdBillingPort.LedgerRefusal.class).extracting(e -> ((AdBillingPort.LedgerRefusal) e).code()).isEqualTo("NOT_FOUND");
        status = 400;
        answer = "{\"error\":{\"code\":\"VALIDATION\",\"message\":\"usage.kind\"}}";
        assertThatThrownBy(() -> ledger.debit(level(701), BigDecimal.ONE, "ad-5#L0")).isInstanceOf(AdBillingPort.LedgerRefusal.class);
    }

    @Test
    void a_401_403_or_5xx_is_the_ledgers_fault_named_after_the_token_variable_when_it_is_the_bearer() {
        OrchestrixLedgerBilling ledger = new OrchestrixLedgerBilling(settings(), env);
        status = 401;
        answer = "{\"error\":{\"code\":\"UNAUTHORIZED\"}}";
        assertThatThrownBy(() -> ledger.debit(level(701), BigDecimal.ONE, "ad-6#L0"))
            .isInstanceOf(AdBillingPort.BillingSystemFault.class).hasMessageContaining("PORTAL_API_TOKEN").hasMessageContaining("401");
        status = 503;
        answer = "{\"error\":{\"code\":\"LEDGER_UNAVAILABLE\"}}";
        assertThatThrownBy(() -> ledger.debit(level(701), BigDecimal.ONE, "ad-7#L0")).isInstanceOf(AdBillingPort.BillingSystemFault.class).hasMessageContaining("503");
    }

    @Test
    void a_timeout_retries_the_same_reference_once_and_a_second_timeout_fails_closed() {
        OrchestrixLedgerBilling ledger = new OrchestrixLedgerBilling(settings(), env);
        slowCalls.set(1);
        LevelCharge c = ledger.debit(level(701), new BigDecimal("0.50"), "ad-8#L0").orElseThrow();
        assertThat(c.chargeAccount()).isEqualTo(587L);
        assertThat(seen).hasSize(2);
        assertThat(seen.get(0).body()).isEqualTo(seen.get(1).body());
        assertThat(seen.get(1).body()).contains("\"reference\":\"ad-8#L0\"");
        seen.clear();
        slowCalls.set(2);
        assertThatThrownBy(() -> ledger.debit(level(701), new BigDecimal("0.50"), "ad-9#L0"))
            .isInstanceOf(AdBillingPort.BillingSystemFault.class).hasMessageContaining("twice");
        assertThat(seen).as("exactly two attempts, never a third").hasSize(2);
    }

    @Test
    void no_connection_is_the_ledgers_fault_and_an_empty_token_variable_refuses_the_start_by_name() {
        server.stop(0);
        int dead = server.getAddress().getPort();
        LedgerSettings s = new LedgerSettings("http://127.0.0.1:" + dead, "PORTAL_API_TOKEN", "btcl", "ad-credit", 300, 300, "ad-sphere");
        OrchestrixLedgerBilling ledger = new OrchestrixLedgerBilling(s, env);
        assertThatThrownBy(() -> ledger.debit(level(701), BigDecimal.ONE, "ad-10#L0")).isInstanceOf(AdBillingPort.BillingSystemFault.class);
        assertThatThrownBy(() -> new OrchestrixLedgerBilling(s, name -> null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("PORTAL_API_TOKEN");
        assertThatThrownBy(() -> new LedgerSettings("http://x", "", "btcl", null, 0, 0, null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("tokenVar");
    }

    @Test
    void the_credit_speaks_road_15_and_answers_the_balance() {
        OrchestrixLedgerBilling ledger = new OrchestrixLedgerBilling(settings(), env);
        status = 201;
        answer = "{\"credit\":{\"accountId\":587,\"amount\":0.50,\"balance\":10.00,\"by\":\"ad-sphere\"}}";
        LevelCharge c = new LevelCharge(0, "res_44", 701, 587L, "BDT", null, new BigDecimal("0.50"), new BigDecimal("10.00"), new BigDecimal("9.50"), "ad-1#L0", false);
        assertThat(ledger.credit(c, "ad-1#L0#C", "compensation:INSUFFICIENT_BALANCE").orElseThrow()).isEqualByComparingTo("10.00");
        Seen s = seen.get(0);
        assertThat(s.path()).isEqualTo("/api/v1/prepaid/ad-credit/partners/701/credit");
        assertThat(s.body()).contains("\"accountId\":587").contains("\"amount\":0.50").contains("compensation:INSUFFICIENT_BALANCE").contains("ad-1#L0#C").contains("\"by\":\"ad-sphere\"");
        status = 404;
        answer = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"not this partner's account\"}}";
        assertThat(ledger.credit(c, "ad-1#L0#C2", "x")).isEmpty();
        status = 500;
        assertThatThrownBy(() -> ledger.credit(c, "ad-1#L0#C3", "x")).isInstanceOf(AdBillingPort.BillingSystemFault.class);
    }
}
