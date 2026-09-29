package com.telcobright.seed.callflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;

import java.util.List;

/**
 * The frozen contract between the two ad streams and the CDR writer (design §2.4, 2026-09-29): an ad view INVERTED into
 * a call payload — a plain record, no framework, mapped 1:1 onto the {@code CallOrSmsTask} / {@code CampaignTask} names so
 * the CDR generator and the summary bean read familiar fields.
 *
 * <pre>
 *   incoming route            the campaign's route (protocol ad-campaign = field2 6, bound by route_vs_campaign)
 *   in-partner (who pays)     the advertiser: the drawn content's owner, else the campaign's owner
 *   outgoing route            the requesting zone (or site)
 *   out-partner (supplier)    the operator's network division (ad_setting out-partner)
 *   originatingCalledNumber   the matched RULE's code (the prefix the rate book and the dialplan match on)
 *   originatingCallingNumber  the MSISDN, else the MAC
 * </pre>
 *
 * {@code answerTimeMillis} = the ad reached the screen (shown), {@code billsec} = watched seconds, {@code hangupCause} = the
 * cause code ({@link AdCause}). {@code levels} is filled by admission (one {@link LevelAdmission} per tier, leaf first).
 */
public record AdCallPayload(
    String uniqueId,                 // the ad session id = the CDR's channel uuid
    String taskType,                 // "AD"
    String tenantName,               // the entry (leaf) tenant database
    String app, Integer callSrcId, String callSrcName,
    String originatingCallingNumber, // msisdn, else mac
    String originatingCalledNumber,  // the rule's code (the prefix)
    String terminatingCallingNumber, // = originatingCallingNumber
    String terminatingCalledNumber,  // = originatingCalledNumber (no digit filter for ads)
    Long ruleId, String ruleCode, String ruleName,
    Integer inPartnerId,             // the advertiser who pays (content owner, else campaign owner)
    Integer incomingRouteId, String incomingRouteName,   // the campaign's route
    Integer campaignId, String campaignName, String contentId, Integer contentPartnerId,
    String mediaKind, String mediaRef, int requiredSeconds,
    String outgoingRouteName,        // the zone, or the site when the winning rule named a site
    Integer outPartnerId,            // the tenant's network-division partner (ad_setting out-partner)
    Integer dialplanId, String dialplanName, String matchedDialplanPrefix, int routePriority, boolean fallback,
    String zone, String site, String district, String gw, String mac, String ip, String msisdn, String uaFamily, String wifiSessionId,
    long startTimeMillis, long answerTimeMillis, long endTimeMillis, int billsec, boolean answered, String hangupCause,
    List<LevelAdmission> levels)     // filled by admission
{
    public static final String TASK_TYPE_AD = "AD";

    public AdCallPayload {
        levels = levels == null ? List.of() : List.copyOf(levels);
    }

    /** The same payload with the admission's tiers. */
    public AdCallPayload withLevels(List<LevelAdmission> newLevels) {
        return new AdCallPayload(uniqueId, taskType, tenantName, app, callSrcId, callSrcName, originatingCallingNumber, originatingCalledNumber,
            terminatingCallingNumber, terminatingCalledNumber, ruleId, ruleCode, ruleName, inPartnerId, incomingRouteId, incomingRouteName,
            campaignId, campaignName, contentId, contentPartnerId, mediaKind, mediaRef, requiredSeconds, outgoingRouteName, outPartnerId,
            dialplanId, dialplanName, matchedDialplanPrefix, routePriority, fallback, zone, site, district, gw, mac, ip, msisdn, uaFamily, wifiSessionId,
            startTimeMillis, answerTimeMillis, endTimeMillis, billsec, answered, hangupCause, newLevels);
    }

    /** The same payload with the lifecycle facts the terminal write needs. */
    public AdCallPayload withLifecycle(long answerTimeMillis, long endTimeMillis, int billsec, boolean answered, String hangupCause) {
        return new AdCallPayload(uniqueId, taskType, tenantName, app, callSrcId, callSrcName, originatingCallingNumber, originatingCalledNumber,
            terminatingCallingNumber, terminatingCalledNumber, ruleId, ruleCode, ruleName, inPartnerId, incomingRouteId, incomingRouteName,
            campaignId, campaignName, contentId, contentPartnerId, mediaKind, mediaRef, requiredSeconds, outgoingRouteName, outPartnerId,
            dialplanId, dialplanName, matchedDialplanPrefix, routePriority, fallback, zone, site, district, gw, mac, ip, msisdn, uaFamily, wifiSessionId,
            startTimeMillis, answerTimeMillis, endTimeMillis, billsec, answered, hangupCause, levels);
    }

    /**
     * The same payload inverted for another payer / content / route (ADMITTING tries the candidates in order; each try re-inverts).
     */
    public AdCallPayload withCandidate(Integer inPartnerId, Integer incomingRouteId, String incomingRouteName, Integer campaignId, String campaignName,
                                       String contentId, Integer contentPartnerId, String mediaKind, String mediaRef, int requiredSeconds,
                                       int routePriority, boolean fallback) {
        return new AdCallPayload(uniqueId, taskType, tenantName, app, callSrcId, callSrcName, originatingCallingNumber, originatingCalledNumber,
            terminatingCallingNumber, terminatingCalledNumber, ruleId, ruleCode, ruleName, inPartnerId, incomingRouteId, incomingRouteName,
            campaignId, campaignName, contentId, contentPartnerId, mediaKind, mediaRef, requiredSeconds, outgoingRouteName, outPartnerId,
            dialplanId, dialplanName, matchedDialplanPrefix, routePriority, fallback, zone, site, district, gw, mac, ip, msisdn, uaFamily, wifiSessionId,
            startTimeMillis, answerTimeMillis, endTimeMillis, billsec, answered, hangupCause, List.of());
    }

    /** A payload for a view that never reached a candidate (a failed CDR: the entry tenant, the advertiser when known). */
    public static AdCallPayload minimal(String uniqueId, String tenantName, String app, String calling, String called, long startTimeMillis) {
        return new AdCallPayload(uniqueId, TASK_TYPE_AD, tenantName, app, null, null, calling, called, calling, called,
            null, null, null, null, null, null, null, null, null, null, null, null, 0, null, null, null, null, null, 0, false,
            null, null, null, null, null, null, null, null, null, startTimeMillis, 0, 0, 0, false, null, List.of());
    }
}
