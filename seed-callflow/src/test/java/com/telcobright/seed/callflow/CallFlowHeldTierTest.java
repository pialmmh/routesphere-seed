package com.telcobright.seed.callflow;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.api.CallCause;
import com.telcobright.seed.callflow.api.TierRate;
import com.telcobright.seed.callflow.dependencies.CallFlowKit;
import com.telcobright.seed.callflow.samples.Scene;
import com.telcobright.seed.callflow.samples.VoiceFlow;
import com.telcobright.seed.callflow.spi.LedgerPort;
import com.telcobright.seed.callflow.testkit.InMemoryLedger;
import com.telcobright.statewalk.pipeline.StepMode;
import com.telcobright.statewalk.session.AdmissionVerdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * A tier the application's OWN step admits (the call switch's C6: {@code ReserveBalanceStep} rates, chooses the account and holds one
 * unit in one move — the reserve is its affordability test) is taken by the base as it is: its reference named, the kit's ledger
 * asked nothing for it, given back through the ledger's release when a later tier refuses. The step's refusal, in the ledger's own
 * words, is the call's cause; its fault is a system fault. A dry run tells the hook, and takes the step's mock with nothing held.
 */
class CallFlowHeldTierTest {

    private final Scene scene = new Scene();

    /** The sample voice flow whose LEAF tier is admitted by its own step; the root tier goes through the base's ledger as before. */
    private static final class SteppedVoice extends VoiceFlow {
        private final String leafVerdict;
        volatile Boolean dryRunSeen;
        volatile LevelAdmission heldLevel;

        SteppedVoice(CallFlowKit kit, String leafVerdict) {
            super(kit, Map.of("10.0.0.7", 701), Map.of("res_44#701", new BigDecimal("0.60"), "btcl#44", new BigDecimal("0.40")),
                List.of(new Route("017", "GP-trunk", 5)));
            this.leafVerdict = leafVerdict;
        }

        @Override
        protected TierRate rateAtLevel(Call call, Tenant tier, Partner partner, int levelIndex) {
            if (levelIndex != 0) return super.rateAtLevel(call, tier, partner, levelIndex);
            dryRunSeen = call.dryRun;
            if ("refuse".equals(leafVerdict)) throw new LedgerPort.LedgerRefusal("NO_BALANCE_INT_OUT", "no cash account can fund a call abroad");
            if ("fault".equals(leafVerdict)) throw new LedgerPort.LedgerFault("the WAL writer is down");
            boolean zeroRated = "held-zero".equals(leafVerdict);
            LevelAdmission level = new LevelAdmission(levelIndex, tier, partner, null);
            level.setRate(zeroRated ? BigDecimal.ZERO : new BigDecimal("0.60"));
            level.setUom(call.dryRun ? "SKIPPED" : "BDT");
            level.setReservedAmount(call.dryRun || zeroRated ? BigDecimal.ZERO : new BigDecimal("0.60"));
            if (!call.dryRun) level.incrementReservationCount();                     // the step's reserve was made — of the rate, even a zero one
            heldLevel = level;
            return TierRate.held(level);
        }
    }

    private SteppedVoice voice(String leafVerdict) { return new SteppedVoice(scene.kit(Scene.settings(4)), leafVerdict); }

    private static VoiceFlow.Call call(String id) { return Scene.call(id, "10.0.0.7", "01712345678"); }

    @Test
    void aTierHeldByTheApplicationsOwnStep_isTakenAsItIs_theBaseAsksItsLedgerNothingForIt() {
        SteppedVoice voice = voice("held");
        VoiceFlow.Call call = call("h-1");

        assertThat(voice.preprocess(call)).isNull();
        AdmissionVerdict verdict = voice.admission(call, StepMode.LIVE);

        assertThat(verdict.accepted()).isTrue();
        assertThat(voice.dryRunSeen).isFalse();
        assertThat(call.levels).hasSize(2);
        assertThat(call.levels.get(0)).as("the step's own level is the tier's").isSameAs(voice.heldLevel);
        assertThat(call.levels.get(0).getDebitReference()).isEqualTo("h-1#L0");
        assertThat(call.levels.get(0).getTotalReserved()).isEqualByComparingTo("0.60");
        assertThat(call.levels.get(0).getReservationCount()).isEqualTo(1);
        assertThat(scene.ledger.journal()).extracting(InMemoryLedger.Entry::verb, InMemoryLedger.Entry::reference)
            .as("the kit's ledger saw the root tier only").containsExactly(tuple("reserve", "h-1#L1"));
        assertThat(scene.ledger.balanceOf("btcl", 44)).isEqualByComparingTo("99.60");
        assertThat(scene.ledger.balanceOf("res_44", 701)).as("the base moved nothing for the held tier").isEqualByComparingTo("100.00");
    }

    @Test
    void aLaterTierRefusing_givesTheHeldTierBack_throughTheLedgersRelease() {
        scene.ledger.refuseOn("btcl", 44, CallCause.INSUFFICIENT_BALANCE);
        SteppedVoice voice = voice("held");
        VoiceFlow.Call call = call("h-2");
        voice.preprocess(call);

        AdmissionVerdict verdict = voice.admission(call, StepMode.LIVE);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejectCause()).isEqualTo(CallCause.INSUFFICIENT_BALANCE);
        assertThat(call.levels).isEmpty();
        assertThat(scene.ledger.journal()).extracting(InMemoryLedger.Entry::verb, InMemoryLedger.Entry::reference)
            .as("compensate (C7): the held tier is released by the ledger's verb, under its reference").contains(tuple("release", "h-2#L0"));
    }

    @Test
    void theStepsRefusal_inTheLedgersWords_isTheCallsCause() {
        SteppedVoice voice = voice("refuse");
        VoiceFlow.Call call = call("h-3");
        voice.preprocess(call);

        AdmissionVerdict verdict = voice.admission(call, StepMode.LIVE);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejectCause()).isEqualTo("NO_BALANCE_INT_OUT");
        assertThat(call.systemFault).isNull();
        assertThat(scene.ledger.count("reserve")).as("the leaf refused first: the root was never asked").isZero();
    }

    @Test
    void theStepsFault_isBillingSystemError_neverABalanceCause() {
        SteppedVoice voice = voice("fault");
        VoiceFlow.Call call = call("h-4");
        voice.preprocess(call);

        AdmissionVerdict verdict = voice.admission(call, StepMode.LIVE);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejectCause()).isEqualTo(CallCause.BILLING_SYSTEM_ERROR);
        assertThat(call.systemFault).isEqualTo(CallCause.BILLING_SYSTEM_ERROR);
    }

    @Test
    void aHeldTierOfZero_isStillSettledOnce_theSwitchsZeroReserveRowMustDieAtSettle() {
        SteppedVoice voice = voice("held-zero");
        VoiceFlow.Call call = call("h-7");
        voice.preprocess(call);
        assertThat(voice.admission(call, StepMode.LIVE).accepted()).isTrue();
        assertThat(call.levels.get(0).getTotalReserved()).isEqualByComparingTo("0");
        assertThat(call.levels.get(0).getReservationCount()).isEqualTo(1);

        voice.settle(call);

        assertThat(scene.ledger.timesAsked("settle")).as("both tiers reach the ledger's settle: the zero one too").isEqualTo(2);
        assertThat(call.settlements.get(0).closed()).isTrue();
        assertThat(call.settlements.get(0).charged()).isEqualByComparingTo("0");

        VoiceFlow.Call mock = call("h-8");
        voice.simulate(mock);
        assertThat(mock.levels.get(0).getReservationCount()).as("a dry run's mock counts no reservation").isZero();
    }

    @Test
    void aDryRun_tellsTheHook_andTakesTheStepsMock_withNothingHeld() {
        SteppedVoice voice = voice("held");
        VoiceFlow.Call call = call("h-5");

        voice.simulate(call);

        assertThat(voice.dryRunSeen).isTrue();
        assertThat(call.dryRun).isTrue();
        assertThat(call.admitted).isTrue();
        assertThat(call.levels.get(0).getUom()).isEqualTo("SKIPPED");
        assertThat(call.levels.get(0).getTotalReserved()).isEqualByComparingTo("0");
        assertThat(scene.ledger.count("reserve")).isZero();
        assertThat(scene.ledger.openReserves()).isZero();

        VoiceFlow.Call live = call("h-6");
        voice.preprocess(live);
        voice.admission(live, StepMode.LIVE);
        assertThat(live.dryRun).isFalse();
    }
}
