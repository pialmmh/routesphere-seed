package com.telcobright.seed.sessionflow.spi;

import java.util.Set;

/**
 * The served roots' trees of a kit made from the bootstrap: the base's {@link TenantLookup} (one snapshot per root) that can serve a root
 * added LIVE (R10: prime-context provisioned it, the {@code tenant_created} message named it) and reload one root's tree (a new reseller
 * tier — the doorbell's own road). seed-tenant's prime-context trees are the real ones; a test hands in its own.
 */
public interface LiveTrees extends TenantLookup, AutoCloseable {

    /** The roots served now. */
    Set<String> roots();

    /**
     * Serve one more root: its tree is loaded now (a root that cannot be loaded is refused in words). Serving a served root is nothing.
     * P1-G: a serve that fails leaves NOTHING served — the root is not in {@link #roots()} and has no tree — so the next serve loads it
     * (prime-context announces a root BEFORE it serves its tree: 404 ROOT_NOT_SERVED until then). A lane's own LiveTrees undoes the same.
     * P1-P: a lane that refuses the root for its OWN reasons (a door it cannot open, ad-sphere's {@code ROOT_DOOR_UNBOUND}; a rule the
     * root lacks) throws {@link LaneRefusal} with its words, and the road's pending line says the lane refused it. Anything else a serve
     * throws is read as the tree not served yet (prime-context's answer).
     */
    void serve(String root);

    /**
     * P1-P · A lane's own refusal to serve a root: its doors, its rules, never the tree's fetch. The message is the lane's words, and the
     * road's pending line names the lane with them. Throw it as it is: wrapped in another exception, it reads as the tree's failure.
     */
    final class LaneRefusal extends RuntimeException {
        public LaneRefusal(String words) { super(words); }
        public LaneRefusal(String words, Throwable cause) { super(words, cause); }
    }

    /**
     * P1-H · Take a served root out again — its roads could not all be made: nothing of it stays served (not in {@link #roots()}, no tree),
     * so the next serve loads it. Default: nothing (trees that cannot take a root out); a lane's own trees implement it.
     */
    default void unserve(String root) { }

    /** Load one served root's tree again (a tier was added under it): the next session takes the new snapshot. */
    void reload(String root);

    /**
     * P1-G · The step each NEW tree of a root takes before a session can see it ({@link TreeGate}): the bootstrap road gives its gate once,
     * at its start — it hands the tree's new tiers to the root's ledger first. The trees call it with every new tree they load (a reload, the
     * doorbell's ring, a root served), BEFORE they show it; a gate that throws keeps the last tree. A lane's own LiveTrees calls it the
     * same way before its own swap. Default: nothing (trees that never show a new tier).
     */
    default void gateEachNewTree(TreeGate gate) { }

    @Override default void close() { }
}
