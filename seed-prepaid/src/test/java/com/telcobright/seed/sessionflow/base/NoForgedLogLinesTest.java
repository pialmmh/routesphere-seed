package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-L item 1 · no value a request carries writes a log line of its own (the architect's review of P1-K's door): the ad's session id is
 * built from the request's MAC ({@code ad-<tenant>-<mac>-<ms>-<n>}), a lane's failure may quote the request — a CR/LF in either would
 * forge a line. Every value the seed logs from a request is cleaned in ONE place ({@code internal.LogText}): control characters escaped
 * ({@code \n} → {@code \\n}, others {@code \\uXXXX}, U+2028/2029 too), at most 200 characters ("…" when cut). The log is read as the
 * tests' binding writes it (slf4j-simple on System.err).
 */
class NoForgedLogLinesTest {

    private Lab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    @Test
    void aSessionIdCarryingALineBreak_isOneLineAtTheDoor_itsBreakEscaped() {
        lab = new Lab(Lab.settings(0));
        String forged = "d-1\n[" + lab.flow.name() + "] forged";

        List<String> lines = linesOf(theLogOf(() -> lab.engine.identifyRootLevelTenant(new LabFacts(forged, 0, Lab.NOBODYS_DOOR, 1, "8801711000000"))));

        assertThat(lines).noneMatch(l -> l.startsWith("[" + lab.flow.name() + "] forged"));
        assertThat(lines).filteredOn(l -> l.contains("no root at")).singleElement().asString().contains("(session d-1\\n[" + lab.flow.name() + "] forged)");
    }

    @Test
    void anIngressCarryingALineBreak_isOneLineAtTheDoor_andOneDoor() {
        lab = new Lab(Lab.settings(0));
        Ingress forged = new Ingress(Ingress.VLAN, "10.10.1.1:7\nFORGED-DOOR");

        List<String> lines = linesOf(theLogOf(() -> lab.engine.identifyRootLevelTenant(new LabFacts("d-3", 0, forged, 1, "8801711000000"))));

        assertThat(lines).noneMatch(l -> l.startsWith("FORGED-DOOR"));
        assertThat(lab.engine.saidAtTheDoor()).singleElement().asString().contains("no root at vlan 10.10.1.1:7\\nFORGED-DOOR");
    }

    @Test
    void aFailedRulesTextCarryingALineBreak_isEscaped_inItsLineAndItsStack() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected String identifyRootLevelTenant(RoutesphereFacts facts) { throw new IllegalArgumentException("bad door\nFORGED-LINE"); }
        });

        List<String> lines = linesOf(theLogOf(() -> lab.engine.identifyRootLevelTenant(new LabFacts("d-2", 0, Lab.BTCL_DOOR, 1, "8801711000000"))));

        assertThat(lines).noneMatch(l -> l.startsWith("FORGED-LINE"));
        assertThat(lines).filteredOn(l -> l.contains("no root at")).singleElement().asString().contains("bad door\\nFORGED-LINE");
        assertThat(lines).as("the stack is still there").anyMatch(l -> l.trim().startsWith("at "));
    }

    @Test
    void withDebugOn_aSessionWhoseIdCarriesALineBreak_writesEachOfItsStepsOnOneLine() {
        lab = new Lab(Lab.settings(0).withDebug(true));

        List<String> lines = linesOf(theLogOf(() -> lab.dial("s-1\nFORGED", Lab.BTCL_DOOR, 1)));

        assertThat(lines).filteredOn(l -> l.contains("s-1\\nFORGED |")).as("the seed's step lines, the id escaped").isNotEmpty();
        assertThat(lines).noneMatch(l -> l.startsWith("FORGED |"));
    }

    /** What the log wrote while {@code act} ran (slf4j-simple, the tests' binding, writes to System.err as it is at each line). */
    static String theLogOf(Runnable act) {
        PrintStream before = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            act.run();
        } finally {
            System.setErr(before);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    static List<String> linesOf(String log) { return log.lines().toList(); }
}
