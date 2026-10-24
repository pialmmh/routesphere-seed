package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-K item 5 · the owner's ruling of 2026-10-24: "if we fail to identify the root level tenant at the ingress points, the request shall be
 * rejected and logs shall be generated". The seed's door, {@code engine.identifyRootLevelTenant(facts)}, says every refusal — a WARN line:
 * the app, the door (the ingress's kind and value), the session id, the reason in words — and counts it ({@code PoolStats.refusedNoRoot});
 * a rule that throws is a refusal too. It still answers null: the rejection is the ingress's own protocol answer, in its guard line
 * ({@code if (root == null) return reject…}). Flood-safe per door (the window: {@code NoRootAtTheDoorWindowTest}); with debug on, every
 * refusal is said.
 */
class NoRootAtTheDoorTest {

    private Lab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    private static LabFacts at(String sessionId, com.telcobright.seed.sessionflow.api.Ingress door) {
        return new LabFacts(sessionId, 0, door, 1, "8801711000000");
    }

    @Test
    void aDoorNoRuleNames_isRefused_saidInWords_andCounted_theDoorStillAnswersNull() {
        lab = new Lab(Lab.settings(0));

        String root = lab.engine.identifyRootLevelTenant(at("d-1", Lab.NOBODYS_DOOR));

        assertThat(root).as("the door answers null: the ingress rejects in its own protocol").isNull();
        assertThat(lab.engine.stats().refusedNoRoot()).isEqualTo(1);
        assertThat(lab.engine.saidAtTheDoor()).singleElement().asString()
            .contains("[" + lab.flow.name() + "]").contains("esl 10.10.197.9:8021").contains("session d-1")
            .contains("no rule names this door — the request is refused");
    }

    @Test
    void theRefusalIsAWarnLineInTheLog() {
        lab = new Lab(Lab.settings(0));

        String log = theLogOf(() -> lab.engine.identifyRootLevelTenant(at("d-5", Lab.NOBODYS_DOOR)));

        assertThat(log).contains("WARN").contains("NoRootAtTheDoor").contains("no root at esl 10.10.197.9:8021 (session d-5)");
    }

    /** What the log wrote while {@code act} ran (slf4j-simple, the tests' binding, writes to System.err as it is at each line). */
    private static String theLogOf(Runnable act) {
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

    @Test
    void aRuleThatThrows_isARefusalToo_saidWithItsWords_theDoorAnswersNull() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected String identifyRootLevelTenant(RoutesphereFacts facts) { throw new IllegalStateException("the rules are not loaded"); }
        });

        assertThat(lab.engine.identifyRootLevelTenant(at("d-2", Lab.BTCL_DOOR))).isNull();
        assertThat(lab.engine.stats().refusedNoRoot()).isEqualTo(1);
        assertThat(lab.engine.saidAtTheDoor()).singleElement().asString().contains("esl 10.10.199.9:8021").contains("session d-2")
            .contains("the rule failed").contains("the rules are not loaded");
    }

    @Test
    void aDoorARuleNames_isNeitherSaidNorCounted() {
        lab = new Lab(Lab.settings(0));

        assertThat(lab.engine.identifyRootLevelTenant(at("d-3", Lab.BTCL_DOOR))).isEqualTo("btcl");
        assertThat(lab.engine.stats().refusedNoRoot()).isZero();
        assertThat(lab.engine.saidAtTheDoor()).isEmpty();
    }

    @Test
    void aBurstOf1000Refusals_fromEightThreads_saysOneLine_andCountsEveryOne() throws Exception {
        lab = new Lab(Lab.settings(0));

        List<String> roots = refuseAtOnce(1000, 8);

        assertThat(roots).hasSize(1000).containsOnlyNulls();
        assertThat(lab.engine.stats().refusedNoRoot()).as("the count exact").isEqualTo(1000);
        assertThat(lab.engine.saidAtTheDoor()).as("the window's first, in full; the rest counted for its summary").hasSize(1);
    }

    @Test
    void withDebugOn_everyRefusalIsSaid() {
        lab = new Lab(Lab.settings(0).withDebug(true));

        for (int i = 0; i < 5; i++) lab.engine.identifyRootLevelTenant(at("g-" + i, Lab.NOBODYS_DOOR));

        assertThat(lab.engine.stats().refusedNoRoot()).isEqualTo(5);
        assertThat(lab.engine.saidAtTheDoor()).hasSize(5).allSatisfy(line -> assertThat(line).contains("no rule names this door"));
    }

    /** {@code n} refusals at nobody's door from {@code threads} threads let go together; every answer of the door. */
    private List<String> refuseAtOnce(int n, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> answers = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                LabFacts facts = at("b-" + i, Lab.NOBODYS_DOOR);
                answers.add(pool.submit(() -> { go.await(); return lab.engine.identifyRootLevelTenant(facts); }));
            }
            go.countDown();
            List<String> roots = new ArrayList<>();
            for (Future<String> a : answers) roots.add(a.get(30, TimeUnit.SECONDS));
            return roots;
        } finally {
            pool.shutdownNow();
        }
    }
}
