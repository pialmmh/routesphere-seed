package com.telcobright.seed.campaign.writer;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.spi.CampaignStore;
import com.telcobright.seed.campaign.spi.StoreChange;
import com.telcobright.seed.campaign.spi.StoreRepair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A store behind ONE writer and a queue (ad-sphere ARCH-0043 R1-1): what the switch knows about a task reaches its store — late if it
 * must, never not at all — and a task never waits for the store.
 *
 * <ul>
 *   <li><b>Telling never waits.</b> {@code insertTask}, {@code updateTask}, {@code bumpCounters} and {@code markComplete} put their
 *       change on the queue and return. The queue is bounded: when it is full the caller writes one line of the journal instead
 *       ({@link ChangeJournal}), and so does every later change until the writer has caught up — the order of the telling is kept.</li>
 *   <li><b>One thread writes</b>, in batches, each batch ONE transaction of the store ({@link CampaignStore#write}): the task rows of
 *       the batch and, per campaign, one counter statement with the batch's sums.</li>
 *   <li><b>A batch that failed is written again</b>, after a wait that grows to a cap, for as long as the process lives: one ERROR
 *       when it starts failing, one INFO when it writes again. A change the store refuses by its own rules (a key, a width — not the
 *       store being away or not ready) must not hold the others: after a few tries the batch is written change by change, and such a
 *       change is put aside in {@code <name>.rejected.jsonl} with one ERROR.</li>
 *   <li><b>A clean stop loses nothing:</b> the queue is drained for a bounded time; what could not be written is put in the journal,
 *       and the next start writes it before the first task.</li>
 *   <li><b>A start repairs what a dead process left</b> ({@link CampaignStore#repairAfterRestart}): after a kill there is no journal,
 *       so every task that is not final is closed and the counters are made to say what the task rows say.</li>
 * </ul>
 *
 * A reader of the store's counters must see what was told before it asked: {@link #campaigns} waits for that ({@link #awaitWritten}).
 */
public final class QueuedCampaignStore implements CampaignStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QueuedCampaignStore.class);

    /**
     * What the writer knows of itself, for a health page and a test.
     *
     * @param settled  the changes that are in the store, or were put aside as rejected
     * @param onDisk   the changes that went to the journal instead of the queue, so far
     * @param lost     the changes that could not be kept at all (the queue full AND the journal not writable)
     */
    public record Stats(long told, long settled, int queued, long onDisk, long rejected, long lost, boolean failing, String lastFailure) {
        /** The changes told that are neither in the store nor put aside nor lost: they wait. */
        public long waiting() { return told - settled - lost; }
    }

    private record Entry(long seq, StoreChange change) {}

    private enum Outcome { WRITTEN, STOPPED, REFUSED, THE_STORE_IS_AWAY }

    private final String name;
    private final CampaignStore store;
    private final WriterSettings settings;
    private final ChangeJournal journal;
    private final ArrayBlockingQueue<Entry> queue;
    /** The order of the queue and of the journal is the order of the telling: one teller at a time (a moment, never the store). */
    private final ReentrantLock telling = new ReentrantLock();
    private final ReentrantLock writtenLock = new ReentrantLock();
    private final Condition moreWritten = writtenLock.newCondition();
    private final Thread writer;

    private long seq;                               // guarded by telling
    private long lastOnDisk;                        // guarded by telling: the last change that went to the journal instead of the queue
    private volatile boolean onDisk;                // the queue was full: every change goes to the journal until the writer has caught up
    private volatile boolean closed;
    private volatile boolean stopping;
    private volatile long stopBy;
    private volatile long writtenThrough;
    private volatile List<StoreChange> inHand = List.of();      // the queue's changes the writer holds and has not settled yet
    private volatile long failingSince;
    private volatile String lastFailure;
    private final AtomicLong settled = new AtomicLong();
    private final AtomicLong putOnDisk = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong lost = new AtomicLong();

    private QueuedCampaignStore(String name, CampaignStore store, WriterSettings settings, Path journalDir) {
        this.name = name;
        this.store = store;
        this.settings = settings;
        this.journal = new ChangeJournal(journalDir, "campaign-store-" + name);
        this.queue = new ArrayBlockingQueue<>(settings.queue());
        this.writer = new Thread(this::run, "campaign-store-" + name);
        this.writer.setDaemon(true);
    }

    /**
     * The store's writer, started. Before it takes its first change: what a stopped process left in the journal is written into the
     * store (the start of this store fails by name when the store does not take it in {@code startWaitMs}), and — when
     * {@code repairFor} names the tenant of this store's task rows — the store is repaired: no task of that tenant is live yet in this
     * process, so the ones not final are closed {@link CampaignStore#LOST_AT_RESTART} and the counters are set from the rows.
     *
     * @param name      the store's name: in the log, and in its journal's file name ({@code campaign-store-<name>.jsonl})
     * @param repairFor the tenant as the store's task rows name it; null = no repair (more than one process writes these tasks)
     */
    public static QueuedCampaignStore open(String name, CampaignStore store, WriterSettings settings, Path journalDir, String repairFor) {
        QueuedCampaignStore queued = new QueuedCampaignStore(name, store, settings, journalDir);
        queued.writeWhatAStoppedProcessLeft();
        if (repairFor != null) queued.repair(repairFor);
        queued.writer.start();
        log.info("campaign store {}: one writer, a queue of {} changes, batches of {} in one transaction; a failed batch is written again every {}–{} ms;"
            + " the journal of what is not written is {}", name, settings.queue(), settings.batch(), settings.retryFirstMs(), settings.retryCapMs(), queued.journal.file());
        return queued;
    }

    // ── the store, as the service tells it ──────────────────────────────────

    /** The campaigns with their counters — after everything told before this call is in the store (else the counts would lag). */
    @Override
    public List<Campaign> campaigns(String tenantId) {
        requireWritten("the campaigns of " + tenantId);
        return store.campaigns(tenantId);
    }

    @Override public void insertTask(CampaignTask task) { tell(new StoreChange.TaskInserted(task)); }

    @Override public void updateTask(CampaignTask task) { tell(new StoreChange.TaskUpdated(task)); }

    @Override
    public void bumpCounters(String tenantId, int campaignId, int sentDelta, int failedDelta, int pendingDelta) {
        tell(new StoreChange.CountersBumped(tenantId, campaignId, sentDelta, failedDelta, pendingDelta));
    }

    @Override public void markComplete(String tenantId, int campaignId) { tell(new StoreChange.CampaignCompleted(tenantId, campaignId)); }

    @Override
    public void write(List<StoreChange> batch) { batch.forEach(this::tell); }

    /** The repair is the start's ({@link #open}); asked again later it would close the tasks that are live. */
    @Override
    public StoreRepair repairAfterRestart(String tenantName, String cause, Instant at) { return StoreRepair.NOTHING; }

    /** The store this writer writes into (a reader of its rows, a test). */
    public CampaignStore store() { return store; }

    public Stats stats() {
        return new Stats(toldSoFar(), settled.get(), queue.size(), putOnDisk.get(), rejected.get(), lost.get(), failingSince != 0, lastFailure);
    }

    // ── telling: the queue, or a line on disk; never a wait ─────────────────

    private void tell(StoreChange change) {
        telling.lock();
        try {
            long s = ++seq;
            if (!onDisk && !closed && queue.offer(new Entry(s, change))) return;
            putOnDiskInsteadOfWaiting(s, change);
        } finally {
            telling.unlock();
        }
    }

    private void putOnDiskInsteadOfWaiting(long s, StoreChange change) {
        if (!onDisk && !closed) {
            log.warn("campaign store {}: the queue is full ({} changes wait for the store): each change is one line of {} from now on, until the writer has caught up",
                name, settings.queue(), journal.file());
        }
        onDisk = true;
        lastOnDisk = s;
        try {
            journal.append(change);
            putOnDisk.incrementAndGet();
        } catch (RuntimeException e) {
            long soFar = lost.incrementAndGet();
            if (soFar == 1 || soFar % 1000 == 0) {
                log.error("campaign store {}: a change could NOT be kept — the queue is full and the journal {} cannot be written ({}); {} lost so far. The change: {}",
                    name, journal.file(), e.toString(), soFar, change);
            }
        }
    }

    private long toldSoFar() {
        telling.lock();
        try { return seq; } finally { telling.unlock(); }
    }

    // ── a reader waits for what was told before it asked ────────────────────

    /** True when every change told before this call is in the store (or was put aside as rejected), within {@code withinMs}. */
    public boolean awaitWritten(long withinMs) {
        long target = toldSoFar();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(withinMs);
        writtenLock.lock();
        try {
            while (writtenThrough < target) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return false;
                moreWritten.awaitNanos(left);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            writtenLock.unlock();
        }
    }

    /** A read of the store's counts waits at most the retry's cap for what was told before it; then it is refused, by name. */
    public void requireWritten(String what) {
        if (awaitWritten(settings.retryCapMs())) return;
        Stats now = stats();
        throw new IllegalStateException(what + " cannot be read now: " + now.waiting() + " change(s) of campaign store " + name + " are not in the store yet"
            + (now.failing() ? " (the store does not take them: " + now.lastFailure() + ")" : "") + " — the counts would lag behind what was told");
    }

    private void writtenUpTo(long seqWritten) {
        writtenLock.lock();
        try {
            if (seqWritten > writtenThrough) writtenThrough = seqWritten;
            moreWritten.signalAll();
        } finally {
            writtenLock.unlock();
        }
    }

    // ── the writer ──────────────────────────────────────────────────────────

    private void run() {
        try {
            while (true) {
                List<Entry> batch = nextBatch();
                if (!batch.isEmpty()) {
                    inHand = changesOf(batch);
                    List<StoreChange> left = writeUntilWritten(inHand, Long.MAX_VALUE);
                    inHand = left;
                    if (!left.isEmpty()) return;                      // a stop could wait no longer: close() keeps what is in hand
                    writtenUpTo(batch.get(batch.size() - 1).seq());
                } else if (onDisk && (!closed || journal.hasLines())) {
                    if (!writeWhatWasPutOnDisk()) return;
                } else if (stopping) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.error("campaign store {}: its writer stopped on a fault of its own — what is told from now on waits in the queue and the journal and is written at the next start", name, e);
        }
    }

    private List<Entry> nextBatch() {
        List<Entry> batch = new ArrayList<>();
        try {
            Entry first = queue.poll(stopping ? 1 : 200, TimeUnit.MILLISECONDS);
            if (first == null) return batch;
            batch.add(first);
            queue.drainTo(batch, settings.batch() - 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return batch;
    }

    private static List<StoreChange> changesOf(List<Entry> batch) {
        List<StoreChange> changes = new ArrayList<>(batch.size());
        for (Entry e : batch) changes.add(e.change());
        return changes;
    }

    /**
     * The queue is empty and changes went to the journal meanwhile: the journal is set aside, new changes go to the queue again (they
     * are newer than every line set aside), and the lines are written now — before anything of the queue. False = a stop ended it; the
     * lines not written stay in the file that was set aside, with the count of the ones that are.
     */
    private boolean writeWhatWasPutOnDisk() {
        long through;
        telling.lock();
        try {
            if (!queue.isEmpty()) return true;                       // older changes first
            journal.setAsideForReplay();
            through = lastOnDisk;
            if (!closed) onDisk = false;
        } finally {
            telling.unlock();
        }
        long lines = writeTheLinesSetAside(Long.MAX_VALUE);
        if (lines < 0) return false;
        writtenUpTo(through);
        if (lines > 0) log.info("campaign store {}: the {} change(s) the journal held are in the store; changes wait in the queue again", name, lines);
        return true;
    }

    /** The file set aside, batch by batch, each batch counted when it is in the store. -1 = a stop (or the start's wait) ended it first. */
    private long writeTheLinesSetAside(long giveUpAtNanos) {
        long changes = 0;
        try (ChangeJournal.Lines lines = journal.linesSetAside()) {
            for (List<StoreChange> batch = lines.next(settings.batch()); !batch.isEmpty(); batch = lines.next(settings.batch())) {
                if (!writeUntilWritten(batch, giveUpAtNanos).isEmpty()) return -1;      // what is left stays in the file, after its count
                changes += batch.size();
                journal.wroteSoFar(lines.linesRead());
            }
        }
        journal.replayed();
        return changes;
    }

    /**
     * The batch into the store, however long it takes. Empty = every change of it is settled: in the store, or put aside as rejected.
     * Not empty = a stop could wait no longer (or the start's wait ran out): the changes that are not settled, in their order.
     */
    private List<StoreChange> writeUntilWritten(List<StoreChange> batch, long giveUpAtNanos) {
        List<StoreChange> rest = batch;
        while (!rest.isEmpty()) {
            Outcome whole = writeWhole(rest, giveUpAtNanos);
            if (whole == Outcome.WRITTEN) { settled.addAndGet(rest.size()); return List.of(); }
            if (whole == Outcome.STOPPED) return List.copyOf(rest);
            // REFUSED: change by change, so that one change the store will never take does not hold the others
            int next = 0;
            Outcome one = Outcome.WRITTEN;
            for (; next < rest.size(); next++) {
                one = writeOne(rest.get(next), giveUpAtNanos);
                if (one == Outcome.THE_STORE_IS_AWAY || one == Outcome.STOPPED) break;
                settled.incrementAndGet();
            }
            rest = rest.subList(next, rest.size());
            if (one == Outcome.STOPPED) return List.copyOf(rest);
        }
        return List.of();
    }

    private Outcome writeWhole(List<StoreChange> batch, long giveUpAtNanos) {
        long waitMs = settings.retryFirstMs();
        for (int tries = 1; ; tries++) {
            try {
                store.write(batch);
                itWritesAgain();
                return Outcome.WRITTEN;
            } catch (RuntimeException e) {
                itFails(batch.size(), e);
                if (refusedByTheStoresOwnRules(e) && tries >= settings.triesBeforeOneByOne()) return Outcome.REFUSED;
            }
            if (!waitUnlessStopped(waitMs, giveUpAtNanos)) return Outcome.STOPPED;
            waitMs = Math.min(waitMs * 2, settings.retryCapMs());
        }
    }

    /** One change alone. WRITTEN also when it was put aside as rejected: it is settled, the writer goes on. */
    private Outcome writeOne(StoreChange change, long giveUpAtNanos) {
        RuntimeException last = null;
        for (int tries = 1; tries <= settings.triesBeforeRejected(); tries++) {
            try {
                store.write(List.of(change));
                itWritesAgain();
                return Outcome.WRITTEN;
            } catch (RuntimeException e) {
                if (!refusedByTheStoresOwnRules(e)) return Outcome.THE_STORE_IS_AWAY;
                last = e;
            }
            if (tries < settings.triesBeforeRejected() && !waitUnlessStopped(settings.retryFirstMs(), giveUpAtNanos)) return Outcome.STOPPED;
        }
        putAside(change, last);
        return Outcome.WRITTEN;
    }

    private void putAside(StoreChange change, RuntimeException why) {
        long soFar = rejected.incrementAndGet();
        try {
            journal.reject(change, String.valueOf(why));
        } catch (RuntimeException e) {
            log.error("campaign store {}: a rejected change could not be written to {} either: {}", name, journal.rejectedFile(), e.toString());
        }
        if (soFar <= 10 || soFar % 100 == 0) {
            log.error("campaign store {}: the store refuses this change by its own rules ({} tries, alone): {} — put aside in {}, not written again by itself ({} so far). The change: {}",
                name, settings.triesBeforeRejected(), why, journal.rejectedFile(), soFar, change);
        }
    }

    private void itFails(int changes, RuntimeException e) {
        lastFailure = whatTheStoreSaid(e);
        if (failingSince != 0) return;
        if (stopping) return;                                        // a stop that cut a write short: the stop says what it kept, once
        failingSince = System.currentTimeMillis();
        log.error("campaign store {}: a batch of {} change(s) could not be written: {} — it is written again every {}–{} ms until the store takes it; no task waits"
            + " ({} more wait in the queue, then on disk in {})", name, changes, lastFailure, settings.retryFirstMs(), settings.retryCapMs(), queue.size(), journal.file());
    }

    /** The words of the failure's first cause: what the database or the pool said, not this library's sentence around it. */
    private static String whatTheStoreSaid(Throwable e) {
        Throwable first = e;
        while (first.getCause() != null && first.getCause() != first) first = first.getCause();
        return String.valueOf(first.getMessage() == null ? first : first.getMessage());
    }

    private void itWritesAgain() {
        long since = failingSince;
        if (since == 0) return;
        failingSince = 0;
        log.info("campaign store {}: the store takes writes again after {} ms; {} change(s) wait in the queue", name, System.currentTimeMillis() - since, queue.size());
    }

    /** False = it must not wait any longer: a stop's time is over, or the start's. */
    private boolean waitUnlessStopped(long waitMs, long giveUpAtNanos) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        while (true) {
            long now = System.nanoTime();
            if (now >= giveUpAtNanos) return false;
            if (stopping && now >= stopBy) return false;
            if (now >= until) return true;
            try {
                TimeUnit.NANOSECONDS.sleep(Math.max(1, Math.min(until - now, TimeUnit.MILLISECONDS.toNanos(50))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Does the store refuse THIS write by its own rules — a key, a value its column cannot hold, a check the store makes before it
     * asks the database — or is the store away or not ready (no connection, the server stopping, a deadlock, a missing table or grant)?
     * The second is waited out, for ever; the first would be refused for ever. By the standard's classes of SQLSTATE: 22 (a data
     * exception) and 23 (a constraint) are the store's own rules; and so is a failure that is no SQL and no I/O failure at all.
     */
    static boolean refusedByTheStoresOwnRules(Throwable e) {
        boolean sqlOrIo = false;
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.io.IOException || t instanceof java.io.UncheckedIOException) sqlOrIo = true;
            if (t instanceof SQLException sql) {
                sqlOrIo = true;
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("22") || state.startsWith("23"))) return true;
            }
        }
        return !sqlOrIo;
    }

    // ── a start: what a stopped process left, then the repair ───────────────

    private void writeWhatAStoppedProcessLeft() {
        if (!journal.somethingIsLeft()) return;
        long giveUpAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.startWaitMs());
        long first = writeTheLinesSetAside(giveUpAt);                // a replay that a stop or a kill cut short: the lines after its count
        long then = first < 0 ? -1 : journal.setAsideForReplay() ? writeTheLinesSetAside(giveUpAt) : 0;
        if (first < 0 || then < 0) {
            throw new IllegalStateException("campaign store " + name + ": what a stopped process left in " + journal.file() + " could not be written within "
                + settings.startWaitMs() + " ms: " + lastFailure + " — the journal is kept as it is");
        }
        failingSince = 0;
        log.info("campaign store {}: {} change(s) a stopped process had left in {} are in the store now", name, first + then, journal.file());
    }

    private void repair(String tenantName) {
        StoreRepair repair = store.repairAfterRestart(tenantName, LOST_AT_RESTART, Instant.now());
        if (!repair.nothing()) log.warn("campaign store {}: the start repaired what a stopped process left — {}", name, repair.words());
    }

    // ── a clean stop ────────────────────────────────────────────────────────

    /**
     * The queue is drained for at most {@code stopWaitMs}; what the store did not take by then is put in the journal, in its order,
     * and the next start writes it. A change told after this goes to the journal at once.
     */
    @Override
    public void close() {
        telling.lock();
        try {
            if (closed) return;
            closed = true;
        } finally {
            telling.unlock();
        }
        stopBy = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.stopWaitMs());
        stopping = true;
        try {
            writer.join(settings.stopWaitMs() + 2_000);
            if (writer.isAlive()) { writer.interrupt(); writer.join(2_000); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        keepWhatWasNotWritten();
    }

    private void keepWhatWasNotWritten() {
        telling.lock();
        try {
            List<StoreChange> left = new ArrayList<>(inHand);        // what the writer held (also when it hangs in the store's call: then it may be written twice — a task row is made once, and a start sets the counters)
            for (Entry e : queue) left.add(e.change());
            queue.clear();
            boolean heldLines = journal.hasLines();
            if (left.isEmpty() && !heldLines) { log.info("campaign store {}: stopped; every change is in the store", name); return; }
            journal.prepend(left);
            log.warn("campaign store {}: {} change(s) were not in the store when the process stopped{}: they are in {}{} and are written at the next start",
                name, left.size(), lastFailure == null ? "" : " (" + lastFailure + ")", journal.file(), heldLines ? ", before the lines it held already," : "");
        } catch (RuntimeException e) {
            log.error("campaign store {}: what was not written could not be put in the journal {}: {}", name, journal.file(), e.toString());
        } finally {
            telling.unlock();
        }
    }
}
