package com.telcobright.seed.sessionflow.spi;

import com.telcobright.seed.sessionflow.api.RootTreeInstaller;

/** P1-O · The trees of every lookup that installs nothing of its own ({@link TenantLookup#snapshot}'s default): each tree object once. */
final class InstalledOnFirstAsk {

    static final RootTreeInstaller TREES = new RootTreeInstaller();

    private InstalledOnFirstAsk() {}
}
