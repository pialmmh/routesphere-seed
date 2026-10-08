package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.seed.sessionflow.internal.FileSessionJournal;
import com.telcobright.seed.sessionflow.spi.SessionJournal;

import java.nio.file.Path;

/** The journals of the calls in the air a host can hand its kit (R1-6). */
public final class SessionJournals {

    private SessionJournals() {}

    /** The file beside the CDR journal: {@code <dir>/<name>}. A stopped process's lines are read when it opens. */
    public static SessionJournal file(Path file) { return new FileSessionJournal(file); }

    /** No journal: a process death loses the record of every call in the air (a test, a lab). */
    public static SessionJournal none() { return SessionJournal.NONE; }
}
