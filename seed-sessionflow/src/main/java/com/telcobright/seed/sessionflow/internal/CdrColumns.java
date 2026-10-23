package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.RecordId;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * billing-core's {@code cdr} row of one tier record (P1-R §A): its columns in billing-core's order — the legacy 104 ({@code cdr.ExtInsertColumns})
 * and, on PostgreSQL, the ratified wire's six ({@code CdrRowSql.WireColumns}) — and each value as billing-core's ingest maps the wire onto it
 * ({@code CdrEventPreprocessor.Map}), with the two durations its pre-rated family stamps on a stated group ({@code SfPreRated}). A column
 * billing-core leaves empty is null here too. The names are billing-core's field names, so the same map is the summary blob's {@code Cdr}.
 */
public final class CdrColumns {

    /** The legacy 104, billing-core {@code cdr.ExtInsertColumns}, in its order. */
    public static final String LEGACY =
        "SwitchId,IdCall,SequenceNumber,FileName,ServiceGroup,IncomingRoute,OriginatingIP,OPC,OriginatingCIC,"
        + "OriginatingCalledNumber,TerminatingCalledNumber,OriginatingCallingNumber,TerminatingCallingNumber,"
        + "PrePaid,DurationSec,EndTime,ConnectTime,AnswerTime,ChargingStatus,PDD,CountryCode,AreaCodeOrLata,"
        + "ReleaseDirection,ReleaseCauseSystem,ReleaseCauseEgress,OutgoingRoute,TerminatingIP,DPC,TerminatingCIC,"
        + "StartTime,InPartnerId,CustomerRate,OutPartnerId,SupplierRate,MatchedPrefixY,UsdRateY,MatchedPrefixCustomer,"
        + "MatchedPrefixSupplier,InPartnerCost,OutPartnerCost,CostAnsIn,CostIcxIn,Tax1,IgwRevenueIn,RevenueAnsOut,"
        + "RevenueIgwOut,RevenueIcxOut,Tax2,XAmount,YAmount,AnsPrefixOrig,AnsIdOrig,AnsPrefixTerm,AnsIdTerm,ValidFlag,"
        + "PartialFlag,ReleaseCauseIngress,InRoamingOpId,OutRoamingOpId,CalledPartyNOA,CallingPartyNOA,"
        + "AdditionalSystemCodes,AdditionalPartyNumber,ResellerIds,ZAmount,PreviousRoutes,E1Id,MediaIp1,MediaIp2,"
        + "MediaIp3,MediaIp4,CallReleaseDuration,E1IdOut,InTrunkAdditionalInfo,OutTrunkAdditionalInfo,InMgwId,OutMgwId,"
        + "MediationComplete,Codec,ConnectedNumberType,RedirectingNumber,CallForwardOrRoamingType,OtherDate,"
        + "SummaryMetaTotal,TransactionMetaTotal,ChargeableMetaTotal,ErrorCode,NERSuccess,RoundedDuration,"
        + "PartialDuration,PartialAnswerTime,PartialEndTime,FinalRecord,Duration1,Duration2,Duration3,Duration4,"
        + "PreviousPeriodCdr,UniqueBillId,AdditionalMetaData,Category,SubCategory,ChangedByJobId,SignalingStartTime";

    /** The ratified wire's own six, billing-core {@code CdrRowSql.WireColumns}: written on PostgreSQL only (the MySQL tables lack them). */
    public static final String WIRE = "ResellerHierarchy,ChannelCallUuid,HangupCause,InPartnerUom,IdPackageAccount,PackageAmount";

    /** Where a row came from. billing-core writes {@code kafka:cdr} (its topic); this road writes its own word. */
    public static final String FILE_NAME = "switch:records";

    /** The groups a record STATES and billing-core takes as given (an ad view, a domestic WiFi session). */
    public static final int AD_VIEW = 30, WIFI_DOMESTIC = 101;

    /** billing-core's primitives: always in its blob, 0 when unset. */
    private static final List<String> ALWAYS_WRITTEN = List.of("SwitchId", "IdCall", "SequenceNumber", "ServiceGroup");
    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> LEGACY_COLUMNS = List.of(LEGACY.split(","));
    private static final List<String> ALL_COLUMNS = concat(LEGACY_COLUMNS, List.of(WIRE.split(",")));
    private static final Map<String, Function<Row, Object>> VALUES = values();

    /** One tier record as a row: the record, the switch id of its store, its IdCall. */
    private record Row(CdrEvent e, int switchId, RecordId idCall) {}

    private CdrColumns() {}

    /** The columns written: the 104 on MySQL, the 110 on PostgreSQL. */
    public static List<String> columns(boolean withTheWire) { return withTheWire ? ALL_COLUMNS : LEGACY_COLUMNS; }

    /** The values of {@link #columns}, in its order; null where billing-core writes none. */
    public static List<Object> valuesOf(CdrEvent record, int switchId, RecordId idCall, boolean withTheWire) {
        Row row = new Row(record, switchId, idCall);
        List<Object> out = new ArrayList<>(columns(withTheWire).size());
        for (String column : columns(withTheWire)) out.add(valueOf(column, row));
        return out;
    }

    /** All 110 by billing-core's names, the empty ones left out but for its primitives — the summary blob's {@code Cdr}. */
    public static Map<String, Object> asSummaryCdr(CdrEvent record, int switchId, RecordId idCall) {
        Row row = new Row(record, switchId, idCall);
        Map<String, Object> cdr = new LinkedHashMap<>();
        for (String column : ALL_COLUMNS) {
            Object value = valueOf(column, row);
            if (value == null && ALWAYS_WRITTEN.contains(column)) value = 0;
            if (value != null) cdr.put(column, value);
        }
        return cdr;
    }

    /** The group billing-core writes: a stated 30 or 101 as given; anything else 0 (billing detects a call's group). */
    public static int serviceGroupOf(CdrEvent e) {
        return e.serviceGroup != null && (e.serviceGroup == AD_VIEW || e.serviceGroup == WIFI_DOMESTIC) ? e.serviceGroup : 0;
    }

    /** The tenant's wall clock of the wire ({@code yyyy-MM-dd HH:mm:ss}) as a time; null stays null. */
    public static LocalDateTime timeOf(String wallClock) { return wallClock == null ? null : LocalDateTime.parse(wallClock, WALL_CLOCK); }

    private static Object valueOf(String column, Row row) {
        Function<Row, Object> value = VALUES.get(column);
        return value == null ? null : value.apply(row);
    }

    /** {@code CdrEventPreprocessor.Map} column by column; the lines cited are billing-core's (postgres-ad-call). */
    private static Map<String, Function<Row, Object>> values() {
        Map<String, Function<Row, Object>> v = new HashMap<>();
        v.put("SwitchId", r -> r.switchId());
        v.put("IdCall", Row::idCall);
        v.put("SequenceNumber", r -> r.e().sequenceNo);
        v.put("FileName", r -> FILE_NAME);
        v.put("ServiceGroup", r -> serviceGroupOf(r.e()));
        v.put("IncomingRoute", r -> blank(r.e().incomingRoute) ? r.e().receiverIp : r.e().incomingRoute);
        v.put("OriginatingIP", r -> r.e().callerIp);
        v.put("OriginatingCalledNumber", r -> r.e().originatingCalledNumber);
        v.put("TerminatingCalledNumber", r -> r.e().terminatingCalledNumber);
        v.put("OriginatingCallingNumber", r -> r.e().originatingCallingNumber);
        v.put("TerminatingCallingNumber", r -> r.e().terminatingCallingNumber);
        v.put("PrePaid", r -> r.e().isPrepaid);
        v.put("DurationSec", r -> r.e().durationSec);
        v.put("EndTime", r -> timeOf(r.e().endTime));
        v.put("ConnectTime", r -> timeOf(r.e().answerTime));
        v.put("AnswerTime", r -> timeOf(r.e().answerTime));
        v.put("ChargingStatus", r -> chargingStatusOf(r.e()));
        v.put("PDD", r -> r.e().pdd);
        v.put("AreaCodeOrLata", r -> r.e().hangupCause);
        v.put("OutgoingRoute", r -> blank(r.e().outgoingRoute) ? r.e().callerIp : r.e().outgoingRoute);
        v.put("TerminatingIP", r -> r.e().receiverIp);
        v.put("StartTime", r -> timeOf(r.e().startTime));
        v.put("InPartnerId", r -> r.e().inPartnerId);
        v.put("CustomerRate", r -> r.e().callRatePerMinBDT);
        v.put("OutPartnerId", r -> r.e().outPartnerId);
        v.put("MatchedPrefixCustomer", r -> r.e().matchPrefixCustomer);
        v.put("MatchedPrefixSupplier", r -> r.e().supplierPrefix);
        v.put("InPartnerCost", r -> r.e().inPartnerCost);
        v.put("OutPartnerCost", r -> r.e().supplierCost);
        v.put("CostAnsIn", r -> r.e().costAnsIn);
        v.put("CostIcxIn", r -> r.e().costIcxIn);
        v.put("RevenueAnsOut", r -> r.e().revenueAnsOut);
        v.put("RevenueIgwOut", r -> r.e().revenueIgwOut);
        v.put("AnsPrefixOrig", r -> r.e().ansPrefixOrig);
        v.put("AnsIdOrig", r -> r.e().ansIdOrig);
        v.put("AnsPrefixTerm", r -> r.e().ansPrefixTerm);
        v.put("AnsIdTerm", r -> r.e().ansIdTerm);
        v.put("Codec", r -> r.e().channelReadCodecName);
        v.put("RoundedDuration", r -> stated(r.e()) ? r.e().durationSec : null);
        v.put("Duration1", r -> stated(r.e()) ? r.e().durationSec : null);
        v.put("UniqueBillId", r -> r.e().callId);
        v.put("AdditionalMetaData", r -> r.e().additionalMetaData);
        v.put("SignalingStartTime", r -> timeOf(r.e().startTime));
        v.put("ResellerHierarchy", r -> r.e().resellerHierarchy);
        v.put("ChannelCallUuid", r -> r.e().channelCallUuid);
        v.put("HangupCause", r -> r.e().hangupCause);
        v.put("InPartnerUom", r -> r.e().inPartnerUom);
        v.put("IdPackageAccount", r -> r.e().idPackageAccount);
        v.put("PackageAmount", r -> r.e().packageAmount);
        return Map.copyOf(v);
    }

    /** {@code CdrEventPreprocessor.ChargingStatusOf}: an ad view is answered when shown; anything else when it lasted. */
    private static int chargingStatusOf(CdrEvent e) {
        if (e.serviceGroup != null && e.serviceGroup == AD_VIEW) return e.answerTime != null ? 1 : 0;
        return e.durationSec != null && e.durationSec.signum() > 0 ? 1 : 0;
    }

    /** A stated group is pre-rated: no plan rounds it, so billing-core stamps the billed and the rounded duration as the real one. */
    public static boolean stated(CdrEvent e) { return serviceGroupOf(e) != 0; }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }
}
