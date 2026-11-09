package com.telcobright.seed.switchledgerremote.dependencies;

import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledgerremote.api.RemoteSwitchLedger;

/**
 * The {@code remote} ledger kind (the owner's road B, ARCH-0116 item 3): a root whose {@code ledger.kind = remote} reads its money from the
 * switch-ledger service of its database instead of an embedded engine. Words: {@code address} (host:port, required), {@code deadline-ms}
 * (2000 unless said), {@code token-var} (required: the NAME of the environment variable holding the bearer — read ONCE here, handed to the
 * client, never logged; an empty variable refuses the start naming the variable, never a value). Found by {@code ServiceLoader} through
 * {@code META-INF/services}, as the {@code switch} and {@code orchestrix} kinds are.
 */
public final class RemoteLedgerMaker implements LedgerMaker {

    public static final String KIND = "remote";

    @Override public String kind() { return KIND; }

    @Override
    public LedgerPort make(RootResources root, Context context) {
        RemoteLedgerSettings settings = settingsOf(root);
        String bearer = bearerOf(root, settings.tokenVar(), context);
        return RemoteSwitchLedger.connect(settings, bearer);
    }

    private static RemoteLedgerSettings settingsOf(RootResources root) {
        RootResources.Ledger l = root.ledger();
        String deadline = l.word("deadline-ms");
        long deadlineMs = deadline == null || deadline.isBlank() ? RemoteLedgerSettings.DEFAULT_DEADLINE_MS : Long.parseLong(deadline.trim());
        return new RemoteLedgerSettings(required(l, "address", root), deadlineMs, required(l, "token-var", root));
    }

    /** The bearer's VALUE by the variable's NAME, from the process environment only. */
    private static String bearerOf(RootResources root, String tokenVar, Context context) {
        String value = context.environment().apply(tokenVar);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("the root '" + root.root() + "' has a remote ledger whose bearer variable " + tokenVar + " is not in the process environment — the start is refused");
        }
        return value;
    }

    private static String required(RootResources.Ledger ledger, String key, RootResources root) {
        String v = ledger.word(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("the root '" + root.root() + "' has a remote ledger with no '" + key + "'");
        return v.trim();
    }
}
