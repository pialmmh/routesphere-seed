package com.telcobright.seed.sessionflow.samples.bed;

import com.telcobright.statewalk.persistence.MachineSnapshot;
import com.telcobright.statewalk.persistence.PersistenceProvider;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One node's handle on a shared store that can DIE (statewalk's lab {@code CrashableStore}, for 4.0's whole provider) — and can be told
 * to die at the worst moment of P1 §L.1: right after a session's final state settled and recorded (synchronously, in its entry) and
 * BEFORE its final row lands. Persistence is async (the owner's rule), so a real process can die exactly there: the next node restores
 * the session from its older row and ends it AGAIN — every money call and the CDR must be idempotent per (session, tier).
 *
 * <p>Before the crash every call passes through. After it, the node is gone as far as the store can tell: writes are dropped, reads see
 * nothing. Give node A this handle and node B the shared store itself.
 */
public final class CrashingStore implements PersistenceProvider {

    /** The final states of the session supervisor: the session row's {@code current_state} when it ended. */
    public static final Set<String> SESSION_FINALS = Set.of("SUCCEEDED", "FAILED");

    private final PersistenceProvider store;
    private final AtomicBoolean dead = new AtomicBoolean();
    private final AtomicInteger droppedWrites = new AtomicInteger();
    private volatile String dieBeforeTheFinalRowOf;

    public CrashingStore(PersistenceProvider store) { this.store = store; }

    /** The node dies now: every later call from it is cut off from the store. */
    public void crash() { dead.set(true); }

    /** The node dies at the moment the final row of {@code sessionId} is written: that row never lands. */
    public CrashingStore dieBeforeTheFinalRowOf(String sessionId) { this.dieBeforeTheFinalRowOf = sessionId; return this; }

    public boolean isDead() { return dead.get(); }

    /** Writes the dead node attempted (they never landed). */
    public int droppedWrites() { return droppedWrites.get(); }

    @Override public void save(MachineSnapshot snapshot) {
        if (theFinalRowKillsTheNode(snapshot) || dropped()) return;
        store.save(snapshot);
    }

    private boolean theFinalRowKillsTheNode(MachineSnapshot snapshot) {
        String victim = dieBeforeTheFinalRowOf;
        if (victim == null || !victim.equals(snapshot.machineId()) || !SESSION_FINALS.contains(snapshot.currentState())) return false;
        dead.set(true);
        droppedWrites.incrementAndGet();
        return true;
    }

    @Override public Optional<MachineSnapshot> load(String machineId, String registryName) {
        return dead.get() ? Optional.empty() : store.load(machineId, registryName);
    }

    @Override public List<MachineSnapshot> loadAll(String machineId) { return dead.get() ? List.of() : store.loadAll(machineId); }

    @Override public void delete(String machineId, String registryName) { if (!dropped()) store.delete(machineId, registryName); }

    @Override public List<MachineSnapshot> loadMatured(String registryName, long nowMs) {
        return dead.get() ? List.of() : store.loadMatured(registryName, nowMs);
    }

    @Override public List<MachineSnapshot> loadAllForRegistry(String registryName) {
        return dead.get() ? List.of() : store.loadAllForRegistry(registryName);
    }

    @Override public List<MachineSnapshot> loadUnfinishedForRegistry(String registryName, Set<String> finalStates) {
        return dead.get() ? List.of() : store.loadUnfinishedForRegistry(registryName, finalStates);
    }

    @Override public void quarantine(String machineId, String registryName, String reason) {
        if (!dropped()) store.quarantine(machineId, registryName, reason);
    }

    @Override public void bindDefaultTable(String tableName) { store.bindDefaultTable(tableName); }

    @Override public int deleteFinishedBefore(String registryName, Set<String> finalStates, long updatedBeforeMs) {
        return dead.get() ? 0 : store.deleteFinishedBefore(registryName, finalStates, updatedBeforeMs);
    }

    @Override public long rotate() { return store.rotate(); }

    @Override public long rotate(String registryName) { return store.rotate(registryName); }

    private boolean dropped() {
        if (!dead.get()) return false;
        droppedWrites.incrementAndGet();
        return true;
    }
}
