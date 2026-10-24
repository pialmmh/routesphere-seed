package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.seed.sessionflow.samples.scene.FlowBed;
import com.telcobright.seed.sessionflow.samples.scene.World;
import com.telcobright.seed.sessionflow.spi.TenantLookup;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The voice sample for a test outside its package (P1-O's proofs): its bed on a world's trees, and one whole answered call. */
public final class VoiceSamples {

    private VoiceSamples() {}

    /** The voice flow on {@code trees} (seed-tenant's, or {@code TenantLookup.of}) with the world's books, its ESL machine and channel. */
    public static FlowBed<VoiceContext, EslOut, EslFrame> bed(World w, TenantLookup trees) {
        VoiceFlow flow = new VoiceFlow(w.kit(World.settings(), trees), VoiceFlowTest.PARTNERS, World.RATES, VoiceFlowTest.ROUTES);
        return new FlowBed<>(c -> new CallSupervisor(flow, c), VoiceContext::new, EslCall.spec(VoiceFlowTest.ESL, EslCallContext::new), new EslChannel(),
            EslFrames::decode);
    }

    /** Park, answer, the caller's hangup, the hangup complete — the call ends with every machine back in its pool. */
    public static void answeredCall(FlowBed<VoiceContext, EslOut, EslFrame> b, String uuid, String esl, String sourceIp) throws Exception {
        b.frame(uuid, VoiceFlowTest.park(uuid, esl, sourceIp, "01711000002"));
        assertEquals("CONNECTING/TRYING", b.states(uuid), "admitted, routed, the parked leg transferred");
        b.frame(uuid, EslFrame.answer(uuid, uuid + "-b", "PCMA"));
        b.frame(uuid, EslFrame.hangup(uuid, "NORMAL_CLEARING"));
        b.frame(uuid, EslFrame.hangupComplete(uuid, "NORMAL_CLEARING"));
        assertEquals("-/-", b.states(uuid));
    }
}
