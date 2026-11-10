package com.telcobright.seed.memledgerremote.dependencies;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.seed.memledgerremote.api.RemoteMemLedger;
import com.telcobright.seed.switchledger.spi.MemLedgerSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The {@code remote} store of a root's ledger (ARCH-0117 item 3): {@code ledger.store: remote} makes the switch ledger's maker ask here
 * for its MemLedger — a client of the root's mem-ledger server instead of an embedded one; the ledger's kind stays {@code switch}.
 * Words: {@code address} (host:port, required), {@code deadline-ms} (2000 unless said), {@code token-var} (required: the NAME of the
 * environment variable holding the bearer — read ONCE here, handed to the client, never logged; an empty variable refuses the start
 * naming the variable, never a value). A tier of the root's tree the server does not hold yet is added at the start; a server that
 * does not answer refuses the start in words.
 */
public final class RemoteMemLedgerSource implements MemLedgerSource {

    private static final Logger log = LoggerFactory.getLogger(RemoteMemLedgerSource.class);

    public static final String STORE = "remote";

    @Override public String store() { return STORE; }

    @Override
    public MemLedger open(Request request) {
        RemoteLedgerSettings settings = settingsOf(request);
        String bearer = bearerOf(request, settings.tokenVar());
        RemoteMemLedger ledger = connected(request, settings, bearer);
        try {
            addTheTiersTheServerLacks(ledger, request);
            log.info("mem-ledger client: the ledger of root {} is the server at {} (deadline {} ms), tiers {}", request.root(), settings.address(), settings.deadlineMs(), request.tiers());
            return ledger;
        } catch (RuntimeException e) {
            ledger.shutdown();
            throw doesNotStart(request, e);
        }
    }

    /** The client, which asks the server what it does at once (ARCH-0118): a server that does not answer refuses the start too. */
    private static RemoteMemLedger connected(Request request, RemoteLedgerSettings settings, String bearer) {
        try {
            return RemoteMemLedger.connect(settings, bearer, request.entities());
        } catch (RuntimeException e) {
            throw doesNotStart(request, e);
        }
    }

    private static IllegalStateException doesNotStart(Request request, RuntimeException e) {
        return new IllegalStateException("the root '" + request.root() + "': its ledger does not start — " + e.getMessage(), e);
    }

    private static void addTheTiersTheServerLacks(RemoteMemLedger ledger, Request request) {
        List<String> held = ledger.getRegisteredDatabases();
        for (String tier : request.tiers()) {
            if (held.contains(tier)) continue;
            ledger.addDatabase(tier);
            log.info("mem-ledger client: tier {} of root {} was not held by the server at {} — added", tier, request.root(), ledger.settings().address());
        }
    }

    private static RemoteLedgerSettings settingsOf(Request request) {
        String deadline = request.word().apply("deadline-ms");
        long deadlineMs = deadline == null || deadline.isBlank() ? RemoteLedgerSettings.DEFAULT_DEADLINE_MS : Long.parseLong(deadline.trim());
        return new RemoteLedgerSettings(required(request, "address"), deadlineMs, required(request, "token-var"));
    }

    /** The bearer's VALUE by the variable's NAME, from the process environment only. */
    private static String bearerOf(Request request, String tokenVar) {
        String value = request.environment().apply(tokenVar);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("the root '" + request.root() + "' has a remote ledger store whose bearer variable " + tokenVar + " is not in the process environment — the start is refused");
        }
        return value;
    }

    private static String required(Request request, String key) {
        String v = request.word().apply(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("the root '" + request.root() + "' has a remote ledger store with no '" + key + "'");
        return v.trim();
    }
}
