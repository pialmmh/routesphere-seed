package com.telcobright.seed.memledgerremote.dependencies;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.seed.memledgerremote.api.RemoteMemLedger;
import com.telcobright.seed.memledgerremote.internal.StartWait;
import com.telcobright.seed.switchledger.spi.MemLedgerSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The {@code remote} store of a root's ledger (ARCH-0117 item 3): {@code ledger.store: remote} makes the switch ledger's maker ask here
 * for its MemLedger — a client of the root's mem-ledger server instead of an embedded one; the ledger's kind stays {@code switch}.
 * Words: {@code address} (host:port, required), {@code deadline-ms} (2000 unless said), {@code token-var} (required: the NAME of the
 * environment variable holding the bearer — read ONCE here, handed to the client, never logged; an empty variable refuses the start
 * naming the variable, never a value), {@code start-wait-ms} (30000 unless said; 0 = one try): how long the start waits for the server's
 * FIRST answer — a server just started may not serve yet ({@link StartWait}). Once it answered, the server is asked what it does
 * (Health's capabilities, ARCH-0118: {@code ghosts}, {@code expect}; none = an older server, said in the log). A tier of the root's tree
 * the server does not hold yet is added at the start; a server that does not answer within the wait refuses the start in words.
 */
public final class RemoteMemLedgerSource implements MemLedgerSource {

    private static final Logger log = LoggerFactory.getLogger(RemoteMemLedgerSource.class);

    public static final String STORE = "remote";
    /** Remote-fixes item (d): the start's wait for the server's first answer, unless {@code start-wait-ms} says otherwise. */
    public static final long DEFAULT_START_WAIT_MS = 30_000;

    @Override public String store() { return STORE; }

    @Override
    public MemLedger open(Request request) {
        RemoteLedgerSettings settings = settingsOf(request);
        String bearer = bearerOf(request, settings.tokenVar());
        long startWaitMs = startWaitMsOf(request);
        RemoteMemLedger ledger = connected(request, settings, bearer);
        try {
            List<String> held = theServersFirstAnswer(ledger, startWaitMs);
            ledger.whatTheServerDoes();
            addTheTiersTheServerLacks(ledger, request, held);
            log.info("mem-ledger client: the ledger of root {} is the server at {} (deadline {} ms), tiers {}", request.root(), settings.address(), settings.deadlineMs(), request.tiers());
            return ledger;
        } catch (RuntimeException e) {
            ledger.shutdown();
            throw doesNotStart(request, e);
        }
    }

    /**
     * The client — nothing is sent yet: the server's first answer is waited for ({@link #theServersFirstAnswer}), and only then is the
     * server asked what it does (ARCH-0118, {@link RemoteMemLedger#whatTheServerDoes()}): a server that does not answer refuses the start.
     */
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

    /** The tiers the server holds — its FIRST answer, waited for up to start-wait-ms (recall-db's unit says "started" before it serves). */
    private static List<String> theServersFirstAnswer(RemoteMemLedger ledger, long startWaitMs) {
        return StartWait.forTheFirstAnswer(ledger.settings().address(), startWaitMs, ledger::getRegisteredDatabases, ledger::reconnectNow);
    }

    private static void addTheTiersTheServerLacks(RemoteMemLedger ledger, Request request, List<String> held) {
        for (String tier : request.tiers()) {
            if (held.contains(tier)) continue;
            ledger.addDatabase(tier);
            log.info("mem-ledger client: tier {} of root {} was not held by the server at {} — added", tier, request.root(), ledger.settings().address());
        }
    }

    private static RemoteLedgerSettings settingsOf(Request request) {
        long deadlineMs = millisecondsOf(request, "deadline-ms", RemoteLedgerSettings.DEFAULT_DEADLINE_MS, 1, "more than 0");
        return new RemoteLedgerSettings(required(request, "address"), deadlineMs, required(request, "token-var"));
    }

    /** {@code start-wait-ms}: 0 or more milliseconds; not said = {@value #DEFAULT_START_WAIT_MS}. */
    private static long startWaitMsOf(Request request) { return millisecondsOf(request, "start-wait-ms", DEFAULT_START_WAIT_MS, 0, "0 or more"); }

    /** A word of whole milliseconds, {@code least} or more; not said = {@code unlessSaid}; anything else refuses the start, the root and the word named. */
    private static long millisecondsOf(Request request, String key, long unlessSaid, long least, String range) {
        String said = request.word().apply(key);
        if (said == null || said.isBlank()) return unlessSaid;
        long ms = wholeNumberOrLessThan(said.trim(), least);
        if (ms < least) {
            throw new IllegalStateException("the root '" + request.root() + "': ledger." + key + " is a whole number of milliseconds (" + range + "), not '" + said.trim() + "'");
        }
        return ms;
    }

    private static long wholeNumberOrLessThan(String said, long least) {
        try {
            return Long.parseLong(said);
        } catch (NumberFormatException notAWholeNumber) {
            return least - 1;
        }
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
