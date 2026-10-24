package com.telcobright.seed.tenant.internal;

import com.telcobright.seed.sessionflow.api.RootTree;

/**
 * What a root's snapshot carries beside its tree (P1-O), built ONCE when the snapshot is installed: the base's {@link RootTree} with its
 * index (every identification of a session), and the D5 map of the ids two tiers hold ({@code tiersHoldingTwice}).
 */
public record InstalledRoot(RootTree tree, PartnerTiers partnerTiers) {}
