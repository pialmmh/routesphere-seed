package com.telcobright.seed.sessionflow.samples.ad;

import com.telcobright.seed.sessionflow.api.Candidate;

/**
 * One way a view may be served: a campaign's content and the advertiser who pays for it ({@code payerPartnerId}, null for the house
 * ad), tried in order at admission. Persisted with the context: a record of ids and words.
 */
public record AdCandidate(int campaignId, String creativeId, String mediaKind, int seconds, Integer payerPartnerId, boolean house)
    implements Candidate {

    /** The house ad costs nothing: every tier is walked at zero, nothing is reserved. */
    @Override public boolean free() { return house; }

    public AdCreative creative() { return new AdCreative(creativeId, campaignId, mediaKind, seconds, "media/" + creativeId); }
}
