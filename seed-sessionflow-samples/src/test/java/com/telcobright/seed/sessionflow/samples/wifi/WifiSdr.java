package com.telcobright.seed.sessionflow.samples.wifi;

/** The WiFi session's record (the SDR the design sends to Kafka): who, where, how long, how it ended. */
public record WifiSdr(String sessionId, String root, String msisdn, String mac, String zone, double seconds, String outcome, String endCause) {}
