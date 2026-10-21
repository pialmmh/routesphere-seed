package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.statewalk.session.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-F1 (T's §10.1) · Step (d) routes BY THE CANDIDATE under admission: {@code routing(ctx)} runs inside the walk, before
 * {@code ctx.chosen} is set, so a lane reads {@code ctx.candidateBeingAdmitted()}. Two candidates: the first has no route (refused
 * NO_ROUTE, its reserves back), the second is routed by its own hop.
 */
class RoutingByCandidateTest {

    /** A candidate with its own creative (the hop); null = it has no route. */
    record Creative(int partnerId, String hop) implements Candidate {}

    private Lab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    @Test
    void theLaneRoutesByTheCandidateUnderAdmission_theFirstHasNoRoute_theSecondIsRouted() {
        List<Candidate> candidates = List.of(new Creative(1, null), new Creative(1, "creative-b"));
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected List<Candidate> selectCandidates(LabContext ctx) { return candidates; }
            @Override protected Tier identifyEntryPartner(LabContext ctx, Candidate candidate) { return entryOfPartner(ctx, ((Creative) candidate).partnerId()); }
            @Override protected RoutePlan routing(LabContext ctx) {
                Creative c = (Creative) ctx.candidateBeingAdmitted();
                return c.hop() == null ? RoutePlan.none() : RoutePlan.one(new LabHop(c.hop()));
            }
        });
        lab.dial("rc-1", Lab.BTCL_DOOR, 1);
        lab.answer("rc-1");
        lab.hangup("rc-1");

        LabContext ctx = lab.ctx("rc-1");
        assertThat(ctx.outcome).isEqualTo(Outcome.SUCCEEDED);
        assertThat(ctx.chosen).isEqualTo(new Creative(1, "creative-b"));
        assertThat(ctx.candidatesTried).isEqualTo(2);
        assertThat(lab.sent("rc-1")).containsExactly("INVITE creative-b");
        assertThat(lab.ledger.journal()).extracting(e -> e.verb() + " " + e.reference())
            .containsExactly("reserve rc-1#L0", "release rc-1#L0", "reserve rc-1#2#L0", "settle rc-1#2#L0");
        assertThat(ctx.candidateBeingAdmitted()).as("null outside admission").isNull();
        assertThat(ctx.history.snapshot()).anyMatch(r -> r.isNote() && r.cause().contains("candidate 1 refused: " + SessionCause.NO_ROUTE));
    }

    @Test
    void outsideAdmission_thereIsNoCandidateUnderAdmission_evenWhenNoneWasAdmitted() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), List.of(), Set.of()));
        lab.dial("rc-2", Lab.BTCL_DOOR, 1);

        assertThat(lab.ctx("rc-2").endCause).isEqualTo(SessionCause.NO_ROUTE);
        assertThat(lab.ctx("rc-2").candidateBeingAdmitted()).isNull();
    }
}
