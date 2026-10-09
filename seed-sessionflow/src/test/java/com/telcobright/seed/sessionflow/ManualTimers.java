package com.telcobright.seed.sessionflow;

import com.telcobright.statewalk.registry.StatemachineRegistry;
import com.telcobright.statewalk.timeout.TimeoutManager;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A manual scheduler for the registry's state timers: in statewalk 3.2.0 every timer a machine arms goes through ONE
 * {@code TimeoutManager} the registry holds. This subclass takes its place, keeps every task instead of scheduling it, and a test
 * fires the tasks it wants — so a delay of 58 s is proven as 58 000 ms without waiting a minute. (callflow-v3-voice's test tree has
 * the same helper, with two-thread firing for the deadline race.)
 */
final class ManualTimers extends TimeoutManager {

    /** One armed timer: what it runs and after how long it was meant to fire. */
    static final class Task {
        final Runnable run;
        final long delayMs;
        final long seq;
        final Fake future = new Fake();

        Task(Runnable run, long delayMs, long seq) { this.run = run; this.delayMs = delayMs; this.seq = seq; }

        @Override public String toString() { return "timer#" + seq + "(" + delayMs + " ms" + (future.cancelled ? ", cancelled" : "") + ")"; }
    }

    static final class Fake implements ScheduledFuture<Object> {
        volatile boolean cancelled;
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed o) { return 0; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { cancelled = true; return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return cancelled; }
        @Override public Object get() { return null; }
        @Override public Object get(long timeout, TimeUnit unit) { return null; }
    }

    final List<Task> tasks = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    private ManualTimers() {
        super("manual-timers", 1);
        super.shutdown();                                                     // its own executor is never used
    }

    /** Put a manual scheduler in place of the registry's, before the first call is launched. */
    static ManualTimers installOn(StatemachineRegistry<?> registry) {
        try {
            Field field = StatemachineRegistry.class.getDeclaredField("timeouts");
            field.setAccessible(true);
            TimeoutManager real = (TimeoutManager) field.get(registry);
            ManualTimers manual = new ManualTimers();
            field.set(registry, manual);
            real.shutdown();
            return manual;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("statewalk's registry no longer holds its timers in 'timeouts': the manual scheduler cannot be installed", e);
        }
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        Task t = new Task(task, unit.toMillis(delay), seq.incrementAndGet());
        tasks.add(t);
        return t.future;
    }

    @Override
    public ScheduledFuture<?> scheduleTracked(String key, Runnable task, long delay, TimeUnit unit) { return schedule(task, delay, unit); }

    @Override
    public boolean cancelTracked(String key) { return true; }

    @Override
    public int activeCount() { return (int) tasks.stream().filter(t -> !t.future.cancelled).count(); }

    @Override
    public void shutdown() { }

    /** How many timers were armed with this delay. */
    long armed(long delayMs) { return tasks.stream().filter(t -> t.delayMs == delayMs).count(); }

    /** The newest timer armed with this delay. */
    Task newest(long delayMs) {
        Task found = null;
        for (Task t : tasks) if (t.delayMs == delayMs && (found == null || t.seq > found.seq)) found = t;
        if (found == null) throw new AssertionError("no timer of " + delayMs + " ms was armed; armed: " + tasks);
        return found;
    }

    /** Fire one timer now, as the timer thread would. */
    void fire(Task task) { task.run.run(); }
}
