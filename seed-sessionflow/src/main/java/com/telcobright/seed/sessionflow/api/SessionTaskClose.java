package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.TaskCharge;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * CLOSE_TASK · The task record of the session ({@link SessionFlowContext#task}) is closed by the base at the end, once, whatever
 * the application (the owner, 2026-10-09: every session kind takes a CampaignTask in, and no flow forgets to close it): completed
 * ({@code SENT}) on SUCCEEDED, failed ({@code FAILED}) on every other end, with the moment of the end, the billed seconds, the leaf
 * tier's charge as the settlement left it and the CDR's cause word — then handed to the kit's {@code TaskSink}. A DEFERRED session
 * is not over: its task stays open. A session that carries no task closes nothing.
 */
final class SessionTaskClose<C extends SessionFlowContext> {

    private final SessionFlow<C> flow;

    SessionTaskClose(SessionFlow<C> flow) { this.flow = flow; }

    void close(C ctx, String outcome) {
        CampaignTask task = ctx.task;
        if (task == null || ctx.taskClosed || task.state().terminal()) return;
        if (SessionState.DEFERRED.equals(outcome)) {
            ctx.history.note(flow.name(), "the task " + task.uniqueId() + " stays open: the session is deferred");
            return;
        }
        ctx.taskClosed = true;
        CampaignTask closed = closedRow(ctx, outcome, task);
        ctx.task = closed;
        flow.kit().tasks().closed(ctx, closed);
    }

    private CampaignTask closedRow(C ctx, String outcome, CampaignTask task) {
        if (ctx.answered() && !task.answered()) task = task.answered(Instant.ofEpochMilli(ctx.answeredAtMs));
        Instant at = Instant.ofEpochMilli(flow.kit().clock().millis());
        int watched = (int) Math.round(ctx.durationSec);
        String cause = flow.cdrCause(ctx, outcome);
        TaskCharge paid = chargeOf(ctx);
        return SessionState.SUCCEEDED.equals(outcome) ? task.completed(at, watched, paid, cause) : task.failed(at, watched, cause, paid);
    }

    /**
     * The leaf tier's charge as the task's: what the settlement charged (before the settlement, what the tier reserved) — units on
     * a unit account, cash on a cash one, on the account the tier was charged on. Nothing charged, or no account = FREE.
     */
    static TaskCharge chargeOf(SessionFlowContext ctx) {
        if (ctx.levels.isEmpty()) return TaskCharge.FREE;
        LevelAdmission leaf = ctx.levels.get(0);
        BigDecimal amount = ctx.settlements.isEmpty() ? leaf.getReservedAmount() : ctx.settlements.get(0).charged();
        if (amount == null) amount = BigDecimal.ZERO;
        Long account = accountOf(leaf);
        if (account == null || amount.signum() == 0) return TaskCharge.FREE;
        boolean cash = leaf.getUom() == null || "BDT".equalsIgnoreCase(leaf.getUom());
        return new TaskCharge(account, leaf.getUom(), cash ? BigDecimal.ZERO : amount, cash ? amount : BigDecimal.ZERO, leaf.getRatePrefix());
    }

    /** The account the tier pays on: the one the switch named, else the one the ledger answered with. */
    static Long accountOf(LevelAdmission level) {
        return level.getPackageAccountId() != null ? level.getPackageAccountId() : level.getChargeAccountId();
    }
}
