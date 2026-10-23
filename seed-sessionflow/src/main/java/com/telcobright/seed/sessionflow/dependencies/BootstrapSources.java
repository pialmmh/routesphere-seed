package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.seed.sessionflow.internal.HttpBootstrapSource;
import com.telcobright.seed.sessionflow.spi.BootstrapSource;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * The bootstrap sources a host can choose from (R8). The application's yml holds exactly this and nothing else of the platform:
 * <pre>
 *   prime-context:
 *     bootstrap-url: http://10.10.188.2:7300/api/v1/bootstrap/platform/ad-sphere
 *     token-env: AD_SPHERE_BOOTSTRAP_TOKEN          # the NAME of the variable, never its value
 * </pre>
 */
public final class BootstrapSources {

    public static final String URL_KEY = "bootstrap-url", TOKEN_KEY = "token-env", SECTION = "prime-context";
    /** The connect and read timeout of one ask. */
    public static final Duration TIMEOUT = Duration.ofSeconds(5);

    private BootstrapSources() {}

    /** The HTTP source of one bootstrap url; the token is read from {@code environment} by its variable's name. */
    public static BootstrapSource http(String bootstrapUrl, String tokenVar, Function<String, String> environment) {
        return new HttpBootstrapSource(bootstrapUrl, tokenVar, environment, TIMEOUT);
    }

    /** The same, from the application's yml (nested {@code prime-context: {bootstrap-url, token-env}} or the flat dotted keys). */
    public static BootstrapSource fromYml(Map<String, ?> yml, Function<String, String> environment) {
        return http(ymlWord(yml, URL_KEY), ymlWord(yml, TOKEN_KEY), environment);
    }

    private static String ymlWord(Map<String, ?> yml, String key) {
        Object section = yml.get(SECTION);
        Object nested = section instanceof Map<?, ?> m ? m.get(key) : null;
        Object value = nested != null ? nested : yml.get(SECTION + "." + key);
        return value == null ? null : String.valueOf(value);
    }
}
