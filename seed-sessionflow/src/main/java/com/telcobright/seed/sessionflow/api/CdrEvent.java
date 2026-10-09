package com.telcobright.seed.sessionflow.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ONE tier's record of a call on the CDR wire — the contract ratified in routesphere
 * {@code docs/architecture/ad-is-a-call.md} §4 (billing-core's {@code CdrEvent}, with the amendments). A call is one
 * Kafka message: the JSON array of its tier records, the leaf first, keyed by {@link #channelCallUuid}.
 *
 * <p>The field names ARE the wire names. The three times are {@code yyyy-MM-dd HH:mm:ss}, the wall clock of the root
 * tenant's zone. {@link #answerTime} is sent as null for a call never answered; every other null field is left out.
 *
 * <p>An application puts its own facts into {@link #meta}; the base sends them as one JSON object in
 * {@link #additionalMetaData}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CdrEvent {

    /** The tier's schema — where billing writes this record. The last node of {@link #resellerHierarchy}. */
    public String tenant;
    /** {@code root > … > this tier}. */
    public String resellerHierarchy;
    /** The producer's own running number: for order and diagnosis only. Billing is idempotent on (tenant, channelCallUuid). */
    public Long sequenceNo;
    public String callId;
    public String channelCallUuid;
    /** Absent or 0 = billing detects the group (a voice call). 30 = an ad view, taken as given. */
    public Integer serviceGroup;

    public String startTime;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public String answerTime;
    public String endTime;
    public BigDecimal durationSec;

    public String originatingCallingNumber;
    public String terminatingCallingNumber;
    public String originatingCalledNumber;
    public String terminatingCalledNumber;
    public String callerIp;
    public String receiverIp;
    public String hangupCause;
    public String channelReadCodecName;
    public Float pdd;

    public Integer inPartnerId;
    public Integer outPartnerId;
    /** 1 = prepaid, 2 = postpaid, 0 = unknown. */
    public Integer isPrepaid;
    public String incomingRoute;
    public String outgoingRoute;

    public String matchPrefixCustomer;
    public String supplierPrefix;
    public Integer ansIdTerm;
    public String ansPrefixTerm;
    public Integer ansIdOrig;
    public String ansPrefixOrig;

    public BigDecimal callRatePerMinBDT;
    public String inPartnerUom;
    public Long idPackageAccount;
    /** What the settle step charged in money. 0 when the tier paid in package units. */
    public BigDecimal inPartnerCost;
    /** What the settle step charged in package units. 0 when the tier paid in money. */
    public BigDecimal packageAmount;
    public BigDecimal supplierCost;
    public BigDecimal costIcxIn;
    public BigDecimal costAnsIn;
    public BigDecimal revenueAnsOut;
    public BigDecimal revenueIgwOut;

    /** One JSON object as a string: the application's own facts. Built by the base from {@link #meta}. */
    public String additionalMetaData;

    /** The application's own facts, key by key. Not on the wire: the base turns it into {@link #additionalMetaData}. */
    @JsonIgnore
    public final Map<String, Object> meta = new LinkedHashMap<>();
}
