package com.telcobright.seed.callflow;

import com.telcobright.seed.callflow.api.AdCause;
import com.telcobright.seed.callflow.api.PreprocessVerdict;
import com.telcobright.seed.callflow.api.RoutedSessionTimings;
import com.telcobright.seed.callflow.internal.RoutedSessionSupervisor;
import com.telcobright.seed.callflow.publishes.Preprocessed;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.registry.InternalEventResolver;
import com.telcobright.statewalk.registry.StatemachineRegistry;
import com.telcobright.statewalk.session.AdmissionVerdict;
import com.telcobright.statewalk.session.HistoryCarrier;
import com.telcobright.statewalk.session.RecordingMachine;
import com.telcobright.statewalk.session.SdrRecord;
import com.telcobright.statewalk.session.SdrSink;
import com.telcobright.statewalk.session.SessionContext;
import com.telcobright.statewalk.session.SessionHistory;
import com.telcobright.statewalk.session.TransitionRecord;
import com.telcobright.statewalk.session.events.ServiceEnd;
import com.telcobright.statewalk.session.events.SignalingDone;
import com.telcobright.statewalk.state.StateMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The routed base's contract (design §2.1): the graph validates with PREPROCESSING in front; a pooled subclass with a
 * mutable field is refused at build; a machine returned to the pool starts the next request in PREPROCESSING with a
 * FRESH context; a preprocessing refusal, a preprocessing timeout and a hung machine all end FAILED with the named cause
 * and one SDR each.
 */
class RoutedSessionSupervisorTest {

    public record Shown() implements StatemachineEvent {}

    public static class Ctx extends SessionContext {
        public String refuseWith;          // a preprocessing refusal with this cause
        public boolean pendingForever;     // preprocessing never answers
        public boolean rejectAdmission;
        public String sawInPreprocess;     // what the machine's context held when preprocessing ran
        public List<String> notes = new ArrayList<>();
    }

    public static class ViewCtx implements HistoryCarrier {
        final SessionHistory history;
        ViewCtx(SessionHistory h) { history = h; }
        @Override public SessionHistory history() { return history; }
        @Override public String historyName() { return "view"; }
    }

    /** The fake signaling child: Shown → DONE (publishes SignalingDone). */
    public static class View extends RecordingMachine<ViewCtx> {
        @Override protected StateMap defineStates() {
            return StateMap.builder().initialState("WAIT")
                .state("WAIT").interim().timeout(60, TimeUnit.SECONDS, "GONE").on(Shown.class, "DONE")
                .state("DONE").finalState().timeout(1, TimeUnit.SECONDS, "DONE").onEntry(self -> ((View) self).publishEvent(new SignalingDone("shown")))
                .state("GONE").finalState().timeout(1, TimeUnit.SECONDS, "GONE")
                .build();
        }
        @Override protected ViewCtx createContext() { return null; }
    }

    /** The fake routed supervisor: every hook writes into the context, nothing into a field. */
    public static class Sup extends RoutedSessionSupervisor<Ctx> {
        private final SdrSink sink;
        private final RoutedSessionTimings timings;
        public Sup(SdrSink sink, RoutedSessionTimings timings) { this.sink = sink; this.timings = timings; }

        @Override protected RoutedSessionTimings timings() { return timings; }
        @Override protected PreprocessVerdict preprocess(Ctx ctx) {
            ctx.sawInPreprocess = ctx.sessionKey + "/" + ctx.notes.size();
            ctx.notes.add("preprocessed " + ctx.sessionKey);
            if (ctx.pendingForever) return PreprocessVerdict.later();
            return ctx.refuseWith == null ? PreprocessVerdict.accept() : PreprocessVerdict.refuse(ctx.refuseWith);
        }
        @Override protected AdmissionVerdict runAdmission(Ctx ctx) {
            ctx.notes.add("admitted " + ctx.sessionKey);
            return ctx.rejectAdmission ? AdmissionVerdict.reject(AdCause.NO_FUNDED_CAMPAIGN.name()) : AdmissionVerdict.accept("ok");
        }
        @Override protected void spawnChildren(InternalEventResolver r, Ctx ctx) { r.spawnChild("View", new ViewCtx(ctx.history)); }
        @Override protected void onActive(Ctx ctx) { ctx.notes.add("active"); }
        @Override protected void onTeardown(Ctx ctx) { ctx.notes.add("teardown"); }
        @Override protected boolean settlesAsync() { return false; }
        @Override protected void onEnded(Ctx ctx, String outcome) { ctx.notes.add("ended " + outcome + " " + ctx.endCause); }
        @Override protected Object buildSdr(Ctx ctx, String outcome) { return List.copyOf(ctx.notes); }
        @Override protected SdrSink sdrSink() { return sink; }
        @Override protected void defineDomainRoutes(InternalEventResolver r) { r.forwardTo("View", Shown.class); }
    }

    /** The leak: a per-request field on the pooled type. The registry must refuse it. */
    public static class LeakySup extends Sup {
        String lastSession;
        public LeakySup(SdrSink sink) { super(sink, new RoutedSessionTimings(3, 5, 20, 60, 5)); }
    }

    private final List<StatemachineRegistry<Ctx>> open = new ArrayList<>();
    private final List<SdrRecord> sdrs = new CopyOnWriteArrayList<>();

    private StatemachineRegistry<Ctx> build(int pool, RoutedSessionTimings t, long globalTimeoutSec) {
        StatemachineRegistry.Builder<Ctx> b = StatemachineRegistry.<Ctx>builder("routed-test")
            .supervisor("Sup", () -> new Sup(sdrs::add, t), pool)
            .child("View", View::new, pool)
            .threads(2)
            .maxConcurrent(pool);
        if (globalTimeoutSec > 0) b.globalTimeout(globalTimeoutSec, TimeUnit.SECONDS, RoutedSessionSupervisor.FAILED);
        StatemachineRegistry<Ctx> r = b.build();
        open.add(r);
        return r;
    }

    @AfterEach void tearDown() { for (var r : open) r.shutdown(); }

    private SdrRecord awaitSdr(int n, long timeoutMs) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < until) {
            if (sdrs.size() >= n) return sdrs.get(n - 1);
            Thread.sleep(20);
        }
        throw new AssertionError("no SDR #" + n + " within " + timeoutMs + " ms (have " + sdrs.size() + ")");
    }

    private static List<String> hops(SdrRecord sdr) {
        List<String> out = new ArrayList<>();
        for (TransitionRecord t : sdr.history()) if (!t.isNote() && "supervisor".equals(t.machine())) out.add(t.fromState() + ">" + t.toState());
        return out;
    }

    @Test
    void the_graph_validates_with_preprocessing_in_front_and_a_full_session_walks_every_state() throws Exception {
        var reg = build(4, new RoutedSessionTimings(3, 5, 20, 60, 5), 0);
        Ctx ctx = new Ctx();
        ctx.sessionKey = "s-1";
        assertThat(reg.dispatch("s-1", ctx).accepted()).isTrue();
        assertThat(reg.awaitSettled("s-1", 5, TimeUnit.SECONDS)).isTrue();
        assertThat(reg.supervisorStateOf("s-1")).isEqualTo("ADMITTED");
        reg.onInboundEvent("s-1", new Shown());
        assertThat(reg.awaitSettled("s-1", 5, TimeUnit.SECONDS)).isTrue();
        assertThat(reg.supervisorStateOf("s-1")).isEqualTo("ACTIVE");
        reg.onInboundEvent("s-1", new ServiceEnd("viewed"));
        SdrRecord sdr = awaitSdr(1, 5_000);
        assertThat(sdr.outcome()).isEqualTo("SUCCEEDED");
        assertThat(sdr.endCause()).isEqualTo("viewed");
        assertThat(hops(sdr)).containsExactly("IDLE>PREPROCESSING", "PREPROCESSING>ADMITTING", "ADMITTING>ADMITTED", "ADMITTED>ACTIVE", "ACTIVE>TEARING_DOWN", "TEARING_DOWN>SUCCEEDED");
        assertThat(ctx.notes).containsExactly("preprocessed s-1", "admitted s-1", "active", "teardown", "ended SUCCEEDED viewed");
    }

    @Test
    void a_pooled_subclass_with_a_mutable_field_is_refused_at_build() {
        assertThatThrownBy(() -> StatemachineRegistry.<Ctx>builder("leaky")
                .supervisor("Sup", () -> new LeakySup(sdrs::add), 2)
                .child("View", View::new, 2)
                .threads(1)
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("lastSession");
    }

    @Test
    void a_machine_back_from_the_pool_starts_in_preprocessing_with_a_fresh_context() throws Exception {
        var reg = build(1, new RoutedSessionTimings(3, 5, 20, 60, 5), 0);       // ONE machine: the second request must reuse it
        Ctx first = new Ctx();
        first.sessionKey = "s-1";
        first.notes.add("stale note of s-1");
        assertThat(reg.dispatch("s-1", first).accepted()).isTrue();
        assertThat(reg.awaitSettled("s-1", 5, TimeUnit.SECONDS)).isTrue();
        reg.onInboundEvent("s-1", new ServiceEnd("abandoned"));            // pre-active abort → FAILED
        SdrRecord one = awaitSdr(1, 5_000);
        assertThat(one.outcome()).isEqualTo("FAILED");
        assertThat(reg.awaitIdle(5, TimeUnit.SECONDS)).isTrue();

        Ctx second = new Ctx();
        second.sessionKey = "s-2";
        assertThat(reg.dispatch("s-2", second).accepted()).as("the one machine was returned to the pool").isTrue();
        assertThat(reg.awaitSettled("s-2", 5, TimeUnit.SECONDS)).isTrue();
        assertThat(second.sawInPreprocess).as("preprocessing ran on the SECOND request's context, with none of the first's notes").isEqualTo("s-2/0");
        assertThat(reg.supervisorContextOf("s-2")).isSameAs(second);
        reg.onInboundEvent("s-2", new ServiceEnd("abandoned"));
        SdrRecord two = awaitSdr(2, 5_000);
        assertThat(two.sessionKey()).isEqualTo("s-2");
        assertThat(hops(two).get(0)).as("the pooled machine's first hop is IDLE → PREPROCESSING again").isEqualTo("IDLE>PREPROCESSING");
        assertThat(two.history()).noneMatch(t -> t.cause() != null && t.cause().contains("s-1"));
        assertThat(first.notes).doesNotContain("preprocessed s-2");
    }

    @Test
    void a_preprocessing_refusal_ends_failed_with_its_cause_and_one_sdr() throws Exception {
        var reg = build(2, new RoutedSessionTimings(3, 5, 20, 60, 5), 0);
        Ctx ctx = new Ctx();
        ctx.sessionKey = "s-r";
        ctx.refuseWith = AdCause.NO_RULE.name();
        assertThat(reg.dispatch("s-r", ctx).accepted()).isTrue();
        SdrRecord sdr = awaitSdr(1, 5_000);
        assertThat(sdr.outcome()).isEqualTo("FAILED");
        assertThat(sdr.endCause()).isEqualTo("NO_RULE");
        assertThat(hops(sdr)).containsExactly("IDLE>PREPROCESSING", "PREPROCESSING>FAILED");
        assertThat(ctx.notes).contains("ended FAILED NO_RULE");
        Thread.sleep(200);
        assertThat(sdrs).hasSize(1);
    }

    @Test
    void an_admission_reject_ends_failed_with_the_money_cause() throws Exception {
        var reg = build(2, new RoutedSessionTimings(3, 5, 20, 60, 5), 0);
        Ctx ctx = new Ctx();
        ctx.sessionKey = "s-a";
        ctx.rejectAdmission = true;
        reg.dispatch("s-a", ctx);
        SdrRecord sdr = awaitSdr(1, 5_000);
        assertThat(sdr.endCause()).isEqualTo("NO_FUNDED_CAMPAIGN");
        assertThat(hops(sdr)).containsExactly("IDLE>PREPROCESSING", "PREPROCESSING>ADMITTING", "ADMITTING>FAILED");
    }

    @Test
    void a_preprocessing_that_never_answers_times_out_into_failed_preprocess_timeout() throws Exception {
        var reg = build(2, new RoutedSessionTimings(1, 5, 20, 60, 5), 0);
        Ctx ctx = new Ctx();
        ctx.sessionKey = "s-p";
        ctx.pendingForever = true;
        reg.dispatch("s-p", ctx);
        SdrRecord sdr = awaitSdr(1, 5_000);
        assertThat(sdr.outcome()).isEqualTo("FAILED");
        assertThat(sdr.endCause()).isEqualTo("PREPROCESS_TIMEOUT");
        assertThat(hops(sdr)).containsExactly("IDLE>PREPROCESSING", "PREPROCESSING>FAILED");
    }

    @Test
    void a_late_preprocessed_event_re_enters_by_id_and_the_session_goes_on() throws Exception {
        var reg = build(2, new RoutedSessionTimings(5, 5, 20, 60, 5), 0);
        Ctx ctx = new Ctx();
        ctx.sessionKey = "s-async";
        ctx.pendingForever = true;
        reg.dispatch("s-async", ctx);
        assertThat(reg.awaitSettled("s-async", 5, TimeUnit.SECONDS)).isTrue();
        assertThat(reg.supervisorStateOf("s-async")).isEqualTo("PREPROCESSING");
        reg.onInboundEvent("s-async", new Preprocessed(true, null));
        assertThat(reg.awaitSettled("s-async", 5, TimeUnit.SECONDS)).isTrue();
        assertThat(reg.supervisorStateOf("s-async")).isEqualTo("ADMITTED");
    }

    @Test
    void the_admitted_window_times_out_into_not_shown() throws Exception {
        var reg = build(2, new RoutedSessionTimings(3, 5, 1, 60, 5), 0);
        Ctx ctx = new Ctx();
        ctx.sessionKey = "s-n";
        reg.dispatch("s-n", ctx);
        SdrRecord sdr = awaitSdr(1, 5_000);
        assertThat(sdr.endCause()).isEqualTo("NOT_SHOWN");
        assertThat(ctx.notes).contains("ended FAILED NOT_SHOWN");
    }

    @Test
    void a_hung_machine_is_forced_into_failed_by_the_global_timeout_with_cause_hung_machine_and_a_record() throws Exception {
        var reg = build(2, new RoutedSessionTimings(3, 5, 600, 600, 5), 1);     // no state would time out for 10 min; the registry kills it after 1 s
        Ctx ctx = new Ctx();
        ctx.sessionKey = "s-h";
        reg.dispatch("s-h", ctx);
        assertThat(reg.awaitSettled("s-h", 5, TimeUnit.SECONDS)).isTrue();
        assertThat(reg.supervisorStateOf("s-h")).isEqualTo("ADMITTED");
        SdrRecord sdr = awaitSdr(1, 10_000);
        assertThat(sdr.outcome()).isEqualTo("FAILED");
        assertThat(sdr.endCause()).isEqualTo("HUNG_MACHINE");
        assertThat(hops(sdr)).endsWith("ADMITTED>FAILED");
        assertThat(ctx.notes).as("the terminal work (the CDR) ran on the forced exit").contains("ended FAILED HUNG_MACHINE");
    }

    @Test
    void the_causes_have_a_wire_form_and_a_way_back() {
        assertThat(AdCause.NO_FUNDED_CAMPAIGN.wire()).isEqualTo("no-funded-campaign");
        assertThat(AdCause.of("no-funded-campaign")).isEqualTo(AdCause.NO_FUNDED_CAMPAIGN);
        assertThat(AdCause.of("garbage")).isEqualTo(AdCause.INTERNAL_ERROR);
        assertThat(AdCause.wireOf("abandoned:page-left")).isEqualTo("abandoned:page-left");
        assertThat(AdCause.isSystemFault("BILLING_SYSTEM_ERROR")).isTrue();
        assertThat(AdCause.isSystemFault("INSUFFICIENT_BALANCE")).isFalse();
    }
}
