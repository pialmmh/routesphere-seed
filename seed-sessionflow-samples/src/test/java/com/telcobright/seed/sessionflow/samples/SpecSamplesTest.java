package com.telcobright.seed.sessionflow.samples;

import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import com.telcobright.seed.sessionflow.samples.ad.AdPlayback;
import com.telcobright.seed.sessionflow.samples.ad.AdPlaybackContext;
import com.telcobright.seed.sessionflow.samples.sms.SmsSubmit;
import com.telcobright.seed.sessionflow.samples.sms.SmsSubmitContext;
import com.telcobright.seed.sessionflow.samples.voice.EslCall;
import com.telcobright.seed.sessionflow.samples.voice.EslCallContext;
import com.telcobright.seed.sessionflow.samples.wifi.WifiGateway;
import com.telcobright.seed.sessionflow.samples.wifi.WifiGatewayContext;
import com.telcobright.statewalk.registry.MachineSpec;
import com.telcobright.statewalk.session.events.SessionStart;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** P1-E item 3 · every sample's signaling machine shows its SessionStart sample: facts that name their ingress (the base checks at start). */
class SpecSamplesTest {

    private static Ingress ingressOf(MachineSpec<?> spec) {
        SessionStart start = (SessionStart) spec.upSamples().stream().filter(SessionStart.class::isInstance).findFirst().orElseThrow();
        assertInstanceOf(RoutesphereFacts.class, start.facts(), spec.name() + "'s sample names its ingress");
        return ((RoutesphereFacts) start.facts()).ingress();
    }

    @Test
    void theFourSamples_showTheFactsTheyStartASessionWith() {
        assertEquals(Ingress.LISTEN, ingressOf(AdPlayback.spec(AdPlayback.AdTimings.production(), AdPlaybackContext::new)).kind());
        assertEquals(Ingress.ESL, ingressOf(EslCall.spec(EslCall.EslTimings.production(), EslCallContext::new)).kind());
        assertEquals(Ingress.LISTEN, ingressOf(SmsSubmit.spec(SmsSubmit.SmsTimings.production(), SmsSubmitContext::new)).kind());
        assertEquals(Ingress.VLAN, ingressOf(WifiGateway.spec(WifiGateway.WifiTimings.production(), WifiGatewayContext::new)).kind());
    }
}
