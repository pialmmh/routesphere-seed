package com.telcobright.seed.prepaid.parity;

import org.junit.jupiter.api.AfterEach;

import java.util.Map;

/**
 * The rows' common ground: the scene of one row, on the lane under test. Item 1 wrote the rows on the BASE's lane (the call's answers,
 * no Prepaid: {@link ParityScene.Lane#ON_THE_BASE}); the {@code …OnPrepaidTest} classes run the SAME rows, the same assertions, on the
 * call's Prepaid ({@link ParityScene.Lane#ON_PREPAID}, design §10.1) — where the base's gaps are written as they should be and run.
 */
abstract class ParityRows {

    protected ParityScene scene;

    @AfterEach
    void close() { if (scene != null) scene.close(); }

    /** The lane the rows run on. */
    protected ParityScene.Lane lane() { return ParityScene.Lane.ON_THE_BASE; }

    protected ParityScene open() { return ParityScene.open(Map.of(), lane()); }

    protected ParityScene open(Map<String, String> balances) { return ParityScene.open(balances, lane()); }

    protected ParityScene openWithEngine(long firstSec, long periodSec, Map<String, String> balances) {
        return ParityScene.openWithEngine(firstSec, periodSec, balances, lane());
    }
}
