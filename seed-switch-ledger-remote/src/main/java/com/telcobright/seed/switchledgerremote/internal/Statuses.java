package com.telcobright.seed.switchledgerremote.internal;

import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * The exceptions on the wire (design §3.3), both ways. The server: a {@code LedgerFault} is INTERNAL (the ledger is broken), an
 * {@code IllegalArgumentException} is NOT_FOUND for an account the schema does not hold and INVALID_ARGUMENT otherwise (the credit and cap
 * doors' own words), anything else INTERNAL. A {@code LedgerRefusal} never comes here: it travels in the reply with status OK.
 *
 * <p>The client: every status that is not OK is a {@link LedgerFault} on the port's verbs — the pipeline says BILLING_SYSTEM_ERROR, never a
 * balance cause; UNAUTHENTICATED names the token variable. On the credit and cap doors NOT_FOUND and INVALID_ARGUMENT are an
 * {@code IllegalArgumentException} (the WiFi clock catches exactly that), anything else a {@link LedgerFault}.
 */
public final class Statuses {

    private Statuses() {}

    /** The server's status for a failure of the engine. */
    public static StatusRuntimeException of(RuntimeException failure) {
        String words = LogText.failureText(failure.getMessage() == null ? failure.toString() : failure.getMessage());
        if (failure instanceof LedgerFault) return Status.INTERNAL.withDescription(words).asRuntimeException();
        if (failure instanceof IllegalArgumentException) {
            Status status = words.contains("not held") ? Status.NOT_FOUND : Status.INVALID_ARGUMENT;
            return status.withDescription(words).asRuntimeException();
        }
        return Status.INTERNAL.withDescription("the ledger failed: " + words).asRuntimeException();
    }

    /** The client's fault for a port verb that did not get an OK answer. */
    public static LedgerFault faultOf(StatusRuntimeException failure, String what, String address, String tokenVar) {
        Status status = failure.getStatus();
        String said = status.getDescription() == null ? "" : ": " + status.getDescription();
        String words = switch (status.getCode()) {
            case UNAVAILABLE -> "the switch-ledger at " + address + " is unreachable (" + what + ")" + said;
            case DEADLINE_EXCEEDED -> "the switch-ledger at " + address + " did not answer " + what + " in time" + said;
            case UNAUTHENTICATED, PERMISSION_DENIED -> "the switch-ledger at " + address + " refused the bearer" + (tokenVar == null ? " (no token-var is set)" : " in " + tokenVar) + " (" + what + ")" + said;
            default -> "the switch-ledger at " + address + " answered " + status.getCode() + " to " + what + said;
        };
        return new LedgerFault(words, failure);
    }

    /** The client's exception on the credit and cap doors: NOT_FOUND and INVALID_ARGUMENT keep the door's own IllegalArgumentException. */
    public static RuntimeException doorFaultOf(StatusRuntimeException failure, String what, String address, String tokenVar) {
        Status.Code code = failure.getStatus().getCode();
        if (code == Status.Code.NOT_FOUND || code == Status.Code.INVALID_ARGUMENT) {
            return new IllegalArgumentException(failure.getStatus().getDescription() == null ? what + " refused: " + code : failure.getStatus().getDescription());
        }
        return faultOf(failure, what, address, tokenVar);
    }
}
