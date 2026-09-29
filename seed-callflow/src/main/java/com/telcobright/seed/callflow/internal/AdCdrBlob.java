package com.telcobright.seed.callflow.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.callflow.api.AdCallPayload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * The summary outbox blob of ONE ad call — summary-service's PINNED envelope, v2: {@code base64(gzip(JSON array of
 * {Cdr, Chargeables:[every tier's leg]}))}. The {@code Cdr} half carries billing's PascalCase names the voice decoder
 * reads ({@code InPartnerId, OutPartnerId, IncomingRoute, OutgoingRoute, StartTime, ConnectTime, DurationSec,
 * MatchedPrefixCustomer, ChargingStatus}) plus the ad facts the ad category keys on ({@code Tenant, CampaignId, RuleCode,
 * Zone, Site, App, MediaKind, Outcome, HangupCause …}); each {@code Chargeable} leg is one TIER ({@code servicegroup 30},
 * {@code assignedDirection 1}, {@code Tenant}, {@code PartnerId}, {@code LevelIndex}, {@code BilledAmount}, {@code Quantity}).
 */
public final class AdCdrBlob {

    public static final String ENTITY_TYPE = "ad_cdr";
    public static final int SERVICE_GROUP_AD = 30;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private AdCdrBlob() {}

    /** The JSON array (one entry: the call with every tier as a leg), before packing. */
    public static String json(AdCallPayload p, List<LevelAdmission> levels, String cause, boolean answered, String outcome, ZoneId zone) {
        ArrayNode batch = JSON.createArrayNode();
        ObjectNode entry = batch.addObject();
        ObjectNode cdr = entry.putObject("Cdr");
        cdr.put("SwitchId", 0);
        cdr.put("SessionId", p.uniqueId());
        cdr.put("Tenant", p.tenantName());
        put(cdr, "InPartnerId", p.inPartnerId());
        put(cdr, "OutPartnerId", p.outPartnerId());
        cdr.put("IncomingRoute", p.incomingRouteName());
        cdr.put("OutgoingRoute", p.outgoingRouteName());
        cdr.put("StartTime", local(p.startTimeMillis(), zone));
        if (p.answerTimeMillis() > 0) cdr.put("ConnectTime", local(p.answerTimeMillis(), zone));
        if (p.endTimeMillis() > 0) cdr.put("EndTime", local(p.endTimeMillis(), zone));
        cdr.put("DurationSec", p.billsec());
        cdr.put("ChargingStatus", levels.isEmpty() ? 0 : 1);
        cdr.put("NERSuccess", answered ? 1 : 0);
        cdr.put("MatchedPrefixCustomer", levels.isEmpty() ? null : levels.get(0).getRatePrefix());
        cdr.put("OriginatingCallingNumber", p.originatingCallingNumber());
        cdr.put("OriginatingCalledNumber", p.originatingCalledNumber());
        cdr.put("ServiceGroup", SERVICE_GROUP_AD);
        put(cdr, "CampaignId", p.campaignId());
        cdr.put("CampaignName", p.campaignName());
        cdr.put("ContentId", p.contentId());
        put(cdr, "ContentPartnerId", p.contentPartnerId());
        cdr.put("RuleCode", p.ruleCode());
        cdr.put("Zone", p.zone());
        cdr.put("Site", p.site());
        cdr.put("District", p.district());
        cdr.put("Gw", p.gw());
        cdr.put("App", p.app());
        cdr.put("MediaKind", p.mediaKind());
        cdr.put("RequiredSeconds", p.requiredSeconds());
        cdr.put("Answered", answered);
        cdr.put("Outcome", outcome);
        cdr.put("HangupCause", cause);
        cdr.put("Fallback", p.fallback());
        ArrayNode legs = entry.putArray("Chargeables");
        for (LevelAdmission level : levels) {
            ObjectNode leg = legs.addObject();
            leg.put("servicegroup", SERVICE_GROUP_AD);
            leg.put("servicefamily", SERVICE_GROUP_AD);
            leg.put("assignedDirection", 1);
            leg.put("ProductId", p.campaignId() == null ? 0L : p.campaignId().longValue());
            leg.put("idBilledUom", level.getUom());
            leg.put("Prefix", level.getRatePrefix() == null ? "" : level.getRatePrefix());
            leg.put("transactionTime", local(p.startTimeMillis(), zone));
            leg.put("unitPriceOrCharge", nz(level.getRate()));
            leg.put("BilledAmount", nz(level.getReservedAmount()));
            leg.put("Quantity", level.getUsageSeconds() > 0 && level.getUom() != null && level.getUom().equalsIgnoreCase("AD_sec")
                ? BigDecimal.valueOf(level.getUsageSeconds()) : BigDecimal.ONE);
            leg.put("Tenant", level.getDbName());
            leg.put("PartnerId", level.getPartnerId());
            leg.put("LevelIndex", level.getLevelIndex());
            leg.put("ResellerHierarchy", LevelCdrWriter.hierarchyOf(level));
            leg.put("Reference", level.getDebitReference());
        }
        return batch.toString();
    }

    /** {@code base64(gzip(UTF-8 JSON))} — exactly what summary-service's {@code OutboxCodec.decode} undoes. */
    public static String pack(String json) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not pack the summary blob", e);
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    public static String local(long epochMs, ZoneId zone) {
        return TS.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone));
    }

    private static void put(ObjectNode n, String f, Integer v) { if (v == null) n.putNull(f); else n.put(f, v); }
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
