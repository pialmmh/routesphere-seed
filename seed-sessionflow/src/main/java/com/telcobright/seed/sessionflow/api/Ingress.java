package com.telcobright.seed.sessionflow.api;

/**
 * Where a session came in — the ingress identity the signaling machine stamps on its facts, and the one thing the base's step (a)
 * {@code identifyTenant} reads (through its helpers) to find the ROOT tenant (operator model §5: identification is a rule (kind, match) → tenant,
 * served by prime-context in the bootstrap; no match = refused, never a default):
 *
 * <ul>
 *   <li>{@value #LISTEN} {@code <ip>:<port>} — the LOCAL address an HTTP request arrived on (the ad, the SMS's HTTP door);</li>
 *   <li>{@value #ESL} {@code <host>:<port>} — the FreeSWITCH connection a CHANNEL_PARK came from (the voice call);</li>
 *   <li>{@value #VLAN} {@code <gateway>:<svlan>} — the gateway and S-VLAN of a WiFi device event;</li>
 *   <li>{@value #TOPIC} {@code <name>} — the Kafka topic a message was consumed from (the SMS's Kafka door).</li>
 * </ul>
 *
 * @param kind  one of the kinds above (a product may add its own word)
 * @param match the fact, as the rule writes it
 */
public record Ingress(String kind, String match) {

    public static final String LISTEN = "listen";
    public static final String ESL = "esl";
    public static final String VLAN = "vlan";
    public static final String TOPIC = "topic";

    public Ingress {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("an ingress needs its kind (listen, esl, vlan, topic)");
        if (match == null || match.isBlank()) throw new IllegalArgumentException("an ingress of kind " + kind + " needs its match");
    }

    /** The local address an HTTP request arrived on. */
    public static Ingress listen(String ip, int port) { return new Ingress(LISTEN, ip + ":" + port); }

    /** The FreeSWITCH connection a CHANNEL_PARK came from. */
    public static Ingress esl(String host, int port) { return new Ingress(ESL, host + ":" + port); }

    /** The gateway and S-VLAN of a WiFi device event. */
    public static Ingress vlan(String gateway, int svlan) { return new Ingress(VLAN, gateway + ":" + svlan); }

    /** The Kafka topic a message was consumed from. */
    public static Ingress topic(String name) { return new Ingress(TOPIC, name); }

    @Override
    public String toString() { return kind + " " + match; }
}
