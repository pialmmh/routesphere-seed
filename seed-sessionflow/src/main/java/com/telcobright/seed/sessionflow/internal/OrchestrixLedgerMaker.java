package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.dependencies.LedgerSettings;
import com.telcobright.seed.sessionflow.dependencies.Ledgers;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LedgerPort;

/**
 * The {@code orchestrix} ledger of a root (the transition's REST roads, R12): {@code ledger: {kind: orchestrix, url, token-env, tenant,
 * profile}}; what orchestrix could not give back is written in {@code <root dir>/owed.jsonl}.
 */
public final class OrchestrixLedgerMaker implements LedgerMaker {

    @Override public String kind() { return RootResources.Ledger.ORCHESTRIX; }

    @Override
    public LedgerPort make(RootResources root, Context context) {
        RootResources.Ledger l = root.ledger();
        LedgerSettings settings = new LedgerSettings(required(l, "url", root), required(l, "token-env", root), required(l, "tenant", root), l.word("profile"),
            0, 0, context.deployment().app());
        return Ledgers.orchestrix(settings, context.environment(), root.dir().resolve("owed.jsonl"), context.clock());
    }

    private static String required(RootResources.Ledger ledger, String key, RootResources root) {
        String v = ledger.word(key);
        if (v == null) throw new IllegalStateException("the root '" + root.root() + "' has an orchestrix ledger with no '" + key + "'");
        return v;
    }
}
