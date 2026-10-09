package com.telcobright.seed.sessionflow;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.sessionflow.internal.ChannelSlots;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The partner's concurrent calls against its cap: held by a call id, released once, healed when the call is gone. */
class ChannelSlotsTest {

    private final ChannelSlots slots = new ChannelSlots();

    private static Partner partner(int id, Integer cap) {
        Partner p = new Partner();
        p.setIdPartner(id);
        p.setField2(cap);
        return p;
    }

    @Test
    void theCapCountsLiveCalls_andAReleaseFreesExactlyOne() {
        Partner twoLines = partner(7, 2);

        assertThat(slots.acquire("a", "res_44", twoLines)).isTrue();
        assertThat(slots.acquire("b", "res_44", twoLines)).isTrue();
        assertThat(slots.acquire("c", "res_44", twoLines)).isFalse();
        slots.release("a");
        slots.release("a");                                             // a second release of the same call frees nothing more
        assertThat(slots.activeOf("res_44", 7)).isEqualTo(1);
        assertThat(slots.acquire("c", "res_44", twoLines)).isTrue();
        assertThat(slots.acquire("d", "res_44", twoLines)).isFalse();
    }

    @Test
    void aPartnerWithNoCapHoldsNothing() {
        assertThat(slots.acquire("a", "res_44", partner(8, null))).isTrue();
        assertThat(slots.acquire("b", "res_44", partner(8, 0))).isTrue();

        assertThat(slots.held()).isZero();
    }

    @Test
    void theSamePartnerIdInTwoTenantsIsTwoPartners() {
        assertThat(slots.acquire("a", "res_44", partner(7, 1))).isTrue();

        assertThat(slots.acquire("b", "res_45", partner(7, 1))).as("partner 7 of another tenant has its own cap").isTrue();
        assertThat(slots.acquire("c", "res_44", partner(7, 1))).isFalse();
    }

    @Test
    void aCallThatTakesAnotherCandidatesSlotGivesTheFirstBack() {
        assertThat(slots.acquire("a", "res_44", partner(7, 1))).isTrue();
        assertThat(slots.acquire("a", "res_44", partner(9, 1))).isTrue();     // the same call, the next candidate's partner

        assertThat(slots.activeOf("res_44", 7)).isZero();
        assertThat(slots.activeOf("res_44", 9)).isEqualTo(1);
        assertThat(slots.held()).isEqualTo(1);
    }

    @Test
    void aSlotWhoseCallIsGoneIsGivenBack_afterTwoSweepsAgree() {
        slots.acquire("gone", "res_44", partner(7, 1));
        slots.acquire("live", "res_44", partner(9, 1));
        Set<String> liveCalls = Set.of("live");

        assertThat(slots.reconcile(liveCalls::contains)).as("the first sweep only suspects").isZero();
        assertThat(slots.activeOf("res_44", 7)).isEqualTo(1);
        assertThat(slots.reconcile(liveCalls::contains)).isEqualTo(1);

        assertThat(slots.activeOf("res_44", 7)).isZero();
        assertThat(slots.activeOf("res_44", 9)).as("a live call keeps its slot").isEqualTo(1);
    }

    @Test
    void aCallSeenLiveAgainIsNoLongerSuspected() {
        slots.acquire("flaky", "res_44", partner(7, 1));

        slots.reconcile(id -> false);
        slots.reconcile(id -> true);
        assertThat(slots.reconcile(id -> false)).as("the suspicion started over").isZero();

        assertThat(slots.activeOf("res_44", 7)).isEqualTo(1);
    }
}
