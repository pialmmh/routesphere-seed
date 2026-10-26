package com.telcobright.seed.prepaid.parity;

import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;

/** The parity call's facts as its wire reads them: who pays (by partner id, inside the root the ESL socket names), what was dialed. */
public record CallFacts(String sessionId, long atMs, Ingress ingress, int partnerId, String called) implements RoutesphereFacts {}
