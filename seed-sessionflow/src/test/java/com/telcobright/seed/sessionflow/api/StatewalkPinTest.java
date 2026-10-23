package com.telcobright.seed.sessionflow.api;

import com.telcobright.statewalk.persistence.PersistenceMode;
import com.telcobright.statewalk.session.SessionHandler;
import com.telcobright.statewalk.session.SessionSupervisor;
import com.telcobright.statewalk.session.events.SessionActive;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seed's statewalk pin is 4.1 (P1, §A; SW-41, the outbox): the base {@link RoutesphereFlow} IS a statewalk 4 {@link SessionHandler} of
 * the final {@link SessionSupervisor}; {@code SessionActive} (4.0's G-1) and {@code ServiceEnd.detail} (4.1's S2) exist only from 4.1 —
 * this class does not compile against 4.0.0 or 3.x. The RESOLVED jar is read too: its Maven properties name the pin, whatever a nearer
 * pin might say (feedback "prove the RESOLVED version").
 */
class StatewalkPinTest {

    @Test
    void theBaseIsTheHandlerOfStatewalk4sFinalSupervisor() {
        assertThat(SessionHandler.class).isAssignableFrom(RoutesphereFlow.class);
        assertThat(SessionSupervisor.CONNECTING).isEqualTo("CONNECTING");
        assertThat(new SessionActive(1L, new com.telcobright.statewalk.session.events.Established(1L, "party", null, null)).atMs()).isEqualTo(1L);
        assertThat(PersistenceMode.parse("off")).isSameAs(PersistenceMode.OFF);
        assertThat(new com.telcobright.statewalk.session.events.ServiceEnd(1L, "NORMAL_CLEARING", "CALLER", "bytes=1").detail()).isEqualTo("bytes=1");
    }

    @Test
    void theResolvedStatewalkJarIs41() throws Exception {
        try (InputStream in = SessionSupervisor.class.getResourceAsStream("/META-INF/maven/com.telcobright/statewalk/pom.properties")) {
            assertThat(in).as("statewalk's pom.properties on the classpath").isNotNull();
            Properties p = new Properties();
            p.load(in);
            assertThat(p.getProperty("version")).isEqualTo("4.1.0");
        }
    }
}
