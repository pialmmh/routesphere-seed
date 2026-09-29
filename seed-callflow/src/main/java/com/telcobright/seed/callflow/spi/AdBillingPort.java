package com.telcobright.seed.callflow.spi;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.callflow.api.LevelCharge;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The money of one tier (contract item 3): implemented on orchestrix portal-api's prepaid roads — road 16 DEBIT and road 15
 * CREDIT, profile {@code ad-credit} — reference {@code <sessionId>#L<levelIndex>}. No wallet in a sphere, one debit per usage.
 *
 * <ul>
 *   <li>{@link #debit}: empty = this partner cannot pay it ({@code 402}: the next payer is tried); a present charge = the
 *       ledger's own answer (idempotent by reference: a replay answers the first result, {@code repeated = true}).</li>
 *   <li>{@link BillingSystemFault} (thrown, never a return): the ledger itself is unreachable or broken — a timeout that the
 *       one same-reference retry did not cure, 401/403/5xx, no connection. The caller reports {@code BILLING_SYSTEM_ERROR},
 *       never a customer cause, and stops trying payers.</li>
 *   <li>{@link LedgerRefusal} (thrown): the ledger REFUSED the debit for a reason of its own that is not a balance —
 *       {@code 409 PARTNER_INACTIVE}, {@code 404} (no such partner), {@code 400} — the body's code is the cause.</li>
 *   <li>{@link #credit}: the new balance, or empty when the account is unknown / the credit was refused.</li>
 * </ul>
 */
public interface AdBillingPort {

    Optional<LevelCharge> debit(LevelAdmission level, BigDecimal amount, String reference) throws BillingSystemFault;

    Optional<BigDecimal> credit(LevelCharge charge, String reference, String reason) throws BillingSystemFault;

    /** The ledger refused with a code of its own (409 / 404 / 400): that code is the candidate's refusal cause. */
    final class LedgerRefusal extends RuntimeException {
        private final String code;
        public LedgerRefusal(String code, String message) { super(code + ": " + message); this.code = code; }
        public String code() { return code; }
    }

    /** The ledger did not answer, or answered that IT is broken: {@code BILLING_SYSTEM_ERROR}, never a money cause. */
    final class BillingSystemFault extends RuntimeException {
        public BillingSystemFault(String message) { super(message); }
        public BillingSystemFault(String message, Throwable cause) { super(message, cause); }
    }
}
