package com.telcobright.seed.routing.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.seed.routing.policy.PolicyFormatException;
import com.telcobright.seed.routing.policy.PolicyTypes;
import com.telcobright.seed.routing.policy.RoutingPolicy;
import com.telcobright.seed.routing.spi.PolicyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Puts the policies a CONFIG FILE declares into the store at start — the bridge for the time before there is a
 * screen: a policy is written in the profile, the store (and so the record, the history, the admin API) holds
 * it as the entity it is.
 *
 * <pre>
 *   sync       the files are the truth: a policy whose file differs from the store is saved as a new version
 *   if-absent  the store is the truth: a file only creates a policy that is not there (a screen edits it after)
 *   off        the files are not read
 * </pre>
 *
 * A declared policy that does not compile FAILS THE START with the path of the mistake — a wrong file must never
 * reach the store, and must never be found by the first customer.
 */
public final class PolicySeeder {
    private static final Logger log = LoggerFactory.getLogger(PolicySeeder.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String BY = "config";

    public enum Mode {
        SYNC, IF_ABSENT, OFF;

        public static Mode of(String word) {
            String w = word == null ? "" : word.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            return switch (w) {
                case "", "sync" -> SYNC;
                case "if-absent" -> IF_ABSENT;
                case "off", "none", "false" -> OFF;
                default -> throw new IllegalArgumentException("routing.seed.mode: '" + word + "' — use sync | if-absent | off");
            };
        }
    }

    /** What the seeding did, one line per declared policy (the host journals them). */
    public record Outcome(String key, String what, int version) {}

    private PolicySeeder() {}

    public static List<Outcome> seed(PolicyStore store, List<RoutingPolicy> declared, Mode mode, PolicyTypes types) {
        List<Outcome> out = new ArrayList<>();
        if (mode == Mode.OFF) return out;
        for (RoutingPolicy draft : declared) {
            try {
                types.compile(draft);                        // a wrong file never reaches the store
            } catch (PolicyFormatException e) {
                throw new PolicyFormatException("routing.policies." + draft.name() + ": " + e.getMessage());
            }
            Optional<RoutingPolicy> stored = store.find(draft.domain(), draft.name());
            if (stored.isEmpty()) {
                RoutingPolicy saved = store.save(draft, BY);
                out.add(new Outcome(saved.key(), "created", saved.version()));
            } else if (mode == Mode.SYNC && differs(stored.get(), draft)) {
                RoutingPolicy s = stored.get();
                RoutingPolicy saved = store.save(new RoutingPolicy(s.domain(), s.name(), draft.type(), draft.enabled(), s.version(),
                    draft.description(), draft.document(), s.updatedAt(), s.updatedBy()), BY);
                out.add(new Outcome(saved.key(), "updated", saved.version()));
            } else {
                out.add(new Outcome(stored.get().key(), mode == Mode.SYNC ? "same" : "kept", stored.get().version()));
            }
        }
        for (Outcome o : out) log.info("routing policy seed: {} {} (version {})", o.key(), o.what(), o.version());
        return out;
    }

    private static boolean differs(RoutingPolicy stored, RoutingPolicy draft) {
        if (!stored.type().equals(draft.type()) || stored.enabled() != draft.enabled()) return true;
        if (!Objects.equals(blankToNull(stored.description()), blankToNull(draft.description()))) return true;
        try {
            return !JSON.readTree(stored.document()).equals(JSON.readTree(draft.document()));   // jsonb re-orders keys: compare the trees
        } catch (JsonProcessingException e) {
            return true;                                      // the stored document is not JSON: the file replaces it
        }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
