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
import com.telcobright.seed.callflow.dependencies.ReturnPace;
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
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A reserve that must go back, on orchestrix's return road ({@code POST …/partners/{pid}/charge/return}), against a fake
 * portal-api that answers the road's contract: the charge's own reference, idempotent; "nothing was charged" closes the
 * story; "an officer must decide" is written once and never asked again; a road that is still answering the charge, is
 * down or says nothing is asked again and then owed; an orchestrix without the road owes as before. A return never
 * holds a call, and what was still being asked when the process stopped is asked again at the next start.
 */
class OrchestrixReturnRoadTest {

    record Seen(String path, String auth, String tenant, String body) {}
    record Canned(int status, String body) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RETURN_ROAD = "/api/v1/prepaid/ad-credit/partners/9001/charge/return";
    private static final String CHARGED = "{\"chargeAccount\":587,\"chargeUom\":\"BDT\",\"chargeUnits\":0,\"chargeBdt\":0.50,\"balanceBefore\":10.00,\"balanceAfter\":9.50}";
    private static final Canned RETURNED = new Canned(200, "{\"account\":587,\"uom\":\"BDT\",\"units\":0,\"bdt\":0.50,\"balanceBefore\":9.50,\"balanceAfter\":10.00}");
    private static final Canned NO_SUCH_CHARGE = new Canned(404, "{\"error\":{\"code\":\"NO_SUCH_CHARGE\",\"message\":\"nothing was charged under this reference — nothing to return\"}}");
    private static final Canned NEEDS_AN_OFFICER = new Canned(409, "{\"error\":{\"code\":\"RETURN_NEEDS_AN_OFFICER\",\"message\":\"the purchase of this bucket was cancelled: an officer decides\"}}");
    private static final Canned STILL_ANSWERING = new Canned(409, "{\"error\":{\"code\":\"CONFLICT\",\"message\":\"ask again with the same reference\"}}");
    private static final Canned DOWN = new Canned(503, "upstream is down");
    private static final Canned NO_ROAD = new Canned(404, "");
    /** Asked again after 30 ms; an unsure reserve's "nothing charged" asked once more after 60 ms. */
    private static final ReturnPace QUICK = new ReturnPace(3, 30, 30, 60, 100);

    @TempDir Path dir;
    HttpServer server;
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    /** The return road's next answers, in order; when empty, {@link #returnAnswer}. */
    final Deque<Canned> returnAnswers = new ConcurrentLinkedDeque<>();
    volatile Canned returnAnswer = RETURNED;
    volatile long returnDelayMs;
    final AtomicInteger slowCharges = new AtomicInteger();
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
        String path = x.getRequestURI().getPath();
        seen.add(new Seen(path, x.getRequestHeaders().getFirst("Authorization"), x.getRequestHeaders().getFirst("X-Tenant-Id"), body));
        Canned canned;
        if (path.endsWith("/charge/return")) {
            pause(returnDelayMs);
            Canned next = returnAnswers.poll();
            canned = next != null ? next : returnAnswer;
        } else {
            if (slowCharges.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) pause(700);
            canned = new Canned(200, CHARGED);
        }
        byte[] out = canned.body().getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(canned.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) x.getResponseBody().write(out);
        x.close();
    }

    private static void pause(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private Path journal() { return dir.resolve("owed-ledger.jsonl"); }

    /** The ledger with a read timeout of 300 ms and the quick pace. */
    private LedgerPort ledger() { return ledger(QUICK); }

    private LedgerPort ledger(ReturnPace pace) { return ledger(pace, 300); }

    private LedgerPort ledger(ReturnPace pace, long readTimeoutMs) {
        LedgerSettings settings = new LedgerSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/", "PORTAL_API_TOKEN", "btcl", "ad-credit", 500, readTimeoutMs, "ad-sphere");
        return Ledgers.orchestrix(settings, env, journal(), Clock.fixed(Instant.parse("2026-10-04T04:00:00Z"), ZoneOffset.UTC), pace);
    }

    /** The switch's partner 701 of res_44 (accounting partner 9001) holding ONE reserve of {@code amount} under ad-1#L0. */
    private static LevelAdmission reserved(String amount) { return reserved(amount, "9001"); }

    private static LevelAdmission reserved(String amount, String billingAccountId) {
        Partner partner = new Partner();
        partner.setIdPartner(701);
        partner.setPartnerName("Unilever");
        partner.setBillingAccountId(billingAccountId);
        LevelAdmission level = new LevelAdmission(0, new Tenant("res_44"), partner, null);
        level.setUsageKind("video");
        level.setUsageSeconds(15);
        level.setDebitReference("ad-1#L0");
        level.setChargeAccountId(587L);
        level.setUom("BDT");
        level.setReservedAmount(new BigDecimal(amount));
        level.setBalanceAfter(new BigDecimal("9.50"));
        return level;
    }

    private List<Seen> returnsAsked() { return seen.stream().filter(s -> s.path().endsWith("/charge/return")).toList(); }

    private List<JsonNode> lines() throws IOException {
        if (!Files.isRegularFile(journal())) return List.of();
        return Files.readAllLines(journal()).stream().filter(l -> !l.isBlank()).map(OrchestrixReturnRoadTest::json).toList();
    }

    private List<String> kinds() throws IOException { return lines().stream().map(l -> l.get("kind").asText()).toList(); }

    private static JsonNode json(String text) {
        try { return JSON.readTree(text); } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static void await(String what, BooleanSupplier holds) {
        long until = System.currentTimeMillis() + 5_000;
        while (!holds.getAsBoolean()) {
            if (System.currentTimeMillis() > until) throw new AssertionError("still not after 5 s: " + what);
            pause(10);
        }
    }

    private void awaitKinds(String... kinds) {
        await("the journal's kinds " + List.of(kinds), () -> {
            try { return kinds().equals(List.of(kinds)); } catch (IOException e) { return false; }
        });
    }

    // ── a refused candidate's reserve: release ──────────────────────────────

    @Test
    void aReleasedReserveGoesBackByTheReturnRoad_withTheChargesOwnReference_onTheAccountingPartner() throws Exception {
        ledger().release(reserved("0.40"), "INSUFFICIENT_BALANCE");

        awaitKinds("returning", "returned");
        assertThat(returnsAsked()).hasSize(1);
        Seen asked = returnsAsked().get(0);
        assertThat(asked.path()).as("the ACCOUNTING partner, never the switch's own id 701").isEqualTo(RETURN_ROAD);
        assertThat(asked.auth()).isEqualTo("Bearer t0ken-not-a-secret");
        assertThat(asked.tenant()).isEqualTo("btcl");
        assertThat(JSON.readTree(asked.body()).get("reference").asText()).as("the charge's reference, as road 16 was sent it").isEqualTo("ad-1#L0");
        assertThat(seen).as("never an officer's road").noneMatch(s -> s.path().endsWith("/credit"));

        JsonNode first = lines().get(0);
        assertThat(first.get("reference").asText()).isEqualTo("ad-1#L0");
        assertThat(first.get("billingAccount").asText()).isEqualTo("9001");
        assertThat(first.get("partnerId").asInt()).isEqualTo(701);
        assertThat(first.get("tenant").asText()).isEqualTo("res_44");
        assertThat(first.get("amount").decimalValue()).isEqualByComparingTo("0.40");
        assertThat(first.get("why").asText()).contains("INSUFFICIENT_BALANCE");
    }

    @Test
    void aReleaseNeverWaitsForTheRoad() throws Exception {
        returnDelayMs = 1500;                                             // the road answers a second and a half later
        LedgerPort ledger = ledger(QUICK, 4000);
        long startedMs = System.currentTimeMillis();

        ledger.release(reserved("0.40"), "ROUTE_NOT_FOUND");

        assertThat(System.currentTimeMillis() - startedMs).as("a refused candidate does not wait for the ledger").isLessThan(1000);
        assertThat(kinds()).as("the call goes on while the road is still answering").containsExactly("returning");
        awaitKinds("returning", "returned");
    }

    @Test
    void aTierThatReservedNothing_hasNothingToGiveBack() throws Exception {
        ledger().release(reserved("0"), "INSUFFICIENT_BALANCE");

        pause(120);
        assertThat(returnsAsked()).isEmpty();
        assertThat(lines()).isEmpty();
    }

    // ── what the road answers ───────────────────────────────────────────────

    @Test
    void nothingWasChargedUnderTheReference_closesTheStory_andNothingIsOwed() throws Exception {
        returnAnswer = NO_SUCH_CHARGE;

        ledger().release(reserved("0.40"), "INSUFFICIENT_BALANCE");

        awaitKinds("returning", "not-charged");
        assertThat(returnsAsked()).as("a reserve that the tier held is believed at once").hasSize(1);
    }

    @Test
    void aReturnThatNeedsAnOfficer_isWrittenOnce_andNeverAskedAgain() throws Exception {
        returnAnswer = NEEDS_AN_OFFICER;

        ledger().release(reserved("0.40"), "INSUFFICIENT_BALANCE");

        awaitKinds("returning", "officer");
        pause(150);                                                       // longer than every wait of the quick pace
        assertThat(returnsAsked()).hasSize(1);
        assertThat(lines().get(1).get("why").asText()).contains("an officer decides");
    }

    @Test
    void aRoadThatIsStillAnsweringTheCharge_isAskedAgainWithTheSameReference() throws Exception {
        returnAnswers.add(STILL_ANSWERING);

        ledger().release(reserved("0.40"), "INSUFFICIENT_BALANCE");

        awaitKinds("returning", "returned");
        assertThat(returnsAsked()).hasSize(2);
        assertThat(returnsAsked().get(1).body()).as("the same reference: the road is idempotent by it").isEqualTo(returnsAsked().get(0).body());
    }

    @Test
    void aRoadThatNeverTakesIt_isAskedAsOftenAsThePaceSays_thenTheReserveIsOwed() throws Exception {
        returnAnswer = DOWN;

        ledger().release(reserved("0.40"), "INSUFFICIENT_BALANCE");

        awaitKinds("returning", "owed");
        assertThat(returnsAsked()).hasSize(3);
        assertThat(lines().get(1).get("why").asText()).contains("after 3 ask(s)").contains("503");
        assertThat(lines().get(1).get("amount").decimalValue()).isEqualByComparingTo("0.40");
    }

    @Test
    void anOrchestrixWithoutTheReturnRoad_owesAsBefore_andIsNotAskedAgain() throws Exception {
        returnAnswer = NO_ROAD;

        ledger().release(reserved("0.40"), "INSUFFICIENT_BALANCE");

        awaitKinds("returning", "owed");
        pause(150);
        assertThat(returnsAsked()).hasSize(1);
        assertThat(lines().get(1).get("why").asText()).contains("no return road");
    }

    @Test
    void aRefusedCredential_isOwed_andNamesTheVariableNeverTheValue() throws Exception {
        returnAnswer = new Canned(401, "");

        ledger().release(reserved("0.40"), "INSUFFICIENT_BALANCE");

        awaitKinds("returning", "owed");
        assertThat(returnsAsked()).hasSize(1);
        String why = lines().get(1).get("why").asText();
        assertThat(why).contains("PORTAL_API_TOKEN").doesNotContain("t0ken-not-a-secret");
    }

    @Test
    void aPartnerWithNoAccountingPartner_cannotBeAsked_itIsOwed() throws Exception {
        ledger().release(reserved("0.40", null), "INSUFFICIENT_BALANCE");

        awaitKinds("owed");
        assertThat(returnsAsked()).isEmpty();
        assertThat(lines().get(0).get("why").asText()).contains("no accounting partner");
    }

    // ── the end of a call: settle ───────────────────────────────────────────

    @Test
    void aTierThatPaysNothing_isSettledByOneReturn_andTheSettlementSaysTheBalanceAfter() throws Exception {
        TierSettlement settlement = ledger().settle(reserved("0.50"), BigDecimal.ZERO);

        assertThat(settlement.closed()).isTrue();
        assertThat(settlement.charged()).isEqualByComparingTo("0");
        assertThat(settlement.returned()).isEqualByComparingTo("0.50");
        assertThat(settlement.balanceAfter()).as("the bucket's balance after the return, as the road said it").isEqualByComparingTo("10.00");
        assertThat(returnsAsked()).hasSize(1);
        assertThat(lines()).as("a return that went back at once leaves no line").isEmpty();
    }

    @Test
    void aTierThatPaysAPart_cannotGoBackByTheRoad_theRestIsOwed() throws Exception {
        TierSettlement settlement = ledger().settle(reserved("0.50"), new BigDecimal("0.20"));

        assertThat(settlement.closed()).isFalse();
        assertThat(settlement.charged()).isEqualByComparingTo("0.20");
        assertThat(returnsAsked()).as("the road gives back a whole charge by its reference, never a part").isEmpty();
        assertThat(kinds()).containsExactly("owed");
        assertThat(lines().get(0).get("amount").decimalValue()).isEqualByComparingTo("0.30");
    }

    @Test
    void aSettlementOnARoadThatDoesNotTakeItNow_saysItIsNotClosed_andTheWorkerFinishesIt() throws Exception {
        returnAnswers.add(DOWN);

        TierSettlement settlement = ledger().settle(reserved("0.50"), BigDecimal.ZERO);

        assertThat(settlement.closed()).as("the settlement says what is true at that moment").isFalse();
        assertThat(settlement.note()).contains("did not go back at the settlement");
        awaitKinds("returning", "returned");
        assertThat(returnsAsked()).hasSize(2);
    }

    @Test
    void aSettlementThatNeedsAnOfficer_isNotClosed_andIsNeverAskedAgain() throws Exception {
        returnAnswer = NEEDS_AN_OFFICER;

        TierSettlement settlement = ledger().settle(reserved("0.50"), BigDecimal.ZERO);

        assertThat(settlement.closed()).isFalse();
        assertThat(kinds()).containsExactly("officer");
        pause(150);
        assertThat(returnsAsked()).hasSize(1);
    }

    // ── a reserve that got no answer ────────────────────────────────────────

    @Test
    void aReserveThatGotNoAnswer_isAskedBack_andMoneyThatMovedComesBack() throws Exception {
        LedgerPort ledger = ledger();
        slowCharges.set(2);

        assertThatThrownBy(() -> ledger.reserve(reserved("0"), new BigDecimal("0.50"), "ad-13#L0", 3000)).isInstanceOf(LedgerPort.LedgerFault.class);

        awaitKinds("unsure", "returning", "returned");
        assertThat(JSON.readTree(returnsAsked().get(0).body()).get("reference").asText()).isEqualTo("ad-13#L0");
        assertThat(lines().get(1).get("amount").decimalValue()).isEqualByComparingTo("0.50");
    }

    @Test
    void anUnansweredReserve_notChargedWhenAsked_isAskedOnceMoreBeforeItIsBelieved() throws Exception {
        returnAnswer = NO_SUCH_CHARGE;
        LedgerPort ledger = ledger();
        slowCharges.set(2);

        assertThatThrownBy(() -> ledger.reserve(reserved("0"), new BigDecimal("0.50"), "ad-13#L0", 3000)).isInstanceOf(LedgerPort.LedgerFault.class);

        awaitKinds("unsure", "returning", "not-charged");
        assertThat(returnsAsked()).as("a charge that got no answer may still land after the first 'nothing charged'").hasSize(2);
    }

    @Test
    void anUnansweredReserve_whoseChargeLandsLate_isGivenBackAtTheSecondAsk() throws Exception {
        returnAnswers.add(NO_SUCH_CHARGE);                                // asked too early: the charge had not landed
        LedgerPort ledger = ledger();
        slowCharges.set(2);

        assertThatThrownBy(() -> ledger.reserve(reserved("0"), new BigDecimal("0.50"), "ad-13#L0", 3000)).isInstanceOf(LedgerPort.LedgerFault.class);

        awaitKinds("unsure", "returning", "returned");
        assertThat(returnsAsked()).hasSize(2);
    }

    // ── nothing of the call outlives it; nothing is lost at a stop ───────────

    @Test
    void theWorkerKeepsItsOwnCopyOfTheTier_theCallMayReuseIt() throws Exception {
        returnAnswers.add(DOWN);                                          // asked again 30 ms later
        LevelAdmission tier = reserved("0.40");

        ledger().release(tier, "INSUFFICIENT_BALANCE");
        tier.setDebitReference("ad-2#L0");                                // the machine went back to the pool: the next call's tier
        tier.getPartner().setBillingAccountId("9002");

        awaitKinds("returning", "returned");
        assertThat(returnsAsked()).hasSize(2);
        assertThat(returnsAsked()).allSatisfy(asked -> {
            assertThat(asked.path()).isEqualTo(RETURN_ROAD);
            assertThat(json(asked.body()).get("reference").asText()).isEqualTo("ad-1#L0");
        });
    }

    @Test
    void aReturnLeftOpenByAProcessThatStopped_isAskedAgainAtTheNextStart() throws Exception {
        returnAnswer = DOWN;
        ledger(new ReturnPace(2, 400, 400, 60, 100)).release(reserved("0.40"), "INSUFFICIENT_BALANCE");
        await("the first ask", () -> returnsAsked().size() == 1);
        assertThat(kinds()).as("the first process is still asking: the story has no end").containsExactly("returning");

        returnAnswer = RETURNED;
        ledger();                                                         // the next start, on the same journal

        await("a 'returned' line", () -> {
            try { return kinds().contains("returned"); } catch (IOException e) { return false; }
        });
        assertThat(returnsAsked().get(1).path()).isEqualTo(RETURN_ROAD);
        assertThat(json(returnsAsked().get(1).body()).get("reference").asText()).isEqualTo("ad-1#L0");
    }

    @Test
    void whatWasWrittenDownAsOwed_isAnOfficers_neverAskedAgainAtTheNextStart() throws Exception {
        returnAnswer = NO_ROAD;
        ledger().release(reserved("0.40"), "INSUFFICIENT_BALANCE");
        awaitKinds("returning", "owed");
        int askedBefore = returnsAsked().size();

        returnAnswer = RETURNED;                                          // the road is there now
        ledger();                                                         // the next start, on the same journal

        pause(200);
        assertThat(returnsAsked()).as("an officer may have credited an owed line by hand: a second return would pay twice").hasSize(askedBefore);
        assertThat(kinds()).containsExactly("returning", "owed");
    }

    @Test
    void moreReturnsThanMayWait_areOwedInsteadOfQueued() throws Exception {
        returnDelayMs = 600;                                              // the worker is busy with the first one
        LedgerPort ledger = ledger(new ReturnPace(1, 30, 30, 60, 1), 4000);

        ledger.release(reserved("0.40"), "first");
        await("the worker took the first", () -> returnsAsked().size() == 1);
        ledger.release(reserved("0.40"), "second");                       // waits behind the first
        ledger.release(reserved("0.40"), "third");                        // one waits already

        await("three ends", () -> {
            try { return kinds().stream().filter(k -> !k.equals("returning")).count() == 3; } catch (IOException e) { return false; }
        });
        assertThat(lines().stream().filter(l -> l.get("kind").asText().equals("owed")).map(l -> l.get("why").asText()))
            .as("the third is written down, never dropped and never blocking the call").singleElement().asString().contains("third").contains("waiting already");
    }
}
