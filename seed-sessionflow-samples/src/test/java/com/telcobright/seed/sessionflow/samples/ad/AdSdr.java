package com.telcobright.seed.sessionflow.samples.ad;

/** The view's session record (the ad's {@code ad_sdr}): what the phone saw and how the view ended. */
public record AdSdr(String sessionId, String root, int campaignId, String creativeId, boolean handedOver, boolean shown, int watchedSeconds,
                    String outcome, String endCause) {}
