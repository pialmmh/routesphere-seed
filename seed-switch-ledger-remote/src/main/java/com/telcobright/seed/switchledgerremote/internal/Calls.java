package com.telcobright.seed.switchledgerremote.internal;

import com.telcobright.seed.switchledgerremote.dependencies.RemoteLedgerSettings;
import com.telcobright.seed.switchledgerremote.wire.SwitchLedgerGrpc;
import com.telcobright.seed.switchledgerremote.wire.SwitchLedgerGrpc.SwitchLedgerBlockingStub;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * One unary call of the client: a blocking stub bounded by a deadline — {@code deadline-ms}, or {@code min(deadline-ms, withinMs)} where
 * the caller says how long it can wait — and the status turned into the port's exception: a {@code LedgerFault} on the port's verbs, the
 * door's own {@code IllegalArgumentException} for NOT_FOUND / INVALID_ARGUMENT on the credit and cap doors.
 */
public final class Calls {

    private final SwitchLedgerBlockingStub blocking;
    private final SwitchLedgerGrpc.SwitchLedgerStub async;
    private final RemoteLedgerSettings settings;

    public Calls(Channel channel, RemoteLedgerSettings settings) {
        this.blocking = SwitchLedgerGrpc.newBlockingStub(channel);
        this.async = SwitchLedgerGrpc.newStub(channel);
        this.settings = settings;
    }

    public RemoteLedgerSettings settings() { return settings; }

    public SwitchLedgerGrpc.SwitchLedgerStub async() { return async; }

    /** The deadline of a call whose caller can wait {@code withinMs}: never longer than {@code deadline-ms}. */
    public long within(long withinMs) { return withinMs > 0 ? Math.min(settings.deadlineMs(), withinMs) : settings.deadlineMs(); }

    /** A port verb: any status but OK is a {@code LedgerFault}. */
    public <T> T call(String what, Function<SwitchLedgerBlockingStub, T> verb) { return call(what, settings.deadlineMs(), verb); }

    public <T> T call(String what, long waitMs, Function<SwitchLedgerBlockingStub, T> verb) {
        try {
            return verb.apply(blocking.withDeadlineAfter(waitMs, TimeUnit.MILLISECONDS));
        } catch (StatusRuntimeException e) {
            throw Statuses.faultOf(e, what, settings.address(), settings.tokenVar());
        }
    }

    /** A credit or cap door: NOT_FOUND and INVALID_ARGUMENT keep the door's {@code IllegalArgumentException}. */
    public <T> T door(String what, Function<SwitchLedgerBlockingStub, T> verb) {
        try {
            return verb.apply(blocking.withDeadlineAfter(settings.deadlineMs(), TimeUnit.MILLISECONDS));
        } catch (StatusRuntimeException e) {
            throw Statuses.doorFaultOf(e, what, settings.address(), settings.tokenVar());
        }
    }
}
