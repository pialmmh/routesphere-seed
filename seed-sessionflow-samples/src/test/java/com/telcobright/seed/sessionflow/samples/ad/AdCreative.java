package com.telcobright.seed.sessionflow.samples.ad;

import com.telcobright.statewalk.session.Route;

/** The ad's hop: the chosen creative — the content of a campaign the view plays. The base puts it in {@code Connect}. */
public record AdCreative(String creativeId, int campaignId, String mediaKind, int seconds, String mediaRef) implements Route {}
