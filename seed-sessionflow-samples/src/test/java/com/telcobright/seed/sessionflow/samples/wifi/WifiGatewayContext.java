package com.telcobright.seed.sessionflow.samples.wifi;

import com.telcobright.statewalk.session.events.ServiceEnd;
import com.telcobright.statewalk.session.events.SignalingFailed;

import java.util.ArrayList;
import java.util.List;

/** Everything one device session's machine knows — cleared by the registry when the session ends. Jackson-friendly (persistence). */
public final class WifiGatewayContext {
    public String sessionId;
    public String mac;
    public WifiGate gate;
    public long bytes;
    public long seconds;
    public int usageReports;
    public SignalingFailed failed;
    /** The end, built when the device left / the supervisor stopped it / the session's maximum — published when the gate closed. */
    public ServiceEnd ended;
    /** The states this machine visited, in order. */
    public final List<String> road = new ArrayList<>();
}
