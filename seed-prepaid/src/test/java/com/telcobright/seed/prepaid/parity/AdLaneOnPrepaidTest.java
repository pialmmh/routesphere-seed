package com.telcobright.seed.prepaid.parity;

import com.telcobright.statewalk.session.Outcome;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static com.telcobright.seed.prepaid.parity.ParityScene.ADCO;
import static com.telcobright.seed.prepaid.parity.ParityScene.DOMESTIC;
import static com.telcobright.seed.prepaid.parity.ParityScene.UNILEVER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L3 · The ad lane on the prepaid library, on the REAL switch ledger ({@link AdLane}): charged at admission on every tier, the best
 * matching package first, cash at the rate-plan price when none matches, nothing at the end. Adco (705) at res_2 holds 51 AD_view 100
 * (2030), 52 AD_view 5 (2027 — the soonest), 53 AD_sec 300, 54 BDT 20 and 55 AD_view 50 EXPIRED; the reseller (2) at the root holds only
 * TF_min (21) and BDT (22): a talk-minute package never pays a view, so the root's tier pays cash.
 */
class AdLaneOnPrepaidTest {

    private ParityScene scene;

    @AfterEach
    void close() { if (scene != null) scene.close(); }

    /** A1 · An image view: the SOONEST-expiring view package pays one view; the root's tier pays cash at the price; no hold row. */
    @Test
    void A1_anImageView_theSoonestViewPackagePaysOne_theRootsTierPaysCash_noHoldRow() {
        scene = ad(Map.of());

        scene.admitted("v1", ADCO, DOMESTIC);

        assertThat(scene.balance("res_2", 52)).as("the soonest-expiring view package").isEqualByComparingTo("4");
        assertThat(scene.balance("res_2", 51)).as("the later one waits").isEqualByComparingTo("100");
        assertThat(scene.balance("res_2", 55)).as("an expired package never pays").isEqualByComparingTo("50");
        assertThat(scene.balance("res_2", 53)).as("seconds pay videos only").isEqualByComparingTo("300");
        assertThat(scene.balance("res_2", 54)).as("cash only when no package matches").isEqualByComparingTo("20");
        assertThat(scene.balance("btcl", 22)).as("the reseller has no view package: its cash, at the price").isEqualByComparingTo("49.60");
        assertThat(scene.balance("btcl", 21)).as("talk minutes never pay a view").isEqualByComparingTo("10");
        assertThat(scene.rows("res_2")).as("charged now: no hold row").isZero();
        assertThat(scene.recorder.seen).anyMatch(s -> s.startsWith("charge v1#L0 ")).noneMatch(s -> s.startsWith("reserve"));
    }

    /** A2 · A video view: its seconds come off the seconds package; the view packages are not touched. */
    @Test
    void A2_aVideoView_paysItsSecondsFromTheSecondsPackage() {
        scene = ad(Map.of());

        scene.admitted("v2", ADCO, AdLane.VIDEO);

        assertThat(scene.balance("res_2", 53)).isEqualByComparingTo(String.valueOf(300 - AdLane.VIDEO_SECONDS));
        assertThat(scene.balance("res_2", 52)).isEqualByComparingTo("5");
        assertThat(scene.balance("res_2", 51)).isEqualByComparingTo("100");
    }

    /** A3 · No matching package can pay (both view packages empty, the third expired): the rate-plan price comes off the cash. */
    @Test
    void A3_noPackageMatches_theRatePlanPriceComesOffTheCash() {
        scene = ad(Map.of("res_2#51", "0", "res_2#52", "0"));

        scene.admitted("v3", ADCO, DOMESTIC);

        assertThat(scene.balance("res_2", 54)).isEqualByComparingTo("19.60");
        assertThat(scene.balance("res_2", 55)).as("the expired package is never tried").isEqualByComparingTo("50");
    }

    /** A4 · The end moves nothing — shown or not ("no refunds/adjustments are required"): the charge of admission stands. */
    @Test
    void A4_theEndMovesNothing_shownOrNot_noRefund() {
        scene = ad(Map.of());
        CallContext shown = scene.admitted("v4", ADCO, DOMESTIC);
        CallContext failed = scene.admitted("v5", ADCO, DOMESTIC);

        scene.answer(shown);
        scene.flow.settle(shown, Outcome.SUCCEEDED);
        scene.flow.release(failed);

        assertThat(scene.balance("res_2", 52)).as("two views, two units").isEqualByComparingTo("3");
        assertThat(scene.balance("btcl", 22)).isEqualByComparingTo("49.20");
        assertThat(shown.settlements).extracting(TierSettlement::returned).allMatch(r -> r.signum() == 0);
        assertThat(failed.settlements).extracting(TierSettlement::returned).allMatch(r -> r.signum() == 0);
        assertThat(scene.recorder.seen).noneMatch(s -> s.startsWith("refund") || s.startsWith("release"));
    }

    /** A5 · A partner's talk minutes never pay a view: Unilever's TF_min package stays, its cash pays the price. */
    @Test
    void A5_anotherUnitsPackageNeverPaysAView() {
        scene = ad(Map.of());

        scene.admitted("v6", UNILEVER, DOMESTIC);

        assertThat(scene.balance("res_2", 11)).isEqualByComparingTo("2.5");
        assertThat(scene.balance("res_2", 12)).isEqualByComparingTo("99.60");
    }

    private static ParityScene ad(Map<String, String> balances) {
        return ParityScene.openWith(ParityScene.perEventSettings(), balances, AdLane::onPrepaid);
    }

    @SuppressWarnings("unused")
    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
