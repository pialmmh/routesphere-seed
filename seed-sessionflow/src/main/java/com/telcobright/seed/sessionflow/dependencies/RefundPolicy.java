package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.seed.sessionflow.api.SettleOutcome;

import java.util.Locale;

/**
 * The refund rule of a lane priced by its reserve: what a session that did not consume pays at settle time — its whole reserve
 * ({@link Rule#CHARGE}) or nothing ({@link Rule#REFUND}). A session that consumed always pays what it reserved. A lane hands it
 * to the base at settle time through {@code SessionFlowSteps.refundPolicy}, from its own config.
 *
 * @param onFailure what an admitted session that FAILED pays
 * @param unshown   what a session OFFERED and never consumed pays (the ad: handed to the phone, never shown —
 *                  {@code adsphere.billing.unshown})
 */
public record RefundPolicy(Rule onFailure, Rule unshown) {

    /** One rule of the policy, as a lane's config says it: {@code charge} or {@code refund}. */
    public enum Rule {
        CHARGE, REFUND;

        /**
         * The config word of {@code key}: {@code charge} or {@code refund}, in any case, spaces around ignored. Any other word,
         * a blank or none refuses the start in the ad lane's own sentence, word for word, naming the lane's key.
         */
        public static Rule parse(String key, String word) {
            String w = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
            return switch (w) {
                case "charge" -> CHARGE;
                case "refund" -> REFUND;
                default -> throw new IllegalStateException(key + " must be 'charge' or 'refund', not '" + word + "'");
            };
        }
    }

    private static final RefundPolicy CALLS_RULE = new RefundPolicy(Rule.REFUND, Rule.CHARGE);

    public RefundPolicy {
        if (onFailure == null) throw new IllegalArgumentException("a refund policy needs its rule for a failed session (onFailure): none was given");
        if (unshown == null) throw new IllegalArgumentException("a refund policy needs its rule for a session offered and never consumed (unshown): none was given");
    }

    /** The call's rule: a session that failed pays nothing; one offered and never consumed keeps its charge. */
    public static RefundPolicy callsRule() { return CALLS_RULE; }

    public static RefundPolicy of(Rule onFailure, Rule unshown) { return new RefundPolicy(onFailure, unshown); }

    /** Does a session that came to {@code outcome} pay what it reserved? */
    public boolean pays(SettleOutcome outcome) {
        return switch (outcome) {
            case CONSUMED -> true;
            case OFFERED_NOT_CONSUMED -> unshown == Rule.CHARGE;
            case FAILED -> onFailure == Rule.CHARGE;
        };
    }
}
