package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;

/** The lab call's facts as its signaling machine reads them from the dial: who pays (by partner id), where it came in, what it dialed. */
public record LabFacts(String sessionId, long atMs, Ingress ingress, int partnerId, String called) implements RoutesphereFacts {}
