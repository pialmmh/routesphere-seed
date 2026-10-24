package com.telcobright.seed.sessionflow.api;

import com.telcobright.statewalk.session.SessionFacts;

/**
 * The facts of one session as the application's signaling machine read them from the message in hand (the INVITE, the PARK, the
 * HTTP request, the device event) — statewalk's {@link SessionFacts} plus the one fact the base reads itself: the {@link Ingress}, the
 * identity of the door the session came in at. Carried by {@code SessionStart}, kept on the context, persisted with it: an implementation
 * is a Jackson-friendly record (ids and words, never a tenant object).
 */
public interface RoutesphereFacts extends SessionFacts {

    /** Where this session came in — the base's step (a) finds the ROOT tenant by it. Null = no identity: refused NO_TENANT. */
    Ingress ingress();

    /**
     * The ROOT operator the ingress already identified ({@code engine.identifyRootLevelTenant(facts)}, the ingress's first line): the
     * machine takes it and asks the lane nothing. Null (the default) = the machine identifies the root itself at its start.
     */
    default String root() { return null; }
}
