package com.telcobright.seed.prepaid.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.LedgerPort;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The money roads of a lane's Prepaid (L2a item 5; design §10 {@code prepaid.admin()}; P20): what an operator's or a reseller's screen calls
 * on the switch that owns the root — never prime-context, never config-manager, never a database write (the owner, 2026-10-26: package
 * money goes through mem-ledger). The application mounts them on its own roads (the seed has no server, R0).
 *
 * <ul>
 *   <li>{@link #credit} — a top-up: a DELTA added to one account through the kit's ledger port, under the account's lock (the switch
 *       ledger's {@code Credit}: routesphere-core {@code PrepaidServiceWithCompensation.recharge} 294–383); the next hold sees it;</li>
 *   <li>{@link #reparent} — the account moved onto a new purchase (its expiry follows the purchase), its balance kept;</li>
 *   <li>{@link #accounts} — a partner's accounts as the TREE names them today (S4: the catalog is the tree's until L2b), with the ledger's
 *       live balance of each;</li>
 *   <li>{@link #purchase} — NOT this round: refused in words naming L2b (it needs ids from seed-idgen and the purchase entity in the ledger).</li>
 * </ul>
 */
public final class PrepaidAdmin {

    /** One account of a partner: the tree's row (id, purchase, unit, expiry) and the ledger's live balance (null = the ledger cannot say). */
    public record Account(long id, long purchaseId, String uom, String name, java.time.LocalDateTime expiresAt, BigDecimal balance) {}

    private final RoutesphereKit kit;

    PrepaidAdmin(RoutesphereKit kit) { this.kit = Objects.requireNonNull(kit, "kit"); }

    /** Add {@code amount} (positive) to an account of {@code tierDb} in {@code root}'s tree; refused in words (an unknown tier, a delta of 0 or less). */
    public LedgerPort.Credited credit(String root, String tierDb, long accountId, BigDecimal amount, String reference) {
        return kit.ledger().credit(accountOf(root, tierDb, accountId), amount, reference);
    }

    /** Move an account of {@code tierDb} onto purchase {@code purchaseId}; its balance stays. */
    public LedgerPort.Credited reparent(String root, String tierDb, long accountId, long purchaseId) {
        return kit.ledger().reparent(accountOf(root, tierDb, accountId), purchaseId);
    }

    /** The accounts the tree names for {@code partnerId} at {@code tierDb} (expired ones included, in the tree's order), each with its live balance. */
    public List<Account> accounts(String root, String tierDb, int partnerId) {
        Tenant tier = tierOf(root, tierDb);
        Map<Long, List<PackageAccount>> byPartner = tier.getContext() == null ? null : tier.getContext().getPartnerIdWisePackageAccounts();
        List<PackageAccount> rows = byPartner == null ? null : byPartner.get((long) partnerId);
        if (rows == null) return List.of();
        List<Account> accounts = new ArrayList<>(rows.size());
        for (PackageAccount row : rows) accounts.add(accountWithBalance(tier, row));
        return List.copyOf(accounts);
    }

    /** L2b's: a purchase and its account(s) made through the ledger — refused in words this round. */
    public Account purchase(String root, String tierDb, int partnerId, String packageName, BigDecimal opening) {
        throw new UnsupportedOperationException("a purchase through the ledger is round L2b's (design prepaid-engine-design.md §8, §10.5): it needs ids"
            + " from seed-idgen and the purchase entity in the ledger's cache — until then the billing portal writes the purchase and its accounts,"
            + " and the tree's reload brings them to the switch");
    }

    private Account accountWithBalance(Tenant tier, PackageAccount row) {
        LevelAdmission level = levelFor(tier, row.getId());
        BigDecimal live = kit.ledger().balanceOf(level).orElse(null);
        return new Account(row.getId(), row.getIdpackagePurchase(), row.getUom(), row.getName(), row.getExpireDate(), live);
    }

    private LevelAdmission accountOf(String root, String tierDb, long accountId) { return levelFor(tierOf(root, tierDb), accountId); }

    /** The account as the ledger port names it: the tier (its tenant routes a root's ledger) and the account id. */
    private static LevelAdmission levelFor(Tenant tier, long accountId) {
        Partner nobody = new Partner();
        LevelAdmission level = new LevelAdmission(0, tier, nobody, null);
        level.setChargeAccountId(accountId);
        return level;
    }

    private Tenant tierOf(String root, String tierDb) {
        return kit.tenants().tenantByDbName(root, tierDb).orElseThrow(() -> new IllegalArgumentException(
            "the tier '" + tierDb + "' is not in the tree of the root '" + root + "' this switch serves"));
    }
}
