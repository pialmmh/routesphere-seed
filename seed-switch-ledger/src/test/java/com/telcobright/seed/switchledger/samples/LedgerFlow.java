package com.telcobright.seed.switchledger.samples;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.EntryPartner;
import com.telcobright.seed.sessionflow.api.SessionFlow;
import com.telcobright.seed.sessionflow.api.SessionFlowContext;
import com.telcobright.seed.sessionflow.api.SessionMachine;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.dependencies.SessionFlowKit;
import com.telcobright.seed.switchledger.api.AccountOrder;
import com.telcobright.seed.switchledger.api.LiveBalance;
import com.telcobright.seed.switchledger.api.WindowRenewal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A session on the switch ledger, the shape a WiFi switch takes on the base: the partner is found by the device's MAC; a tier's rate is
 * per window; the leaf tier's account is the first unit BUCKET of its partner on the tier's own tree ({@link AccountOrder#buckets}: no
 * package, no wifi) and the base reserves it through the kit's ledger (the {@code MemLedgerPort}); a tier with no rate is free. A long
 * session renews the same window on the same account every period — through the base's own renewal, or, given the ledger's live-balance
 * peek, through {@link WindowRenewal}: the whole window, else the remainder (the call's C14); the settlement charges what the test scripts.
 */
public class LedgerFlow extends SessionFlow<LedgerFlow.Session> {

    public static final class Session extends SessionFlowContext {
        public volatile String mac;
    }

    private final Map<String, Integer> partnerByMac;
    private final Map<String, BigDecimal> ratePerWindow;
    /** The ledger's peek: with it the renewal is the WiFi's (WindowRenewal); without, the base's. */
    private final LiveBalance balances;
    /** What a tier finally pays at the settlement: by default everything it reserved. */
    public volatile Function<LevelAdmission, BigDecimal> charge = TierSettlement::reservedOf;

    /** @param ratePerWindow a tier's rate per window by {@code tenant#partner}; a tier absent here is free */
    public LedgerFlow(SessionFlowKit kit, Map<String, Integer> partnerByMac, Map<String, BigDecimal> ratePerWindow) {
        this(kit, partnerByMac, ratePerWindow, null);
    }

    /** The same, renewing through {@link WindowRenewal} over the ledger's peek (3b). */
    public LedgerFlow(SessionFlowKit kit, Map<String, Integer> partnerByMac, Map<String, BigDecimal> ratePerWindow, LiveBalance balances) {
        super(kit);
        this.partnerByMac = partnerByMac;
        this.ratePerWindow = ratePerWindow;
        this.balances = balances;
    }

    @Override public String name() { return "wifi-like"; }

    @Override protected int serviceGroup(Session session) { return 0; }

    @Override
    protected String buildTask(Session session) {
        session.taskType = "WIFI";
        session.protocol = "RADIUS";
        session.originatingCallingNumber = session.mac;
        return null;
    }

    @Override
    protected EntryPartner identifyEntryPartner(Session session) {
        Integer partnerId = partnerByMac.get(session.mac);
        return partnerId == null ? null : entryOfPartner(session, partnerId);
    }

    /** The tier's rate on its partner's first unit bucket; no rate = a free tier; a rate with no bucket = unrated (no package, no wifi). */
    @Override
    protected TierRate rateAtLevel(Session session, Tenant tier, Partner partner, int levelIndex) {
        BigDecimal rate = ratePerWindow.get(tier.getDbName() + "#" + partner.getIdPartner());
        if (rate == null) return TierRate.free();
        List<PackageAccount> buckets = AccountOrder.buckets(partner, tier.getContext(), LocalDateTime.now(kit.clock()));
        if (buckets.isEmpty()) return null;
        PackageAccount account = buckets.get(0);
        return TierRate.of(rate, rate, account.getUom(), "wifi").onAccount(account);
    }

    /** The same window again, on the tier's own account. */
    @Override
    protected TierRate rateNextWindow(Session session, LevelAdmission level) {
        return TierRate.of(level.getRate(), level.getRate(), level.getUom(), level.getRatePrefix());
    }

    /** The WiFi's renewal (3b): the whole window through the chain's own reserve, else the remainder's seconds, under the base's window reference. */
    @Override
    protected double renewWindowSeconds(Session session, LevelAdmission level) {
        if (balances == null || level.getDebitReference() == null) return super.renewWindowSeconds(session, level);
        WindowRenewal renewal = new WindowRenewal(balances, (tier, amount, reference) -> reserveWindow(session, tier, amount, reference));
        String reference = level.getDebitReference() + "#W" + (level.getReservationCount() + 1);
        return renewal.renewSeconds(level, level.getRate(), reference, level.getRate(), kit.settings().reservePeriodSec());
    }

    @Override protected void startSignaling(Session session, SessionMachine machine) { }

    @Override protected BigDecimal chargeAtSettle(Session session, LevelAdmission level) { return charge.apply(level); }

    public static Session session(String id, String mac) {
        Session s = new Session();
        s.sessionKey = id;
        s.tenantName = "btcl";
        s.mac = mac;
        return s;
    }
}
