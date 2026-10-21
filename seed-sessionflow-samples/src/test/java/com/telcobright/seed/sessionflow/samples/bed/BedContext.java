package com.telcobright.seed.sessionflow.samples.bed;

import com.telcobright.statewalk.session.SessionContext;

/** The supervisor's context on the bed: the library's session fields plus the hop cursor the scripted handler routes by. */
public final class BedContext extends SessionContext {
    /** The plan's current hop: {@code nextHop} reads it, a REROUTE advances it, a RETRY_SAME keeps it. */
    public int hop;
}
