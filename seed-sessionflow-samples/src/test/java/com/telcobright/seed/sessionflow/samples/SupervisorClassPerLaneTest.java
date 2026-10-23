package com.telcobright.seed.sessionflow.samples;

import com.telcobright.seed.sessionflow.api.RoutesphereSupervisor;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.samples.ad.AdFlow;
import com.telcobright.seed.sessionflow.samples.ad.AdQuota;
import com.telcobright.seed.sessionflow.samples.ad.AdSupervisor;
import com.telcobright.seed.sessionflow.samples.scene.World;
import com.telcobright.seed.sessionflow.samples.sms.SmsFlow;
import com.telcobright.seed.sessionflow.samples.sms.SmsSupervisor;
import com.telcobright.seed.sessionflow.samples.voice.CallSupervisor;
import com.telcobright.seed.sessionflow.samples.voice.VoiceFlow;
import com.telcobright.seed.sessionflow.samples.wifi.WifiFlow;
import com.telcobright.seed.sessionflow.samples.wifi.WifiSupervisor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1-E item 4 · one supervisor class per lane (R6, the owner: "each flow shall implement its own supervisor — CallSupervisor, AdSupervisor,
 * SmsSupervisor"): each sample's class is named as its flow names the lane — statewalk's registered supervisor and table, the log lines.
 */
class SupervisorClassPerLaneTest {

    private final RoutesphereKit kit = new World().kit(World.settings());

    @Test
    void eachSamplesSupervisorClass_isNamedAsItsLane() {
        named(new AdSupervisor(new AdFlow(kit, Map.of(), Map.of(), Map.of(), false, new AdQuota())));
        named(new CallSupervisor(new VoiceFlow(kit, Map.of(), Map.of(), Map.of())));
        named(new SmsSupervisor(SmsFlow.of(kit, Map.of(), Map.of(), Map.of(), true)));
        named(new WifiSupervisor(new WifiFlow(kit, Map.of(), Map.of(), Map.of())));
    }

    private static void named(RoutesphereSupervisor<?> supervisor) {
        assertEquals(supervisor.getClass().getSimpleName(), supervisor.name(), "the class is named as its lane");
        assertEquals(supervisor.name(), supervisor.flow().name(), "ONE name: the flow's");
        assertEquals(supervisor.name(), supervisor.spec().name(), "statewalk's registered supervisor");
    }
}
