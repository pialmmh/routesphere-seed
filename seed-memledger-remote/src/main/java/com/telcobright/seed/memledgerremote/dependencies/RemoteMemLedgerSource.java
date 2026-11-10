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
 * FIRST answers — a server just started may not serve yet ({@link StartWait}): the tiers it holds, then what it does (Health's
 * capabilities, ARCH-0118: {@code ghosts}, {@code expect}; none = an older server, said in the log), both within the one wait. A tier of
 * the root's tree the server does not hold yet is added at the start; a server that does not answer both within the wait refuses the
 * start in words.
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
            List<String> held = theServersFirstAnswers(ledger, startWaitMs);
            addTheTiersTheServerLacks(ledger, request, held);
            requireTheLockRoadItAsksFor(ledger, request);
            log.info("mem-ledger client: the ledger of root {} is the server at {} (deadline {} ms, lock road {}), tiers {}", request.root(), settings.address(), settings.deadlineMs(),
                (settings.lockRoad().on() ? "ON: lease " + settings.lockRoad().leaseMs() + " ms, wait " + settings.lockRoad().waitMs() + " ms" : "off")
                    + (settings.oneCallMoves() ? "; one-call moves ON" : ""), request.tiers());
            return ledger;
        } catch (RuntimeException e) {
            ledger.shutdown();
            throw doesNotStart(request, e);
        }
    }

    /**
     * The client — nothing is sent yet: the server's first answers are waited for ({@link #theServersFirstAnswers}) — the tiers it holds and
     * what it does (ARCH-0118, {@link RemoteMemLedger#whatTheServerDoes()}): a server that does not answer both refuses the start.
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

    /**
     * The tiers the server holds, and what it does — its FIRST answers, waited for together up to start-wait-ms (recall-db's unit says
     * "started" before it serves; its Health is the heavier verb, reading its WAL's lag): a server just started gets the same patience for
     * its capabilities as for its first call.
     */
    private static List<String> theServersFirstAnswers(RemoteMemLedger ledger, long startWaitMs) {
        return StartWait.forTheFirstAnswer(ledger.settings().address(), startWaitMs, () -> heldAndWhatItDoes(ledger), ledger::reconnectNow);
    }

    private static List<String> heldAndWhatItDoes(RemoteMemLedger ledger) {
        List<String> held = ledger.getRegisteredDatabases();
        ledger.whatTheServerDoes();
        return held;
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
        return new RemoteLedgerSettings(required(request, "address"), deadlineMs, required(request, "token-var"), lockRoadOf(request), onOrOff(request, "one-call-moves"));
    }

    /** A word that is {@code "on"} or {@code "off"} (off unless said), written in quotes: a bare on is a YAML boolean and reaches here as another word, refused in words. */
    private static boolean onOrOff(Request request, String key) {
        String said = request.word().apply(key);
        if (said == null || said.isBlank() || said.trim().equals("off")) return false;
        if (said.trim().equals("on")) return true;
        throw new IllegalStateException("the root '" + request.root() + "': ledger." + key + " is \"on\" or \"off\" (in quotes), not '" + said.trim() + "'");
    }

    /**
     * ARCH-0119 · {@code lock-road}: {@code "on"} or {@code "off"} (off unless said) — written in quotes: a bare on is a YAML boolean
     * and reaches here as another word, refused in words. {@code lock-lease-ms} (1–1000; 1000 unless said), {@code lock-wait-ms} (0 or
     * more; 1000 unless said), {@code lock-queue-max} (0 or more; 32 unless said).
     */
    private static RemoteLedgerSettings.LockRoad lockRoadOf(Request request) {
        String said = request.word().apply("lock-road");
        boolean on;
        if (said == null || said.isBlank() || said.trim().equals("off")) on = false;
        else if (said.trim().equals("on")) on = true;
        else throw new IllegalStateException("the root '" + request.root() + "': ledger.lock-road is \"on\" or \"off\" (in quotes), not '" + said.trim() + "'");
        long leaseMs = millisecondsOf(request, "lock-lease-ms", RemoteLedgerSettings.LockRoad.DEFAULT_LEASE_MS, 1, "1 to " + RemoteLedgerSettings.LockRoad.LEASE_MS_MAX);
        long waitMs = millisecondsOf(request, "lock-wait-ms", RemoteLedgerSettings.LockRoad.DEFAULT_WAIT_MS, 0, "0 or more");
        long queueMax = wholeNumberOf(request, "lock-queue-max", RemoteLedgerSettings.LockRoad.DEFAULT_QUEUE_MAX);
        try {
            return new RemoteLedgerSettings.LockRoad(on, Math.toIntExact(leaseMs), Math.toIntExact(waitMs), Math.toIntExact(queueMax));
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new IllegalStateException("the root '" + request.root() + "': " + e.getMessage(), e);
        }
    }

    /** {@code lock-queue-max}: how many units of this application may wait for one account (0 or more; 32 unless said). */
    private static long wholeNumberOf(Request request, String key, long unlessSaid) {
        String said = request.word().apply(key);
        if (said == null || said.isBlank()) return unlessSaid;
        long n = wholeNumberOrLessThan(said.trim(), 0);
        if (n < 0) throw new IllegalStateException("the root '" + request.root() + "': ledger." + key + " is a whole number (0 or more), not '" + said.trim() + "'");
        return n;
    }

    /** ARCH-0119 · A root that says {@code lock-road: "on"} over a server that does not serve the lock road does not start: never a silent fall back to no lock. */
    private static void requireTheLockRoadItAsksFor(RemoteMemLedger ledger, Request request) {
        if (ledger.settings().oneCallMoves() && !ledger.servesAtomic()) {
            throw new IllegalStateException("ledger.one-call-moves is \"on\" and the mem-ledger server at " + ledger.settings().address() + " does not serve the one-call road (it says "
                + ledger.whatTheServerDoes() + "): move the server to a build that says '" + com.telcobright.seed.memledgerremote.api.Capabilities.PATCH + "', or say one-call-moves: \"off\"");
        }
        if (ledger.settings().lockRoad().on() && !ledger.servesLeases()) {
            throw new IllegalStateException("ledger.lock-road is \"on\" and the mem-ledger server at " + ledger.settings().address() + " does not serve the lock road (it says "
                + ledger.whatTheServerDoes() + "): move the server to a build that says '" + com.telcobright.seed.memledgerremote.api.Capabilities.LEASE + "', or say lock-road: \"off\"");
        }
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
