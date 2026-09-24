package com.telcobright.seed.campaign.internal;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignCounters;
import com.telcobright.seed.campaign.api.Creative;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One campaign as this process sees it: the row from the store (frozen at load) plus what happened here since —
 * the live (pending) views, the completed and failed ones, the creative rotation and today's served count.
 * A reload swaps the row and keeps the live count: the views in progress do not belong to the old row.
 */
final class LiveCampaign {

    volatile Campaign row;
    final AtomicInteger pending = new AtomicInteger();
    final AtomicInteger sentSinceLoad = new AtomicInteger();
    final AtomicInteger failedSinceLoad = new AtomicInteger();
    final AtomicInteger rotation = new AtomicInteger();
    private final AtomicInteger servedToday = new AtomicInteger();
    private volatile LocalDate servedDay;

    LiveCampaign(Campaign row, ZoneId zone) {
        this.row = row;
        this.servedDay = LocalDate.now(zone);
    }

    /** A reload: the store's counters already include what we completed and told it; only the live views carry over. */
    void reloaded(Campaign fresh) {
        row = fresh;
        sentSinceLoad.set(0);
        failedSinceLoad.set(0);
    }

    int sent() { return row.sentTaskCount() + sentSinceLoad.get(); }

    int failed() { return row.failedTaskCount() + failedSinceLoad.get(); }

    /** Quota, with the store's pending count NOT counted twice: only this process's live views count as pending. */
    boolean quotaLeft() {
        return row.unlimited() || sent() + pending.get() < row.totalTaskCount();
    }

    /** Take a slot if one is left (compare-and-set, so concurrent views cannot overrun the quota). */
    boolean tryTake() {
        while (true) {
            int p = pending.get();
            if (!row.unlimited() && sent() + p >= row.totalTaskCount()) return false;
            if (pending.compareAndSet(p, p + 1)) return true;
        }
    }

    Creative rotate() {
        var list = row.creatives();
        if (list.isEmpty()) return null;
        int i = Math.floorMod(rotation.getAndIncrement(), list.size());
        return list.get(i);
    }

    int servedToday(Instant at, ZoneId zone) {
        rollDay(at, zone);
        return servedToday.get();
    }

    void served(Instant at, ZoneId zone) {
        rollDay(at, zone);
        servedToday.incrementAndGet();
    }

    private void rollDay(Instant at, ZoneId zone) {
        LocalDate today = at.atZone(zone).toLocalDate();
        if (!today.equals(servedDay)) {
            synchronized (this) {
                if (!today.equals(servedDay)) { servedToday.set(0); servedDay = today; }
            }
        }
    }

    CampaignCounters counters(Instant at, ZoneId zone) {
        return new CampaignCounters(row.id(), row.status(), row.totalTaskCount(), sent(), failed(), pending.get(), servedToday(at, zone));
    }
}
