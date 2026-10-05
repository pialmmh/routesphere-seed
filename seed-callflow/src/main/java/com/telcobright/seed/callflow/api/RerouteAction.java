package com.telcobright.seed.callflow.api;

/**
 * What a call does when its signaling failed before the answer — the call switch's v1 policy (C12), answered by
 * {@link CallFlowSteps#rerouteActionFor} from the protocol and the cause.
 */
public enum RerouteAction {
    /** The next hop of the route plan, with a fresh signaling child. The reserve stays; nothing is re-admitted. */
    REROUTE,
    /** The same hop once more, with a fresh signaling child. */
    RETRY_SAME,
    /** The call fails with the cause. */
    FAIL_TERMINAL
}
