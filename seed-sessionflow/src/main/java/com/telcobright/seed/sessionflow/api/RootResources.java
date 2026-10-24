package com.telcobright.seed.sessionflow.api;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
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

        /**
         * P1-J · The password from the PROCESS environment only ({@code env:<NAME>}) — never the envelope's {@code secrets} (the secrets
         * ruling: secreteer's env file); refused in words when the variable is not there.
         */
        public String passwordFromTheEnvironment(Function<String, String> environment) {
            if (passwordRef == null || passwordRef.isBlank()) return null;
            if (!passwordRef.startsWith("env:")) throw new IllegalStateException("the switch ledger's password-ref '" + passwordRef + "' is not env:<NAME> — a password is never written in the config");
            String name = passwordRef.substring(4);
            String value = environment.apply(name);
            if (value == null || value.isBlank()) throw new IllegalStateException("the switch ledger's password " + name + " is not in the process environment (never read from the bootstrap's secrets)");
            return value;
        }

        /** The password: {@code env:<NAME>} from the process environment, else from the envelope's {@code secrets}; refused in words when neither has it. */
        public String password(Function<String, String> environment, Map<String, String> secrets) {
            if (passwordRef == null || passwordRef.isBlank()) return null;
            if (!passwordRef.startsWith("env:")) throw new IllegalStateException("the switch database's password-ref '" + passwordRef + "' is not env:<NAME> — a password is never written in the config");
            String name = passwordRef.substring(4);
            String value = environment.apply(name);
            if (value == null || value.isBlank()) value = secrets.get(name);
            if (value == null || value.isBlank()) throw new IllegalStateException("the switch database's password " + name + " is in neither the environment nor the bootstrap's secrets");
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
    }
}
