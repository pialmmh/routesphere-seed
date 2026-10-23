package com.telcobright.seed.bootstrapconfig.internal;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Map;
import java.util.Set;

/**
 * The bootstrap envelope's {@code config} as ONE config source, flattened ({@link FlatConfig}), at {@link #ORDINAL}: above the jar's
 * {@code application.properties} / yml (250), below the environment (300) and the system properties (400) — a documented
 * {@code -D} or environment override still wins. The envelope's {@code secrets} are never a value here: a {@code password-ref} stays a
 * reference, resolved where it always was.
 */
public final class EnvelopeConfigSource implements ConfigSource {

    /** Between the jar's own files (250) and the environment (300). */
    public static final int ORDINAL = 275;

    private final String name;
    private final Map<String, String> properties;

    public EnvelopeConfigSource(String where, Map<String, ?> config) {
        this.name = "prime-context bootstrap " + where;
        this.properties = Map.copyOf(FlatConfig.of(config));
    }

    @Override public Map<String, String> getProperties() { return properties; }

    @Override public Set<String> getPropertyNames() { return properties.keySet(); }

    @Override public String getValue(String propertyName) { return properties.get(propertyName); }

    @Override public String getName() { return name; }

    @Override public int getOrdinal() { return ORDINAL; }
}
