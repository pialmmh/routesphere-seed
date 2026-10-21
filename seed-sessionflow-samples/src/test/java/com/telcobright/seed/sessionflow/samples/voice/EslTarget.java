package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.statewalk.session.Route;

/**
 * The call's hop: one route of the root's dialplan as the ESL channel needs it — the target gateway's ip, the sofia profile, the
 * digit-filtered destination and the dialplan context the parked leg is transferred into; the route's name and its supplier for the CDR.
 */
public record EslTarget(String routeName, int supplierId, String targetIp, String sofiaProfile, String destination, String context)
    implements Route {}
