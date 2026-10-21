package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.statewalk.session.events.ServiceEnd;
import com.telcobright.statewalk.session.events.SignalingFailed;

import java.util.ArrayList;
import java.util.List;

/** Everything one call's ESL machine knows — cleared by the registry when the call ends. Jackson-friendly (persistence). */
public final class EslCallContext {
    public String uuid;
    public String caller;
    public EslTarget target;
    /** The other leg, once FreeSWITCH bridged it (C22). */
    public String otherUuid;
    public int transfers;
    /** The last failure before the answer. */
    public SignalingFailed failed;
    /** The end after the answer, built on the hangup / Stop / the maximum — published at HANGUP_COMPLETE. */
    public ServiceEnd ended;
    /** The states this machine visited, in order. */
    public final List<String> road = new ArrayList<>();
}
