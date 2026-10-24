package com.telcobright.seed.sessionflow.api;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * ONE served root's resources as its bootstrap names them (§C.1 {@code config.routesphere.root.<root>}): its switch database, its Kafka
 * (the CDR road {@code cdr_<root>}), its Redis, its ledger's kind and its own directory on this box. Each root has its own — link3 and
 * btcl never share a road by accident.
 *
 * @param root     the root's name (its database: {@code link3})
 * @param switchDb the switch database the root's ledger lives in; null when the bootstrap names none
 * @param kafka    the root's Kafka
 * @param redisUrl the root's Redis ({@code redis://host:port}); null when none (keys are {@code <root>:<app>:<name>}, R9)
 * @param ledger   the ledger's kind ({@code switch}, the default — R12; {@code orchestrix}, the transition) and its own words
 * @param dir      the root's own directory: the sessions in the air ({@code sessions-in-the-air.jsonl}), the switch ledger's WAL and queue
 * @param ledgerDb the switch ledger's OWN login ({@code ledger.db}, P1-J): its user and password by reference, its url and schema
 *                 switch-db's when it says none; null = the ledger connects with switch-db's login, as before
 */
public record RootResources(String root, SwitchDb switchDb, KafkaRoad kafka, String redisUrl, Ledger ledger, Path dir, SwitchDb ledgerDb) {

    public RootResources {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(kafka, "the root's kafka");
        Objects.requireNonNull(ledger, "the root's ledger");
        Objects.requireNonNull(dir, "the root's directory");
    }

    /** The six words of P1-E, kept (P1-J): no login of the ledger's own — the switch ledger connects with switch-db's. */
    public RootResources(String root, SwitchDb switchDb, KafkaRoad kafka, String redisUrl, Ledger ledger, Path dir) {
        this(root, switchDb, kafka, redisUrl, ledger, dir, null);
    }

    /**
     * P1-J · The login the switch ledger connects with: its own ({@code ledger.db}; the url and schema switch-db's where it says none),
     * else switch-db's. prime-context grants the app's role and the ledger's role separately (separation of duties at the database).
     */
    public SwitchDb ledgerLogin() { return ledgerDb == null ? switchDb : ledgerDb.over(switchDb); }

    /** P1-M · The root's resources as a log may print them: a password inside its redis url masked (its logins and ledger print their own). */
    @Override
    public String toString() {
        return "RootResources[root=" + root + ", switchDb=" + switchDb + ", kafka=" + kafka + ", redisUrl=" + LogText.maskPassword(redisUrl) + ", ledger=" + ledger
            + ", dir=" + dir + ", ledgerDb=" + ledgerDb + "]";
    }

    /** P1-L · How a {@code password-ref} that is not {@code env:<NAME>} is printed: it may be a password written by mistake. */
    public static final String HIDDEN_REF = "<not an env: reference — hidden>";
    private static final Set<String> PASSWORD_REF_KEYS = Set.of("password-ref", "passwordRef");

    /** P1-L · A {@code password-ref} as a log may print it: {@code env:<NAME>} (or none) as it is, anything else {@value #HIDDEN_REF}. */
    static String shownRef(Object ref) {
        if (ref == null) return "null";
        String text = String.valueOf(ref);
        return text.isBlank() || text.startsWith("env:") ? text : HIDDEN_REF;
    }

    /** P1-L · Words as a log may print them: every {@code password-ref} in them, nested maps and lists too, as {@link #shownRef} says it. */
    static Object shownWords(Object value, String key) {
        if (key != null && PASSWORD_REF_KEYS.contains(key)) return shownRef(value);
        if (value instanceof String text) return LogText.maskPassword(text);                // P1-M: a url among the words (an orchestrix ledger's)
        if (value instanceof Map<?, ?> words) {
            Map<Object, Object> shown = new LinkedHashMap<>();
            words.forEach((k, v) -> shown.put(k, shownWords(v, String.valueOf(k))));
            return shown;
        }
        if (value instanceof List<?> items) return items.stream().map(v -> shownWords(v, null)).toList();
        return value;
    }

    /**
     * The switch database: a JDBC url, the root's schema, the user, and the password BY REFERENCE ({@code env:<NAME>}) — never a value in
     * the config.
     */
    public record SwitchDb(String url, String schema, String username, String passwordRef) {

        /** P1-J · This login in another's place: the url and the schema of {@code base} where this one says none. */
        public SwitchDb over(SwitchDb base) {
            return new SwitchDb(url != null ? url : base == null ? null : base.url(), schema != null ? schema : base == null ? null : base.schema(),
                username, passwordRef);
        }

        /** P1-L · The login as a log may print it: a {@code password-ref} that is not {@code env:<NAME>} is {@value RootResources#HIDDEN_REF}. */
        @Override
        public String toString() {
            return "SwitchDb[url=" + LogText.maskPassword(url) + ", schema=" + schema + ", username=" + username + ", passwordRef=" + shownRef(passwordRef) + "]";
        }

        /**
         * The password by reference ({@code env:<NAME>}), from the PROCESS environment only — switch-db's and the ledger's own login alike
         * (P1-J; P1-K, the owner's ruling of 2026-10-24 "Environment only": secreteer fills the box's environment file, an application reads
         * only its environment). The bootstrap envelope's {@code secrets} are never read. Refused in words naming the VARIABLE — never a
         * value — when it is not there; a password-ref that is not {@code env:<NAME>} is refused without its text (it may be a password).
         * Null when the login names no password.
         */
        public String passwordFromTheEnvironment(Function<String, String> environment) {
            if (passwordRef == null || passwordRef.isBlank()) return null;
            if (!passwordRef.startsWith("env:")) throw new IllegalStateException("the password-ref of the login '" + username + "' is not env:<NAME> (its text is not repeated: it may be a password) — a password is never written in the config");
            String name = passwordRef.substring(4);
            String value = environment.apply(name);
            if (value == null || value.isBlank()) throw new IllegalStateException("the password " + name + " of the login '" + username + "' is not in the process environment — a password is read from the environment only (the box's environment file), never from the bootstrap");
            return value;
        }
    }

    /** The root's Kafka: its bootstrap address and how its topics are made (partitions, replication). */
    public record KafkaRoad(String bootstrap, int partitions, short replication) {}

    /** The root's ledger: its kind and the rest of its words (an orchestrix ledger's url, token variable, tenant, profile). */
    public record Ledger(String kind, Map<String, Object> words) {
        public static final String SWITCH = "switch", ORCHESTRIX = "orchestrix";

        public Ledger { words = words == null ? Map.of() : Map.copyOf(words); }

        /** One of the ledger's own words, or null. */
        public String word(String key) { Object v = words.get(key); return v == null ? null : String.valueOf(v); }

        /** P1-L · The ledger as a log may print it: a {@code password-ref} in its words (its {@code db} block) that is not {@code env:<NAME>} hidden. */
        @Override
        public String toString() { return "Ledger[kind=" + kind + ", words=" + shownWords(words, null) + "]"; }
    }
}
