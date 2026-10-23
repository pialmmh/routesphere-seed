package com.telcobright.seed.sessionflow.samples.scene;

import com.telcobright.seed.sessionflow.samples.bed.Crash;
import com.telcobright.seed.sessionflow.spi.SessionJournal;

import java.util.List;

/** One node's journal of the sessions in the air (P1-R C2): its lines are written until the node dies; a dead process writes nothing. */
public final class CrashingJournal implements SessionJournal {

    private final SessionJournal journal;
    private final Crash crash;

    public CrashingJournal(SessionJournal journal, Crash crash) {
        this.journal = journal;
        this.crash = crash;
    }

    @Override public void reserved(String callId, long atMs, String records, Held held) { if (!crash.isDead()) journal.reserved(callId, atMs, records, held); }
    @Override public void released(String callId, String reference) { if (!crash.isDead()) journal.released(callId, reference); }
    @Override public void handedOver(String callId, long atMs, String records) { if (!crash.isDead()) journal.handedOver(callId, atMs, records); }
    @Override public void noted(String callId, long atMs, long answeredAtMs, double billedSec) { if (!crash.isDead()) journal.noted(callId, atMs, answeredAtMs, billedSec); }
    @Override public void done(String callId) { if (!crash.isDead()) journal.done(callId); }
    @Override public List<Leftover> leftovers() { return journal.leftovers(); }
    @Override public String where() { return journal.where(); }
    @Override public boolean holds(String callId) { return journal.holds(callId); }
}
