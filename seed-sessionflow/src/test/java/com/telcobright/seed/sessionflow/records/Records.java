package com.telcobright.seed.sessionflow.records;

import com.telcobright.seed.sessionflow.api.CdrEvent;

import java.math.BigDecimal;

/** The records of the records road's tests: an ad view's tier records as the base assembles them (the wire of ad-is-a-call §4.1). */
final class Records {

    private Records() {}

    /** One tier's record of a shown ad view: {@code root > … > tier}, partner {@code partnerId}, 0.50 charged, 15 s watched. */
    static CdrEvent adView(String sessionId, String hierarchy, int partnerId) {
        CdrEvent cdr = new CdrEvent();
        String[] nodes = hierarchy.split(" > ");
        cdr.tenant = nodes[nodes.length - 1];
        cdr.resellerHierarchy = hierarchy;
        cdr.sequenceNo = 1_791_000_000_000_000L + Math.abs(sessionId.hashCode() % 1000);
        cdr.callId = sessionId;
        cdr.channelCallUuid = sessionId;
        cdr.serviceGroup = 30;
        cdr.startTime = "2026-10-23 17:10:05";
        cdr.answerTime = "2026-10-23 17:10:06";
        cdr.endTime = "2026-10-23 17:10:21";
        cdr.durationSec = new BigDecimal("15.0");
        cdr.originatingCallingNumber = "8801711000001";
        cdr.terminatingCallingNumber = "8801711000001";
        cdr.originatingCalledNumber = "R701";
        cdr.terminatingCalledNumber = "R701";
        cdr.callerIp = "10.20.0.1";
        cdr.hangupCause = "NORMAL_CLEARING";
        cdr.channelReadCodecName = "video";
        cdr.pdd = 1.0f;
        cdr.inPartnerId = partnerId;
        cdr.isPrepaid = 1;
        cdr.incomingRoute = "cola-eid";
        cdr.matchPrefixCustomer = "R701";
        cdr.callRatePerMinBDT = new BigDecimal("0.50");
        cdr.inPartnerUom = "BDT";
        cdr.idPackageAccount = 3044L;
        cdr.inPartnerCost = new BigDecimal("0.50");
        cdr.packageAmount = BigDecimal.ZERO;
        cdr.additionalMetaData = "{\"campaignId\":71,\"zone\":\"zone-701\",\"completed\":true,\"credited\":true}";
        return cdr;
    }

    /** The same session's record of a call (no stated group: billing detects it, the switch rates nothing). */
    static CdrEvent call(String sessionId, String hierarchy, int partnerId) {
        CdrEvent cdr = adView(sessionId, hierarchy, partnerId);
        cdr.serviceGroup = null;
        cdr.channelReadCodecName = "PCMU";
        return cdr;
    }
}
