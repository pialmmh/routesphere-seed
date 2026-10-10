package com.telcobright.seed.sessionflow.api;

/**
 * What a request tells about where it came in — the facts a {@code TenantResolver} matches an identification rule on (ARCH-0077-A item 8):
 *
 * <ul>
 *   <li>{@value #LISTEN}: the LOCAL address the request arrived on, {@code ip:port} (a RADIUS listener, an HTTP listener);</li>
 *   <li>{@value #ESL}: the FreeSWITCH connection it came from, {@code host:port}.</li>
 * </ul>
 *
 * @param kind  one of the kinds above (a product may add its own words)
 * @param match the fact, as the rule writes it
 */
public record RequestFacts(String kind, String match) {

    public static final String LISTEN = "listen";
    public static final String ESL = "esl";

    public RequestFacts {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("a request fact needs its kind");
        if (match == null || match.isBlank()) throw new IllegalArgumentException("a request fact of kind " + kind + " needs its match");
    }

    /** The local address a request arrived on. */
    public static RequestFacts listen(String ip, int port) { return new RequestFacts(LISTEN, ip + ":" + port); }

    /** The FreeSWITCH connection a request came from. */
    public static RequestFacts esl(String host, int port) { return new RequestFacts(ESL, host + ":" + port); }
}
