package com.telcobright.seed.campaign.jdbc;

import com.telcobright.seed.campaign.api.TaskState;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-0067 item 2 (prime-context PC-0014 §8 #4): a {@code NOT IN} cannot seek the repair's index (W42: TASK_TYPE, STATE, CAMPAIGN_ID). The
 * two statements of the start's repair name the OPEN states — every {@code TaskState} whose {@code terminal()} is false — as an {@code IN}
 * list DERIVED from the enum, never written by hand: a state added later is in the list the day it is added. The behaviour over every
 * state of the enum is {@code StoreBatchAndRepairTest.a_start_closes_every_non_terminal_state_of_the_enum_…}.
 */
class RepairSeeksTheIndexTest {

    @Test
    void the_repair_names_the_open_states_as_an_in_list_derived_from_the_enum() {
        List<Integer> open = Arrays.stream(TaskState.values()).filter(s -> !s.terminal()).map(TaskState::code).toList();
        assertThat(open).as("today's open states").containsExactly(0, 1, 4, 6, 16);
        assertThat(JdbcCampaignStore.OPEN_STATE_CODES).as("the list the statements bind = the enum's, in the enum's order").containsExactlyElementsOf(open);
        String in = "STATE IN (" + String.join(", ", Collections.nCopies(open.size(), "?")) + ")";
        assertThat(JdbcCampaignStore.CLOSE_WHAT_IS_NOT_FINAL).as("the close seeks the index").contains(in).doesNotContain("NOT IN");
        assertThat(JdbcCampaignStore.COUNT_THE_TASKS).as("the count's 'not final' column the same way").contains("WHEN " + in + " THEN 1 ELSE 0").doesNotContain("NOT IN");
    }
}
