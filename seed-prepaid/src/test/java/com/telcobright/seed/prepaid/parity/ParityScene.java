package com.telcobright.seed.prepaid.parity;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.IdentificationRule;
import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerUnit;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.ManualClock;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.api.SwitchLedger;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.SessionTimings;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.Established;
import com.telcobright.statewalk.session.events.SessionStart;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The scene of every parity row: ONE root {@code btcl} with ONE reseller tier {@code res_2}, the REAL switch ledger (a MemLedger over H2,
 * nothing listening), a manual clock, the call lane on the base's kit — driven at the handler level (the money rows) or on the engine
 * (the rows that need the renewal timer and the cut).
 *
 * <pre>
 *   btcl    partner 2 = the reseller (accounts 21 TF_min 10, 22 BDT 50) · partner 9 Direct (91 BDT 100; plan 8801 → 0.75, 88018 → 0.50, 88019 → 0.00)
 *   res_2   701 Unilever (11 TF_min 2.5 · 12 BDT 100 · 13 TF_min 100 EXPIRED; plan 88017 → 0.60, 88018 → 0.50)
 *           702 Poorco (no account) · 703 CashOnly (31 BDT 1.00) · 704 PackagesOnly (41 TF_min 100)
 *           705 Adco, an advertiser (L3): 51 AD_view 100 (2030) · 52 AD_view 5 (2027, the soonest) · 53 AD_sec 300 · 54 BDT 20 ·
 *               55 AD_view 50 EXPIRED
 *   117     the root's international plan: 44 → 1.50/min (no minimum, 1 s pulse) · 1 → 2.00/min (30 s minimum, 10 s pulse)
 * </pre>
 */
public final class ParityScene implements AutoCloseable {

    public static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    public static final Instant T0 = Instant.parse("2026-10-26T04:00:00Z");
    public static final Ingress ESL_DOOR = Ingress.esl("10.0.0.1", 8021);
    public static final LocalDateTime LATER = LocalDateTime.of(2030, 1, 1, 0, 0), EXPIRED = LocalDateTime.of(2026, 1, 1, 0, 0);
    public static final LocalDateTime SOONER = LocalDateTime.of(2027, 6, 1, 0, 0);
    public static final int RESELLER = 2, DIRECT = 9, UNILEVER = 701, POORCO = 702, CASH_ONLY = 703, PACKAGES_ONLY = 704, ADCO = 705;
    public static final String DOMESTIC = "8801711000000", PORTED = "8801811000000", UNRATED_PREFIX = "8809900000000", ZERO_RATED = "8801911000000";
    /** A number on Direct's 8801 rate (0.75) that the scene does NOT port (DOMESTIC is ported at the answer: P13). */
    public static final String NOT_PORTED = "8801712000000";
    public static final String DENIED = "9901711000000", INTL_UK = "00442071234567", INTL_US = "0012125551234";
    /** The live reaper's thresholds ({@code ReserveReaperJob} 40–42): 30 min, 1000 rows per pass. */
    public static final SwitchLedgerSettings LEDGER_SETTINGS = new SwitchLedgerSettings(30, 1000, 5000);

    public final ManualClock clock = new ManualClock(T0, DHAKA);
    public final LedgerLab lab = LedgerLab.open();
    public final Tenant root = tree();
    public final CallRates rates = rates();
    public final RecordingCdrSink cdrs = new RecordingCdrSink();
    public final List<CallContext> contexts = new CopyOnWriteArrayList<>();
    public final RecordingPort recorder;
    public final SwitchLedger ledger;
    public final RoutesphereKit kit;
    public final RoutesphereFlow<CallContext> flow;
    public final TestChannel<String, StatemachineEvent> wire = new TestChannel<>("parity");
    public final RoutesphereEngine<CallContext> engine;

    /**
     * The lane under test. Item 1: the base's builder with the call's answers and NO Prepaid (the gaps of today's base stay disabled on it);
     * 2b: the same call as a BIG lane (a subclass of RoutesphereFlow) on the call's Prepaid ({@link CallFlowOnPrepaid}) — the same rows, the
     * same assertions, the gaps written as they should be and run; 3: the call in builder syntax with {@code .prepaid(CallPrepaid)} — the
     * small lane's way, the one the design names ({@link CallLane#onPrepaid}).
     */
    public enum Lane { ON_THE_BASE, ON_A_SUBCLASS_PREPAID, ON_PREPAID }

    private ParityScene(Map<String, String> balances, RoutesphereSettings settings, boolean withEngine, Function<RoutesphereKit, RoutesphereFlow<CallContext>> lanes) {
        this(balances, null, settings, withEngine, lanes, null);
    }

    /** L2b item 4 · The ledger as a ledger DB with the purchases cached (each account's purchase as the tree names it; expiries overridden). */
    private ParityScene(Map<String, String> balances, Map<Long, String> purchaseExpiries, RoutesphereSettings settings, boolean withEngine,
                        Function<RoutesphereKit, RoutesphereFlow<CallContext>> lanes) {
        this(balances, purchaseExpiries, settings, withEngine, lanes, null);
    }

    /** §15 · The same, the catalog and the products cached too; {@code catalog} lays down packages, items, products and the accounts' items before the start. */
    private ParityScene(Map<String, String> balances, Map<Long, String> purchaseExpiries, RoutesphereSettings settings, boolean withEngine,
                        Function<RoutesphereKit, RoutesphereFlow<CallContext>> lanes, java.util.function.Consumer<LedgerLab> catalog) {
        fundTheLab(balances);
        if (purchaseExpiries != null) buyEveryAccountsPurchase(purchaseExpiries);
        if (catalog != null) {
            lab.productTable();
            catalog.accept(lab);
            lab.asLedgerDb(List.of("packageaccount", "packageaccountreserve", "packagepurchase", "catalog", "product"), List.of("packageaccountreserve"), 5_000);
        }
        lab.start();
        this.ledger = SwitchLedgers.over(lab.ledger(), LEDGER_SETTINGS, clock, purchaseExpiries == null ? null : catalog != null
            ? java.util.Set.of("PackageAccount", "PackageAccountReserve", "PackagePurchase", "Package", "PackageItem", "Product")
            : java.util.Set.of("PackageAccount", "PackageAccountReserve", "PackagePurchase"));
        this.recorder = new RecordingPort(ledger.port(), this::ctxOrNull);
        this.kit = RoutesphereKit.builder().tenants(TenantLookup.of(root)).identification(List.of(new IdentificationRule("esl", "10.0.0.1:8021", "btcl")))
            .ledger(recorder).cdr(cdrs).zone(DHAKA).clock(clock).settings(settings).build();
        this.flow = lanes.apply(kit);
        this.engine = withEngine ? RoutesphereEngine.of(new ParitySupervisor(flow, this::newContext)).signaling(ParityWire.spec()).channel(wire, ParityScene::decode).start() : null;
    }

    /**
     * L2b item 4 (S4) · The handler-level scene whose ledger caches the PURCHASES: a partner's accounts and their expiry are the ledger's,
     * not the tree's — {@code expiries} (purchase id → {@code "yyyy-MM-dd HH:mm:ss"}) makes the ledger's purchase disagree with the tree.
     */
    public static ParityScene openOnTheLedgersPurchases(Map<String, String> balances, Map<Long, String> expiries, Lane lane) {
        return new ParityScene(balances, expiries, handlerSettings(), false, kit -> laneOf(lane, kit));
    }

    /**
     * §15 · The per-event scene (the ad) whose ledger caches the purchases AND the catalog with its products: {@code catalog} lays down the
     * packages, items, products and the accounts' items on the lab before the start (every purchase is of package 1 until it moves one).
     */
    public static ParityScene openOnProductItems(Map<String, String> balances, Function<RoutesphereKit, RoutesphereFlow<CallContext>> lane, java.util.function.Consumer<LedgerLab> catalog) {
        return new ParityScene(balances, Map.of(), perEventSettings(), false, lane, catalog);
    }

    /** The handler-level scene: the live call's cadence (60 s, the first renewal at 58 s), a 3-hour ACTIVE, no machine. */
    public static ParityScene open() { return open(Map.of()); }

    /** The same with some live balances overridden ({@code "res_2#11" → "0.5"}). */
    public static ParityScene open(Map<String, String> balances) { return open(balances, Lane.ON_THE_BASE); }

    /** The handler-level scene on a lane. */
    public static ParityScene open(Map<String, String> balances, Lane lane) { return new ParityScene(balances, handlerSettings(), false, kit -> laneOf(lane, kit)); }

    /** The scene on the engine: the budget child renews every {@code periodSec}, the first time after {@code firstSec} (0 = one period). */
    public static ParityScene openWithEngine(long firstSec, long periodSec, Map<String, String> balances) {
        return openWithEngine(firstSec, periodSec, balances, Lane.ON_THE_BASE);
    }

    /** The scene on the engine, on a lane. */
    public static ParityScene openWithEngine(long firstSec, long periodSec, Map<String, String> balances, Lane lane) {
        return new ParityScene(balances, engineSettings(firstSec, periodSec), true, kit -> laneOf(lane, kit));
    }

    /** The scene with settings and a lane of the test's own (the SMS-shaped rows of item 4: no renewal, CHARGE_NOW). */
    public static ParityScene openWith(RoutesphereSettings settings, Map<String, String> balances, Function<RoutesphereKit, RoutesphereFlow<CallContext>> lane) {
        return new ParityScene(balances, settings, false, lane);
    }

    /** A lane priced per event (the SMS, the ad): no budget child — one charge or one hold at admission, the end decides. */
    public static RoutesphereSettings perEventSettings() { return handlerSettings().withReservePeriodSec(0).withReserveInitialDelaySec(0); }

    /** The lane under test on the scene's kit: the call's answers on the base (item 1), or the call's Prepaid (2b). */
    static RoutesphereFlow<CallContext> laneOf(Lane lane, RoutesphereKit kit) {
        return switch (lane) {
            case ON_THE_BASE -> CallLane.onTheBase(kit, rates(), Map.of(DOMESTIC, PORTED), kit.clock());
            case ON_A_SUBCLASS_PREPAID -> new CallFlowOnPrepaid(kit, rates(), Map.of(DOMESTIC, PORTED));
            case ON_PREPAID -> CallLane.onPrepaid(kit, rates(), Map.of(DOMESTIC, PORTED));
        };
    }

    public static RoutesphereSettings handlerSettings() {
        return new RoutesphereSettings(8, 2, 12_000, new SessionTimings(2, 2, 5, 10_800), 60, 58, 0, false, 200);
    }

    public static RoutesphereSettings engineSettings(long firstSec, long periodSec) {
        return new RoutesphereSettings(8, 2, 120, new SessionTimings(2, 2, 5, 60), periodSec, firstSec, 0, false, 200);
    }

    // ── the scene's facts ───────────────────────────────────────────────────

    static Tenant tree() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(RESELLER, "res_2", PartnerType.RESELLER).partner(DIRECT, "Direct", PartnerType.CUSTOMER)
            .account(RESELLER, 21, 5, "TF_min", "10", LATER).account(RESELLER, 22, 6, "BDT", "50", LATER)
            .account(DIRECT, 91, 9, "BDT", "100", LATER);
        b.tenant("res_2", "btcl").partner(UNILEVER, "Unilever", PartnerType.CUSTOMER).partner(POORCO, "Poorco", PartnerType.CUSTOMER)
            .partner(CASH_ONLY, "CashOnly", PartnerType.CUSTOMER).partner(PACKAGES_ONLY, "PackagesOnly", PartnerType.CUSTOMER)
            .partner(ADCO, "Adco", PartnerType.CUSTOMER)
            .account(UNILEVER, 11, 1, "TF_min", "2.5", LATER).account(UNILEVER, 12, 2, "BDT", "100", LATER).account(UNILEVER, 13, 3, "TF_min", "100", EXPIRED)
            .account(CASH_ONLY, 31, 4, "BDT", "1.00", LATER).account(PACKAGES_ONLY, 41, 7, "TF_min", "100", LATER)
            .account(ADCO, 52, 12, "AD_view", "5", SOONER).account(ADCO, 51, 11, "AD_view", "100", LATER).account(ADCO, 53, 13, "AD_sec", "300", LATER)   // prime-context's order: the soonest first
            .account(ADCO, 54, 14, "BDT", "20", LATER).account(ADCO, 55, 15, "AD_view", "50", EXPIRED);
        return b.build();
    }

    static CallRates rates() {
        return new CallRates(
            Map.of("btcl#" + DIRECT, Map.of("8801", new BigDecimal("0.75"), "88018", new BigDecimal("0.50"), "88019", new BigDecimal("0.00")),
                   "res_2#" + UNILEVER, Map.of("88017", new BigDecimal("0.60"), "88018", new BigDecimal("0.50"))),
            Map.of("44", CallRates.intl("1.50", 0, 1), "1", CallRates.intl("2.00", 30, 10)));
    }

    private void fundTheLab(Map<String, String> balances) {
        lab.schema("btcl").schema("res_2");
        row("btcl", 21, 5, "TF_min", "10", balances).row("btcl", 22, 6, "BDT", "50", balances).row("btcl", 91, 9, "BDT", "100", balances)
            .row("res_2", 11, 1, "TF_min", "2.5", balances).row("res_2", 12, 2, "BDT", "100", balances).row("res_2", 13, 3, "TF_min", "100", balances)
            .row("res_2", 31, 4, "BDT", "1.00", balances).row("res_2", 41, 7, "TF_min", "100", balances)
            .row("res_2", 51, 11, "AD_view", "100", balances).row("res_2", 52, 12, "AD_view", "5", balances).row("res_2", 53, 13, "AD_sec", "300", balances)
            .row("res_2", 54, 14, "BDT", "20", balances).row("res_2", 55, 15, "AD_view", "50", balances);
    }

    /** The purchase's expiry as the tree names it: 3 and 15 EXPIRED, 12 the soonest (2027), the rest 2030. */
    private static String defaultExpiry(long purchase) {
        return purchase == 3 || purchase == 15 ? "2026-01-01 00:00:00" : purchase == 12 ? "2027-06-01 00:00:00" : "2030-01-01 00:00:00";
    }

    /** Every account's purchase as the tree names it (partner, expiry; 3 = EXPIRED), the ledger as a ledger DB over them. */
    private void buyEveryAccountsPurchase(Map<Long, String> expiries) {
        lab.purchaseTables();
        long[][] purchases = {{5, RESELLER}, {6, RESELLER}, {9, DIRECT}};
        for (long[] p : purchases) lab.purchase("btcl", p[0], p[1], expiries.getOrDefault(p[0], "2030-01-01 00:00:00"));
        long[][] inRes2 = {{1, UNILEVER}, {2, UNILEVER}, {3, UNILEVER}, {4, CASH_ONLY}, {7, PACKAGES_ONLY}, {11, ADCO}, {12, ADCO}, {13, ADCO}, {14, ADCO}, {15, ADCO}};
        for (long[] p : inRes2) lab.purchase("res_2", p[0], p[1], expiries.getOrDefault(p[0], defaultExpiry(p[0])));
        lab.asLedgerDb(List.of("packageaccount", "packageaccountreserve", "packagepurchase"), List.of("packageaccountreserve"), 5_000);
    }

    private ParityScene row(String schema, long id, long purchase, String uom, String balance, Map<String, String> overrides) {
        lab.account(schema, id, purchase, uom, overrides.getOrDefault(schema + "#" + id, balance));
        return this;
    }

    // ── the handler level: the money rows ───────────────────────────────────

    public CallContext call(String id, int partnerId, String called) {
        CallContext ctx = newContext();
        ctx.facts = new CallFacts(id, clock.millis(), ESL_DOOR, partnerId, called);
        return ctx;
    }

    private CallContext newContext() {
        CallContext ctx = new CallContext();
        contexts.add(ctx);
        return ctx;
    }

    /** INIT + ADMITTING through the handler alone (no registry): the root, the task, the one candidate, the walk. */
    public Verdict admit(CallContext ctx) {
        flow.onStart(ctx, new SessionStart(ctx.facts));
        ctx.candidates = List.of(Candidate.SELF);
        return flow.admit(ctx);
    }

    public CallContext admitted(String id, int partnerId, String called) {
        CallContext ctx = call(id, partnerId, called);
        Verdict verdict = admit(ctx);
        if (!verdict.accepted()) throw new AssertionError(id + " was refused: " + verdict.cause());
        return ctx;
    }

    /** The answer as the library delivers it: on the context, then the flow's own step (the lane's onAnswered: the call learns its ported number). */
    public void answer(CallContext ctx) {
        Established answer = new Established(clock.millis(), "carrier-a", "PCMA", null);
        ctx.answer = answer;
        flow.onEstablished(ctx, answer);
    }

    public void talk(long seconds) { clock.advance(seconds * 1000); }

    public double renew(CallContext ctx) { return flow.renewReserves(ctx); }

    /** The end of an answered call: the final state's money, then the record (the CDR). */
    public void hangUp(CallContext ctx) {
        flow.settle(ctx, Outcome.SUCCEEDED);
        flow.record(ctx, Outcome.SUCCEEDED);
    }

    /** The end of a call never answered: every reserve back, then the record. */
    public void giveUp(CallContext ctx) {
        ctx.endCause = "NO_ANSWER";
        flow.release(ctx);
        flow.record(ctx, Outcome.FAILED);
    }

    // ── the engine: the rows with a timer ───────────────────────────────────

    static Delivery decode(String sessionId, StatemachineEvent frame) { return Delivery.to(ParityWire.TYPE, frame); }

    public void dial(String id, int partnerId, String called) { frame(id, new ParityWire.Dial(new CallFacts(id, System.currentTimeMillis(), ESL_DOOR, partnerId, called))); }

    public void answerOnTheWire(String id) { frame(id, new ParityWire.Answer(System.currentTimeMillis())); }

    public void hangUpOnTheWire(String id) { frame(id, new ParityWire.Hangup(System.currentTimeMillis())); }

    public void failOnTheWire(String id, String cause) { frame(id, new ParityWire.Fail(cause, 403)); }

    public void frame(String id, StatemachineEvent e) {
        try {
            wire.inject(id, e).toCompletableFuture().get(5, TimeUnit.SECONDS);
            engine.awaitSettled(id, 5, TimeUnit.SECONDS);
            engine.registry().awaitIdle(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("the frame " + e + " for " + id + " was not taken: " + ex, ex);
        }
    }

    /** The context of the session with this id (the last one made for it). */
    public CallContext ctx(String id) {
        CallContext ctx = ctxOrNull(id);
        if (ctx == null) throw new AssertionError("no session " + id);
        return ctx;
    }

    CallContext ctxOrNull(String id) {
        for (int i = contexts.size() - 1; i >= 0; i--) if (id.equals(contexts.get(i).sessionId())) return contexts.get(i);
        return null;
    }

    /** Wait until the session is gone from the registry; the milliseconds it took. */
    public long awaitEnd(String id, long withinMs) throws InterruptedException {
        long startedAt = System.currentTimeMillis();
        while (System.currentTimeMillis() - startedAt < withinMs && engine.isLive(id)) Thread.sleep(10);
        return System.currentTimeMillis() - startedAt;
    }

    public void awaitRenewals(String id, int count, long withinMs) throws InterruptedException {
        long until = System.currentTimeMillis() + withinMs;
        while (System.currentTimeMillis() < until && ctx(id).tiers.stream().anyMatch(t -> t.reservationCount < count)) Thread.sleep(20);
    }

    // ── looking at the books ────────────────────────────────────────────────

    public BigDecimal balance(String schema, long accountId) { return lab.cachedBalance(schema, accountId); }

    public PackageAccountReserve row(String schema, String key) { return lab.cachedRow(schema, key); }

    public int rows(String schema) { return lab.cachedReserveRows(schema); }

    public LocalDateTime now() { return LocalDateTime.now(clock); }

    @Override
    public void close() {
        if (engine != null) engine.close();
        lab.close();
    }

    /**
     * P8's witness: the switch ledger's port with every money call recorded — the verb, the reference, and whether the session's money
     * lock was held by the calling thread (the base's rule: a renewal and a settlement of one session never run beside each other).
     */
    public static final class RecordingPort implements LedgerPort {
        private final LedgerPort real;
        private final Function<String, CallContext> contexts;
        public final List<String> seen = new CopyOnWriteArrayList<>();

        RecordingPort(LedgerPort real, Function<String, CallContext> contexts) { this.real = real; this.contexts = contexts; }

        private void note(String verb, String reference) {
            String sessionId = reference == null ? "?" : reference.split("[#.]")[0];
            CallContext ctx = contexts.apply(sessionId);
            seen.add(verb + " " + reference + " locked=" + (ctx != null && ctx.money.isHeldByCurrentThread()) + " thread=" + Thread.currentThread().getName());
        }

        @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) { note("reserve", reference); return real.reserve(level, amount, reference); }
        @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { note("settle", level.getDebitReference()); return real.settle(level, charged); }
        @Override public void release(LevelAdmission level, String why) { note("release", level.getDebitReference()); real.release(level, why); }
        /** 2b: the peek of the C14 remainder (a read: not recorded as a money move). */
        @Override public Optional<BigDecimal> balanceOf(LevelAdmission level) { return real.balanceOf(level); }
        /** 4: CHARGE_NOW's final debit and its refund. */
        @Override public Optional<Reservation> charge(LevelAdmission level, BigDecimal amount, String reference) { note("charge", reference); return real.charge(level, amount, reference); }
        @Override public TierSettlement refund(LevelAdmission level, BigDecimal amount, String why) { note("refund", level.getDebitReference()); return real.refund(level, amount, why); }
        /** 5: the money roads. */
        @Override public Credited credit(LevelAdmission account, BigDecimal amount, String reference) { note("credit", reference); return real.credit(account, amount, reference); }
        @Override public Credited reparent(LevelAdmission account, long purchaseId) { note("reparent", "account-" + account.getPackageAccountId()); return real.reparent(account, purchaseId); }
        @Override public long slowestAnswerMs() { return real.slowestAnswerMs(); }

        /** L2b item 4: the purchase road and the ledger's accounts. */
        @Override public Purchased purchase(LevelAdmission buyer, Offer offer, String reference, Ids ids) { note("purchase", reference); return real.purchase(buyer, offer, reference, ids); }
        @Override public Optional<java.util.List<com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount>> accountsOf(LevelAdmission buyer) { return real.accountsOf(buyer); }
        /** §15 · the catalog read passes through like every read (a wrapper that inherited the SPI's default would walk by unit). */
        @Override public Optional<ItemCatalog> catalogOf(LevelAdmission tier) { return real.catalogOf(tier); }

        /** L2b: a unit's moves are recorded as the port's verbs are (the same words), then staged in the real port's unit. */
        @Override public LedgerUnit unit() { return new RecordingUnit(real.unit()); }

        private final class RecordingUnit implements LedgerUnit {
            private final LedgerUnit unit;
            RecordingUnit(LedgerUnit unit) { this.unit = unit; }
            @Override public void lock(java.util.Collection<LevelAdmission> accounts) { unit.lock(accounts); }
            @Override public Optional<BigDecimal> balanceOf(LevelAdmission level) { return unit.balanceOf(level); }
            @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) { note("reserve", reference); return unit.reserve(level, amount, reference, withinMs); }
            @Override public Optional<Reservation> charge(LevelAdmission level, BigDecimal amount, String reference) { note("charge", reference); return unit.charge(level, amount, reference); }
            @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { note("settle", level.getDebitReference()); return unit.settle(level, charged); }
            @Override public void release(LevelAdmission level, String why) { note("release", level.getDebitReference()); unit.release(level, why); }
            @Override public TierSettlement refund(LevelAdmission level, BigDecimal amount, String why) { note("refund", level.getDebitReference()); return unit.refund(level, amount, why); }
            @Override public void abandon(String why) { unit.abandon(why); }
            @Override public void commit(String what) { unit.commit(what); }
            @Override public void close() { unit.close(); }
        }
    }
}
