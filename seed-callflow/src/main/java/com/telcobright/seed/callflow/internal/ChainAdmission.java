package com.telcobright.seed.callflow.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.api.AdAdmission;
import com.telcobright.seed.callflow.api.AdCallPayload;
import com.telcobright.seed.callflow.api.AdCause;
import com.telcobright.seed.callflow.api.LevelCharge;
import com.telcobright.seed.callflow.spi.AdAdmissionPort;
import com.telcobright.seed.callflow.spi.AdBillingPort;
import com.telcobright.seed.callflow.spi.TenantLookup;
import com.telcobright.statewalk.pipeline.StepMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code DefaultAdmissionService.runAdmission} ported for ads (design §2.3): the top-level readability shape kept —
 * identify the entry tenant → walk the ancestor chain leaf → root (per tier: identify the partner, check its status,
 * the concurrent cap at the entry tier, RATE the view on THIS tier's plan, DEBIT it) → a later tier's refusal credits the
 * earlier tiers back. Nothing here reads SQL: every entity comes from {@code Tenant.getContext()}.
 *
 * <table>
 *   <tr><th>call step</th><th>ad step</th></tr>
 *   <tr><td>IdentifyEntryPartnerStep</td><td>the partner is known ({@code payload.inPartnerId}); the entry tenant = the one whose partners hold it</td></tr>
 *   <tr><td>IdentifyPartnerStep above the leaf</td><td>{@code MultiLevelTaskBuilder.findResellerPartnerId}: the parent's partner of type RESELLER whose name is the child tenant's</td></tr>
 *   <tr><td>CheckAuthorizationStep</td><td>status ACTIVE; {@code maxConcurrentChannels} = the advertiser's concurrent views (entry tier, LIVE only)</td></tr>
 *   <tr><td>DigitFilterStep</td><td>skipped</td></tr>
 *   <tr><td>ReserveBalanceStep</td><td>DEBIT the whole view now through {@link AdBillingPort}; a thrown {@link AdBillingPort.BillingSystemFault} is {@code BILLING_SYSTEM_ERROR}, never a money cause</td></tr>
 *   <tr><td>ResolveRouteStep</td><td>skipped: the outgoing route and the out-partner are on the payload</td></tr>
 *   <tr><td>compensateReserves</td><td>{@link #compensate}: a CREDIT of every debited tier, reason {@code compensation:<cause>}</td></tr>
 * </table>
 *
 * A FALLBACK payload (the house campaign) is free: every tier is walked and recorded at 0, nothing is debited. A tier whose
 * partner has no live rate for the view refuses with {@code UNRATED} (the content leaves the draw, as {@code rating.unrated=skip}).
 */
public final class ChainAdmission implements AdAdmissionPort {

    private static final Logger log = LoggerFactory.getLogger(ChainAdmission.class);

    /** The per-candidate refusal of a tier with no rate row for the view (not a design cause: the aggregate is NO_FUNDED_CAMPAIGN). */
    public static final String UNRATED = "UNRATED";

    private final TenantLookup tenants;
    private final AdBillingPort billing;
    private final Clock clock;
    /** The live views per entry partner (the advertiser's concurrent cap), released at the session's end. */
    private final ConcurrentHashMap<Integer, AtomicInteger> activeByPartner = new ConcurrentHashMap<>();

    public ChainAdmission(TenantLookup tenants, AdBillingPort billing, Clock clock) {
        this.tenants = tenants;
        this.billing = billing;
        this.clock = clock;
    }

    @Override
    public AdAdmission admit(AdCallPayload payload, String requestId, StepMode mode) {
        Integer payer = payload.inPartnerId();
        if (payer == null || payer <= 0) {
            Tenant byName = tenants.tenantByDbName(payload.tenantName()).orElse(null);
            if (payload.fallback()) return AdAdmission.admitted(List.of(), byName, null);     // the house ad of a tenant with no partner of its own
            return AdAdmission.refused(AdCause.PARTNER_NOT_FOUND, byName, null);
        }
        Tenant entryTenant = tenants.tenantOfPartner(payer).orElse(null);
        if (entryTenant == null) return AdAdmission.refused(AdCause.PARTNER_NOT_FOUND, tenants.tenantByDbName(payload.tenantName()).orElse(null), null);
        Partner entryPartner = entryTenant.getContext().getPartners().get(payer);
        List<Tenant> chain = entryTenant.getAncestorChain();
        if (chain == null || chain.isEmpty()) chain = List.of(entryTenant);

        List<LevelAdmission> levels = new ArrayList<>();
        boolean slotHeld = false;
        Tenant below = null;
        for (int i = 0; i < chain.size(); i++) {
            Tenant tier = chain.get(i);
            Partner partner = i == 0 ? entryPartner : resellerPartnerOf(below, tier);
            if (partner == null) return refuse(AdCause.PARTNER_NOT_FOUND.name(), levels, slotHeld, payer, requestId, entryTenant, entryPartner);
            if (partner.getStatus() != null && !"ACTIVE".equalsIgnoreCase(partner.getStatus())) {
                return refuse(AdCause.PARTNER_DEACTIVATED.name(), levels, slotHeld, payer, requestId, entryTenant, entryPartner);
            }
            if (i == 0 && mode != StepMode.SIMULATE) {
                if (!acquireSlot(partner)) return refuse(AdCause.CHANNEL_LIMIT_REACHED.name(), levels, false, payer, requestId, entryTenant, entryPartner);
                slotHeld = true;
            }
            LevelAdmission level = new LevelAdmission(i, tier, partner, null);
            level.setUsageKind(payload.mediaKind());
            level.setUsageSeconds(payload.requiredSeconds());
            if (payload.fallback()) {
                level.setRate(BigDecimal.ZERO);
                level.setReservedAmount(BigDecimal.ZERO);
                level.setUom("BDT");
            } else {
                Optional<TierRating.Rated> rated = TierRating.rate(tier, partner.getIdPartner(), calledOf(payload), payload.mediaKind(),
                    payload.requiredSeconds(), LocalDateTime.now(clock));
                if (rated.isEmpty()) {
                    log.debug("{} | tier {} ({}): partner {} has no live ad rate for called {} {} — unrated", requestId, i, tier.getDbName(), partner.getIdPartner(), calledOf(payload), payload.mediaKind());
                    return refuse(UNRATED, levels, slotHeld, payer, requestId, entryTenant, entryPartner);
                }
                TierRating.Rated r = rated.get();
                level.setRate(r.amount());
                level.setRatePrefix(r.prefix());
                level.setUom(r.currency() == null ? "BDT" : r.currency());
                level.setReservedAmount(BigDecimal.ZERO);
                if (mode != StepMode.SIMULATE && r.amount().signum() > 0) {
                    String reference = requestId + "#L" + i;
                    Optional<LevelCharge> charge;
                    try {
                        charge = billing.debit(level, r.amount(), reference);
                    } catch (AdBillingPort.LedgerRefusal refusal) {
                        log.info("{} | tier {} ({}) partner {}: the ledger refused: {}", requestId, i, tier.getDbName(), partner.getIdPartner(), refusal.getMessage());
                        return refuse(refusal.code(), levels, slotHeld, payer, requestId, entryTenant, entryPartner);
                    } catch (AdBillingPort.BillingSystemFault fault) {
                        log.error("{} | BILLING SYSTEM FAULT at tier {} ({}) partner {}: {} — refusing as BILLING_SYSTEM_ERROR (this is NOT an insufficient-balance case)",
                            requestId, i, tier.getDbName(), partner.getIdPartner(), fault.getMessage());
                        return refuse(AdCause.BILLING_SYSTEM_ERROR.name(), levels, slotHeld, payer, requestId, entryTenant, entryPartner);
                    }
                    if (charge.isEmpty()) return refuse(AdCause.INSUFFICIENT_BALANCE.name(), levels, slotHeld, payer, requestId, entryTenant, entryPartner);
                    LevelCharge c = charge.get();
                    level.setDebitReference(reference);
                    level.setChargeAccountId(c.chargeAccount());
                    if (c.chargeUom() != null) level.setUom(c.chargeUom());
                    level.setReservedAmount(c.amount());
                    level.setBalanceBefore(c.balanceBefore());
                    level.setBalanceAfter(c.balanceAfter());
                    level.incrementReservationCount();
                }
            }
            levels.add(level);
            below = tier;
        }
        return AdAdmission.admitted(levels, entryTenant, entryPartner);
    }

    /** The tiers debited before a later tier's refusal are credited back — the only automatic refund there is. */
    @Override
    public void compensate(AdAdmission admission, String requestId, String why) {
        if (admission == null) return;
        creditAll(admission.levels(), requestId, why);
    }

    /** The session ended: the advertiser's concurrent slot is free again (the entry partner's, exactly once per admission). */
    @Override
    public void release(AdAdmission admission) {
        if (admission == null || admission.entryPartner() == null || admission.entryPartner().getIdPartner() == null) return;
        releaseSlot(admission.entryPartner().getIdPartner());
    }

    /** The live views of a partner right now (monitoring, tests). */
    public int activeOf(int partnerId) {
        AtomicInteger n = activeByPartner.get(partnerId);
        return n == null ? 0 : n.get();
    }

    private AdAdmission refuse(String cause, List<LevelAdmission> debited, boolean slotHeld, int payer, String requestId, Tenant entryTenant, Partner entryPartner) {
        creditAll(debited, requestId, cause);
        if (slotHeld) releaseSlot(payer);
        return AdAdmission.refused(cause, entryTenant, entryPartner);
    }

    private void creditAll(List<LevelAdmission> levels, String requestId, String why) {
        for (LevelAdmission level : levels) {
            if (level == null || level.getDebitReference() == null || level.getReservedAmount() == null || level.getReservedAmount().signum() <= 0) continue;
            LevelCharge c = new LevelCharge(level.getLevelIndex(), level.getDbName(), level.getPartnerId(), level.getChargeAccountId(), level.getUom(),
                null, level.getReservedAmount(), level.getBalanceBefore(), level.getBalanceAfter(), level.getDebitReference(), false);
            try {
                billing.credit(c, level.getDebitReference() + "#C", "compensation:" + why);
                log.info("[COMPENSATION] {} | tier {} ({}) partner {}: {} {} credited back ({})", requestId, level.getLevelIndex(), level.getDbName(),
                    level.getPartnerId(), level.getReservedAmount(), level.getUom(), why);
            } catch (RuntimeException e) {
                log.error("[COMPENSATION] {} | tier {} ({}) partner {}: credit of {} {} FAILED: {}", requestId, level.getLevelIndex(), level.getDbName(),
                    level.getPartnerId(), level.getReservedAmount(), level.getUom(), e.toString());
            }
        }
    }

    /** {@code MultiLevelTaskBuilder.findResellerPartnerId}: the parent's partner of type RESELLER whose name is the child tenant's name or db name. */
    static Partner resellerPartnerOf(Tenant child, Tenant parent) {
        if (child == null || parent == null || parent.getContext() == null || parent.getContext().getPartners() == null) return null;
        String childName = child.getName() == null || child.getName().isBlank() ? child.getDbName() : child.getName();
        for (Partner p : parent.getContext().getPartners().values()) {
            if (p == null || !PartnerType.isReseller(p.getPartnerType())) continue;
            if (matchesTenantName(p.getPartnerName(), childName, child.getDbName())) return p;
        }
        return null;
    }

    private static boolean matchesTenantName(String partnerName, String tenantName, String tenantDbName) {
        if (partnerName == null) return false;
        if (partnerName.equalsIgnoreCase(tenantName) || partnerName.equalsIgnoreCase(tenantDbName)) return true;
        return tenantDbName != null && tenantDbName.toLowerCase().startsWith(partnerName.toLowerCase());
    }

    private boolean acquireSlot(Partner partner) {
        Integer cap = partner.getMaxConcurrentChannels();
        if (cap == null || cap <= 0) return true;
        AtomicInteger n = activeByPartner.computeIfAbsent(partner.getIdPartner(), k -> new AtomicInteger());
        int now = n.incrementAndGet();
        if (now > cap) {
            n.decrementAndGet();
            return false;
        }
        return true;
    }

    private void releaseSlot(int partnerId) {
        AtomicInteger n = activeByPartner.get(partnerId);
        if (n != null) n.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    private static String calledOf(AdCallPayload p) {
        return p.terminatingCalledNumber() != null ? p.terminatingCalledNumber() : p.originatingCalledNumber();
    }
}
