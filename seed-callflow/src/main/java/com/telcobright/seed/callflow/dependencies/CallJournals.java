package com.telcobright.seed.callflow.dependencies;

import com.telcobright.seed.callflow.internal.FileCallJournal;
import com.telcobright.seed.callflow.spi.CallJournal;

import java.nio.file.Path;

/** The journals of the calls in the air a host can hand its kit (R1-6). */
public final class CallJournals {

    private CallJournals() {}

    /** The file beside the CDR journal: {@code <dir>/<name>}. A stopped process's lines are read when it opens. */
    public static CallJournal file(Path file) { return new FileCallJournal(file); }

    /** No journal: a process death loses the record of every call in the air (a test, a lab). */
    public static CallJournal none() { return CallJournal.NONE; }
}
