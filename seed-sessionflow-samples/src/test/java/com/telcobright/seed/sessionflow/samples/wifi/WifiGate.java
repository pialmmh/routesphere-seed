package com.telcobright.seed.sessionflow.samples.wifi;

import com.telcobright.statewalk.session.Route;

/** The WiFi session's one hop: the gate of the zone — the gateway to open the device on and the policy (the package's speed class). */
public record WifiGate(String gateId, String gateway, String policy) implements Route {}
