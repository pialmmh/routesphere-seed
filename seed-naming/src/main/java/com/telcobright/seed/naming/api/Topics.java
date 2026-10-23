package com.telcobright.seed.naming.api;

import java.util.regex.Pattern;

/**
 * The Kafka topics of a root (R9, decided as recommended 2026-10-23):
 * <ul>
 *   <li>a PLATFORM topic — {@code <name>_<root>}: {@code cdr_btcl}, {@code cdr_summary_ping_btcl}, {@code config_event_loader_btcl} —
 *       unchanged, so billing-core and summary-service need no change;</li>
 *   <li>an APPLICATION's own topic — {@code <app>_<in|out>_<name>_<root>}: {@code ad-sphere_in_requests_link3} — the direction in the
 *       name, so publish and subscribe never share a topic.</li>
 * </ul>
 * Every name is checked as Kafka checks it: its legal characters ({@code a-z A-Z 0-9 _ -}; never {@code .}, which Kafka's metrics take for
 * {@code _}, so {@code a.b} and {@code a_b} would collide), at most {@value #MAX_LENGTH} characters, every part named. A wrong one is
 * refused in words — it would have been a topic nobody publishes on.
 */
public final class Topics {

    /** Kafka's own limit on a topic's name. */
    public static final int MAX_LENGTH = 249;

    /** The platform topic of the tenants created (§C.2): key = the root. Not per root — every application of every root listens. */
    public static final String TENANT_CREATED = "tenant_created";

    private static final Pattern LEGAL = Pattern.compile("[a-zA-Z0-9_-]+");

    private Topics() {}

    /** {@code <name>_<root>} — {@code platform("cdr", "btcl")} = {@code cdr_btcl}. */
    public static String platform(String name, String root) { return checked(part("name", name) + "_" + part("root", root)); }

    /** {@code <app>_<in|out>_<name>_<root>} — {@code app("ad-sphere", IN, "requests", "link3")} = {@code ad-sphere_in_requests_link3}. */
    public static String app(String app, Direction direction, String name, String root) {
        if (direction == null) throw new IllegalArgumentException("an application's topic names its direction (in or out): " + app + " " + name);
        return checked(part("app", app) + "_" + direction.word() + "_" + part("name", name) + "_" + part("root", root));
    }

    /** A topic's name as Kafka takes it; else refused in words. */
    public static String checked(String topic) {
        if (topic == null || topic.isEmpty()) throw new IllegalArgumentException("a topic needs a name");
        if (topic.length() > MAX_LENGTH) throw new IllegalArgumentException("the topic '" + topic + "' is " + topic.length() + " characters: Kafka takes " + MAX_LENGTH + " at most");
        if (topic.indexOf('.') >= 0) throw new IllegalArgumentException("the topic '" + topic + "' has a '.': Kafka's metrics read it as '_', so it would collide with its twin — use '_'");
        if (!LEGAL.matcher(topic).matches()) throw new IllegalArgumentException("the topic '" + topic + "' has a character Kafka refuses: only a-z A-Z 0-9 _ - are legal");
        return topic;
    }

    private static String part(String what, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("a topic's " + what + " is missing");
        return value;
    }
}
