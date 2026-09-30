package com.telcobright.seed.callflow.testkit;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.mysqlentity.RatePlan;
import com.telcobright.rtc.domainmodel.mysqlentity.Route;
import com.telcobright.rtc.domainmodel.mysqlentity.ad.AdCaller;
import com.telcobright.rtc.domainmodel.mysqlentity.ad.AdContent;
import com.telcobright.rtc.domainmodel.mysqlentity.ad.AdRate;
import com.telcobright.rtc.domainmodel.mysqlentity.ad.AdRule;
import com.telcobright.rtc.domainmodel.mysqlentity.ad.AdRuleCriterion;
import com.telcobright.rtc.domainmodel.mysqlentity.ad.CampaignContent;
import com.telcobright.rtc.domainmodel.mysqlentity.ad.CampaignSla;
import com.telcobright.rtc.domainmodel.mysqlentity.ad.RouteVsCampaign;
import com.telcobright.rtc.domainmodel.nonentity.DynamicContext;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tenant tree built in memory for the tests of every product on this kit (the story of design §5): tenants with
 * partners, resellers (type 100 + a child tenant), ad rate plans and rows, routes bound to campaigns, rules, callers,
 * settings, contents. {@link #build()} rebuilds the index and the ancestor chains exactly as {@code TenantHierarchyInitializer} does.
 */
public final class TenantTreeBuilder {

    /** One tenant under construction. */
    public final class TenantSpec {
        final String dbName;
        final String parent;
        final Map<Integer, Partner> partners = new LinkedHashMap<>();
        final Map<String, List<RatePlan>> partnerPlans = new LinkedHashMap<>();
        final Map<Integer, RatePlan> plans = new LinkedHashMap<>();
        final Map<Integer, List<AdRate>> adRates = new LinkedHashMap<>();
        final Map<Integer, Route> routes = new LinkedHashMap<>();
        final Map<Integer, RouteVsCampaign> routeVsCampaign = new LinkedHashMap<>();
        final List<AdRule> rules = new ArrayList<>();
        final Map<String, AdCaller> callers = new LinkedHashMap<>();
        final Map<String, String> settings = new LinkedHashMap<>();
        final Map<Integer, List<CampaignContent>> members = new LinkedHashMap<>();
        final Map<String, AdContent> contents = new LinkedHashMap<>();
        final Map<Integer, CampaignSla> slas = new LinkedHashMap<>();
        long rateIds = 0, criterionIds = 0, memberIds = 0, bindingIds = 0;

        TenantSpec(String dbName, String parent) { this.dbName = dbName; this.parent = parent; }

        public TenantSpec partner(int id, String name, PartnerType type) { return partner(id, name, type, null); }

        /** A partner; {@code maxConcurrent} = the advertiser's concurrent views cap (null = none). */
        public TenantSpec partner(int id, String name, PartnerType type, Integer maxConcurrent) {
            Partner p = new Partner();
            p.setIdPartner(id);
            p.setPartnerName(name);
            p.setPartnerType(type.code());
            p.setCustomerPrePaid(1);
            p.setField2(maxConcurrent);
            partners.put(id, p);
            return this;
        }

        public TenantSpec deactivate(int partnerId) { partners.get(partnerId).setStatus("DEACTIVATED"); return this; }

        /** An ad rate plan assigned to a partner (the assignment open-ended, priority in the order given). */
        public TenantSpec plan(int planId, String name, int partnerId, Integer roundDecimals) {
            RatePlan plan = new RatePlan();
            plan.setId(planId);
            plan.setRatePlanName(name);
            plan.setCurrency("BDT");
            plan.setCategory(3);
            plan.setRateAmountRoundupDecimal(roundDecimals);
            plans.put(planId, plan);
            partnerPlans.computeIfAbsent(String.valueOf(partnerId), k -> new ArrayList<>()).add(plan);
            return this;
        }

        public TenantSpec perView(int planId, String prefix, String media, String amount) { return rate(planId, prefix, media, amount, AdRate.UNIT_VIEW, 1, 0); }

        public TenantSpec perSecond(int planId, String prefix, String media, String amountPerSecond) { return rate(planId, prefix, media, amountPerSecond, AdRate.UNIT_SECOND, 1, 0); }

        public TenantSpec rate(int planId, String prefix, String media, String amount, String unit, int resolution, int surchargeSec) {
            AdRate r = new AdRate();
            r.setId(++rateIds);
            r.setIdRatePlan(planId);
            r.setPrefix(prefix);
            r.setMedia(media);
            r.setRateAmount(new BigDecimal(amount));
            r.setUnit(unit);
            r.setResolution(resolution);
            r.setSurchargeTime(surchargeSec);
            r.setStartDate(LocalDateTime.of(2020, 1, 1, 0, 0));
            adRates.computeIfAbsent(planId, k -> new ArrayList<>()).add(r);
            return this;
        }

        /** A campaign's route (protocol ad-campaign, field2 6) bound to the campaign. */
        public TenantSpec campaignRoute(int routeId, String routeName, int partnerId, int campaignId) {
            Route r = new Route();
            r.setIdroute(routeId);
            r.setRouteName(routeName);
            r.setSwitchId(1);
            r.setIdPartner(partnerId);
            r.setField2(6);
            routes.put(routeId, r);
            routeVsCampaign.put(routeId, new RouteVsCampaign(++bindingIds, routeId, campaignId));
            return this;
        }

        public TenantSpec rule(long id, String code, String name, int priority, String... criteria) {
            AdRule r = new AdRule();
            r.setId(id);
            r.setCode(code);
            r.setName(name);
            r.setPriority(priority);
            r.setEnabled(true);
            List<AdRuleCriterion> cs = new ArrayList<>();
            int n = 0;
            for (String c : criteria) {
                String[] parts = c.trim().split("\\s+", 3);
                cs.add(new AdRuleCriterion(++criterionIds, id, parts[0], parts[1], parts.length > 2 ? parts[2] : "", n++));
            }
            r.setCriteria(cs);
            rules.add(r);
            return this;
        }

        public TenantSpec caller(String app, long partnerId, String callSrc) {
            callers.put(app, new AdCaller(app, partnerId, callSrc, null));
            return this;
        }

        public TenantSpec setting(String key, String value) { settings.put(key, value); return this; }

        public TenantSpec content(String id, int partnerId, String kind, int seconds, String mediaRef) {
            AdContent c = new AdContent();
            c.setId(id);
            c.setIdPartner((long) partnerId);
            c.setName(id);
            c.setKind(kind);
            c.setDurationSec(seconds);
            c.setMediaRef(mediaRef);
            c.setStatus(AdContent.STATUS_APPROVED);
            contents.put(id, c);
            return this;
        }

        public TenantSpec member(int campaignId, String contentId, double sharePercent) {
            members.computeIfAbsent(campaignId, k -> new ArrayList<>()).add(new CampaignContent(++memberIds, campaignId, contentId, BigDecimal.valueOf(sharePercent), members.getOrDefault(campaignId, List.of()).size()));
            return this;
        }

        public TenantSpec sla(int campaignId, String type) {
            slas.put(campaignId, new CampaignSla(campaignId, type, null, null, null, null, null));
            return this;
        }

        public TenantTreeBuilder and() { return TenantTreeBuilder.this; }

        DynamicContext context() {
            Map<Integer, RouteVsCampaign> byCampaign = new HashMap<>();
            routeVsCampaign.values().forEach(b -> byCampaign.put(b.getCampaignId(), b));
            Map<String, AdRule> byCode = new HashMap<>();
            rules.forEach(r -> byCode.putIfAbsent(r.getCode(), r));
            Map<Integer, List<Route>> partnerVsRoutes = new HashMap<>();
            routes.values().forEach(r -> partnerVsRoutes.computeIfAbsent(r.getIdPartner(), k -> new ArrayList<>()).add(r));
            return new DynamicContext(Map.of(), partners, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), partnerPlans, plans, Map.of(), List.of(), List.of(),
                partnerVsRoutes, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                routes, routeVsCampaign, byCampaign, rules, byCode, callers, settings, adRates, members, contents, slas);
        }
    }

    private final Map<String, TenantSpec> specs = new LinkedHashMap<>();

    public TenantSpec root(String dbName) { return tenant(dbName, null); }

    public TenantSpec tenant(String dbName, String parentDbName) {
        TenantSpec s = new TenantSpec(dbName, parentDbName);
        specs.put(dbName, s);
        return s;
    }

    /** The root tenant, index rebuilt, chains computed — exactly what routesphere-core holds after a load. */
    public Tenant build() {
        Map<String, Tenant> built = new LinkedHashMap<>();
        Tenant root = null;
        for (TenantSpec s : specs.values()) {
            Tenant t = new Tenant(s.dbName);
            t.setName(s.dbName);
            t.setContext(s.context());
            built.put(s.dbName, t);
            if (s.parent == null && root == null) root = t;
        }
        for (TenantSpec s : specs.values()) {
            if (s.parent == null) continue;
            Tenant parent = built.get(s.parent);
            if (parent == null) throw new IllegalStateException("no parent " + s.parent + " for " + s.dbName);
            Tenant child = built.get(s.dbName);
            child.setParent(s.parent);
            parent.addChild(s.dbName, child);
        }
        if (root == null) throw new IllegalStateException("no root tenant");
        root.rebuildTenantIndex();
        root.computeAncestorChains();
        return root;
    }
}
