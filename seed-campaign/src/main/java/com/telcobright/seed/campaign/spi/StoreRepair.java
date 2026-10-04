package com.telcobright.seed.campaign.spi;

/**
 * What a start found in a store and put right ({@link CampaignStore#repairAfterRestart}).
 *
 * @param tasksClosed       the tasks a dead process left not final, closed now
 * @param countersCorrected the campaigns whose counters did not say what their task rows say, set now
 * @param words             the numbers as one sentence for the log; empty when nothing was wrong
 */
public record StoreRepair(int tasksClosed, int countersCorrected, String words) {

    public static final StoreRepair NOTHING = new StoreRepair(0, 0, "");

    public boolean nothing() { return tasksClosed == 0 && countersCorrected == 0; }
}
