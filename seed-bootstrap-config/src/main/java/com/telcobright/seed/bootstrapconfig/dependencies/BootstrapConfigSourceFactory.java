package com.telcobright.seed.bootstrapconfig.dependencies;

import com.telcobright.seed.bootstrapconfig.internal.EnvelopeConfigSource;
import com.telcobright.seed.sessionflow.dependencies.BootstrapSources;
import com.telcobright.seed.sessionflow.spi.BootstrapSource;
import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The application's WHOLE config from prime-context's bootstrap (R8: the app's own yml holds ONE thing, the bootstrap url). Registered by
 * {@code ServiceLoader} ({@code META-INF/services/io.smallrye.config.ConfigSourceFactory}): a Quarkus application gets it by adding this
 * module. It reads {@code prime-context.bootstrap-url} and {@code prime-context.token-env} from the lower sources (the jar's yml, the
 * environment, {@code -D}), fetches the envelope through the SAME road as {@code RoutesphereKit.fromBootstrap} ({@code BootstrapSources.http}:
 * the token by its variable's name, {@code If-None-Match}), and serves the envelope's {@code config} flattened at ordinal 275.
 *
 * <ul>
 *   <li>No bootstrap url: no source — the application runs on its own files (a test, a lab); the kit's own door still refuses a missing
 *       url when the application builds its kit from the bootstrap.</li>
 *   <li>A url without its token's variable name, a token variable that is empty, prime-context unreachable or refusing: the start is
 *       REFUSED, exactly as the kit's door refuses it — the same words, the same {@code BootstrapUnavailable}.</li>
 *   <li>Never a secret: only {@code config} is served; the envelope's {@code secrets} stay out, a {@code password-ref} stays a reference.</li>
 * </ul>
 */
public final class BootstrapConfigSourceFactory implements ConfigSourceFactory {

    private static final Logger log = LoggerFactory.getLogger(BootstrapConfigSourceFactory.class);

    public static final String URL = "prime-context.bootstrap-url", TOKEN_ENV = "prime-context.token-env";

    private final BiFunction<String, String, BootstrapSource> sources;

    /** The deployed road: HTTP, the token read from the process environment by its variable's name. */
    public BootstrapConfigSourceFactory() { this((url, tokenVar) -> BootstrapSources.http(url, tokenVar, System::getenv)); }

    /** @param sources the bootstrap source of a url and the token's variable name (a test's map) */
    public BootstrapConfigSourceFactory(BiFunction<String, String, BootstrapSource> sources) { this.sources = sources; }

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        String url = valueOf(context, URL);
        if (url == null) { log.info("no {}: the application's config is its own files only", URL); return List.of(); }
        BootstrapSource source = sources.apply(url, valueOf(context, TOKEN_ENV));
        return List.of(new EnvelopeConfigSource(source.where(), configOf(source.envelope())));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> configOf(Map<String, Object> envelope) {
        Object config = envelope.get("config");
        if (!(config instanceof Map<?, ?>)) throw new BootstrapSource.BootstrapUnavailable("prime-context's envelope carries no config map: nothing to serve");
        return (Map<String, ?>) config;
    }

    private static String valueOf(ConfigSourceContext context, String name) {
        ConfigValue value = context.getValue(name);
        String word = value == null ? null : value.getValue();
        return word == null || word.isBlank() ? null : word.trim();
    }
}
