package com.telcobright.seed.sessionflow.internal;

import com.fasterxml.jackson.databind.util.StdConverter;
import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.TaskCharge;
import com.telcobright.seed.campaign.api.TaskState;

import java.time.Instant;
import java.util.Map;

/**
 * The session's task row in the session's persisted context (§B, §L.6): statewalk's store writes the context with a plain Jackson mapper,
 * which cannot write the task's {@code Instant}s — so the task goes to the store as its {@link TaskLine} (the same components, the times
 * as epoch millis) and comes back as the same {@code CampaignTask}: a session restored from its row closes its own task at its end.
 */
public final class TaskJson {

    private TaskJson() {}

    /** The task's picture in the store: its components, the times as epoch millis (null = not yet). */
    public record TaskLine(String uniqueId, String tenantId, int campaignId, int partnerId, CampaignKind kind, String subject, String creativeId,
                           String zone, String site, String clientRef, TaskState state, Long createdAtMs, Long answeredAtMs, Long endedAtMs,
                           int billsec, String endCause, TaskCharge charge, Map<String, Object> detail) {}

    /** CampaignTask → its picture. */
    public static final class Out extends StdConverter<CampaignTask, TaskLine> {
        @Override
        public TaskLine convert(CampaignTask t) {
            return new TaskLine(t.uniqueId(), t.tenantId(), t.campaignId(), t.partnerId(), t.kind(), t.subject(), t.creativeId(), t.zone(), t.site(),
                t.clientRef(), t.state(), millis(t.createdAt()), millis(t.answeredAt()), millis(t.endedAt()), t.billsec(), t.endCause(), t.charge(),
                t.detail());
        }
    }

    /** The picture → the CampaignTask again. */
    public static final class In extends StdConverter<TaskLine, CampaignTask> {
        @Override
        public CampaignTask convert(TaskLine l) {
            return new CampaignTask(l.uniqueId(), l.tenantId(), l.campaignId(), l.partnerId(), l.kind(), l.subject(), l.creativeId(), l.zone(), l.site(),
                l.clientRef(), l.state(), instant(l.createdAtMs()), instant(l.answeredAtMs()), instant(l.endedAtMs()), l.billsec(), l.endCause(),
                l.charge(), l.detail());
        }
    }

    private static Long millis(Instant at) { return at == null ? null : at.toEpochMilli(); }

    private static Instant instant(Long ms) { return ms == null ? null : Instant.ofEpochMilli(ms); }
}
