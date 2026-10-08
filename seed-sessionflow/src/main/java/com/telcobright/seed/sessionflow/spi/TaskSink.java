package com.telcobright.seed.sessionflow.spi;

import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.sessionflow.api.SessionFlowContext;

/**
 * Where a session's CLOSED task goes — the host's store of the campaign_task rows. The base closes the task itself at the end of
 * every session that carries one ({@code SessionFlowContext.task}): completed (SENT) on SUCCEEDED, failed (FAILED) otherwise, with
 * the settlement's charge and the CDR's cause; this port only takes the closed row. The default keeps nothing: a plain call writes
 * no task row. An application on a campaign store hands the row to {@code CampaignService.closed}, which keeps the counters.
 *
 * <p>Called once per session, after the settlement and the CDR, before {@code onEnded}. A sink that throws loses nothing of the
 * session: the step is guarded, the fault is on the history.
 */
@FunctionalInterface
public interface TaskSink {
    TaskSink NONE = (ctx, task) -> { };

    void closed(SessionFlowContext ctx, CampaignTask closedTask);
}
