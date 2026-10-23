package com.telcobright.seed.sessionflow.testkit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.seed.sessionflow.spi.BootstrapSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A test's bootstrap: the envelope as a map (or a JSON resource, the §C sample), swappable between asks (a root added live), every ask
 * counted. No network: its origin is a name only.
 */
public final class MapBootstrapSource implements BootstrapSource {

    private static final ObjectMapper JSON = new ObjectMapper();

    private volatile Map<String, Object> envelope;
    private final AtomicInteger asks = new AtomicInteger();

    private MapBootstrapSource(Map<String, Object> envelope) { this.envelope = envelope; }

    public static MapBootstrapSource of(Map<String, Object> envelope) { return new MapBootstrapSource(envelope); }

    /** The envelope of a JSON resource on the test's classpath ({@code /bootstrap/deployment.json}). */
    public static MapBootstrapSource ofResource(String path) { return new MapBootstrapSource(read(path)); }

    /** prime-context serves another envelope from now on. */
    public MapBootstrapSource serve(Map<String, Object> next) { this.envelope = next; return this; }

    /** How many times the envelope was asked. */
    public int asks() { return asks.get(); }

    @Override public Map<String, Object> envelope() { asks.incrementAndGet(); return envelope; }

    @Override public String origin() { return "http://prime-context.test:7300"; }

    /** A JSON resource as a mutable-free map. */
    public static Map<String, Object> read(String path) {
        try (InputStream in = MapBootstrapSource.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("no resource " + path + " on the classpath");
            return JSON.readValue(in, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new IllegalArgumentException("the resource " + path + " is not JSON: " + e.getMessage(), e);
        }
    }
}
