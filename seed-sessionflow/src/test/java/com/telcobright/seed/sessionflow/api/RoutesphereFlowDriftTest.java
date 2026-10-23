package com.telcobright.seed.sessionflow.api;

import com.telcobright.statewalk.session.SessionHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-D §1 · The drift guard of the base's shape (like statewalk's GuideDriftTest): the rules every lane must state itself are exactly the
 * abstract methods — a new lane cannot inherit the call's delivery, refund or failover by forgetting them — and what the base
 * owns stays final: every statewalk call, (b) {@code iterateResellerTenants}, (g) {@code finishSession}. The class javadoc's table marks
 * the forced ones (■), and only those.
 */
class RoutesphereFlowDriftTest {

    /** The owner's word of 2026-10-23 (decision 3; R13: the CDR's SQL left with the records road): what every lane answers itself. */
    static final Set<String> FORCED = Set.of("name", "serviceGroup", "identifyTenant", "identifyEntryPartner", "routing", "rateAtLevel",
        "delivered", "refundPolicy", "rerouteActionFor");

    @Test
    void theAbstractMethods_areExactlyTheRulesEveryLaneMustState() {
        Set<String> abstractOnes = Arrays.stream(RoutesphereFlow.class.getDeclaredMethods())
            .filter(m -> Modifier.isAbstract(m.getModifiers()))
            .map(Method::getName)
            .collect(Collectors.toCollection(TreeSet::new));
        assertThat(abstractOnes).as("the forced rules of RoutesphereFlow").containsExactlyInAnyOrderElementsOf(FORCED);
    }

    @Test
    void everyStatewalkCall_iterateResellerTenants_andFinishSession_areFinal() {
        for (Method call : SessionHandler.class.getDeclaredMethods()) {
            if (Modifier.isStatic(call.getModifiers()) || call.isSynthetic()) continue;
            assertThat(isFinal(declared(call.getName(), call.getParameterCount())))
                .as("the statewalk call %s is the base's, final", call.getName()).isTrue();
        }
        assertThat(isFinal(declared("iterateResellerTenants", 1))).as("(b) the tier walk is the base's").isTrue();
        assertThat(isFinal(declared("finishSession", 2))).as("(g) the finish is the base's").isTrue();
    }

    @Test
    void theClassJavadocsTable_marksTheForcedOnes_andOnlyThose() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/telcobright/seed/sessionflow/api/RoutesphereFlow.java"), StandardCharsets.UTF_8);
        String classJavadoc = source.substring(0, source.indexOf("public abstract class RoutesphereFlow"));
        Set<String> marked = new TreeSet<>();
        Matcher m = Pattern.compile("(\\w+)■").matcher(classJavadoc);
        while (m.find()) marked.add(m.group(1));
        assertThat(marked).as("the methods the class javadoc marks ■ (abstract: every lane answers it)").containsExactlyInAnyOrderElementsOf(FORCED);
    }

    private static Method declared(String name, int parameters) {
        return Arrays.stream(RoutesphereFlow.class.getDeclaredMethods())
            .filter(m -> m.getName().equals(name) && m.getParameterCount() == parameters && !m.isBridge() && !m.isSynthetic())
            .findFirst()
            .orElseThrow(() -> new AssertionError("RoutesphereFlow declares no " + name + " of " + parameters + " parameter(s)"));
    }

    private static boolean isFinal(Method m) { return Modifier.isFinal(m.getModifiers()); }
}
