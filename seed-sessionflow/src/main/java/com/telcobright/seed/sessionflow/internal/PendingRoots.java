package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.PendingRootPace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * P1-G · The roots a {@code tenant_created} named that cannot be served yet. prime-context announces a root at its provisioning, BEFORE it
 * serves it: its tree road answers 404 ROOT_NOT_SERVED until tenant-master's switch row exists, and the deployment document may list the
 * root only later. Each such root is tried again every {@code retry-sec} until it is served or {@code give-up-min} has passed — said once
 * when it goes pending, once when it is given up (the road says once when it is served). One daemon thread per road, made at the first
 * pending root, stopped by the road's close.
 */
public final class PendingRoots implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PendingRoots.class);
    private static final int KEPT_WORDS = 50;

    /** One try to serve a root: null = served now, else the reason in words it is not yet. */
    @FunctionalInterface
    public interface Attempt { String serve(String root); }

    private final String app;
    private final PendingRootPace pace;
    private final Clock clock;
    private final Attempt attempt;
    private final Map<String, Long> since = new ConcurrentHashMap<>();
    private final List<String> said = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService timer;
    private boolean closed;

    public PendingRoots(String app, PendingRootPace pace, Clock clock, Attempt attempt) {
        this.app = app;
        this.pace = pace;
        this.clock = clock;
        this.attempt = attempt;
    }

    /** The root goes pending, said once with the reason; a root already pending stays as it is. */
    public synchronized void pend(String root, String reason) {
        if (closed || since.putIfAbsent(root, clock.millis()) != null) return;
        say("the root " + root + " of " + app + " is not served yet: " + reason + " — tried again every " + pace.retrySec() + " s, given up after "
            + pace.giveUpMin() + " min", true);
        startTheTimer();
    }

    /** A pending root served now (by a try or by a new word): no longer pending. */
    public void served(String root) { since.remove(root); }

    public Set<String> roots() { return Set.copyOf(since.keySet()); }

    /** What was said of the pending roots, the last first-said lines (the operations' view; a test's). */
    public List<String> said() { return List.copyOf(said); }

    /** The road's own line about a root, kept with the rest (the road says once when a pending root is served). */
    void note(String line) { keep(line); }

    @Override
    public synchronized void close() {
        closed = true;
        if (timer != null) timer.shutdownNow();
        since.clear();
    }

    private void startTheTimer() {
        if (timer != null) return;
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, app + "-pending-roots");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(this::tryEveryPendingRoot, pace.retrySec(), pace.retrySec(), TimeUnit.SECONDS);
    }

    private void tryEveryPendingRoot() {
        for (String root : roots()) tryOne(root);
    }

    private void tryOne(String root) {
        try {
            if (attempt.serve(root) == null) { served(root); return; }
        } catch (RuntimeException e) {
            log.debug("[{}] the pending root {} was tried and failed: {}", app, root, e.toString());
        }
        giveUpWhenItsTimeIsOver(root);
    }

    private void giveUpWhenItsTimeIsOver(String root) {
        Long at = since.get(root);
        if (at == null || clock.millis() - at < TimeUnit.MINUTES.toMillis(pace.giveUpMin())) return;
        since.remove(root);
        say("the root " + root + " of " + app + " was given up after " + pace.giveUpMin() + " min not served: a restart, or a new tenant_created, serves it", true);
    }

    private void say(String line, boolean warn) {
        if (warn) log.warn("{}", line); else log.info("{}", line);
        keep(line);
    }

    private void keep(String line) {
        said.add(line);
        while (said.size() > KEPT_WORDS) said.remove(0);
    }
}
