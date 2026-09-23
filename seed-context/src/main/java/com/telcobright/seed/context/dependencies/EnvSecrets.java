package com.telcobright.seed.context.dependencies;

import com.telcobright.seed.context.spi.SecretResolver;
import com.telcobright.seed.context.spi.SecretUnavailable;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The secreteer contract's reader: a pointer {@code env:<VAR>} names a variable of the process
 * environment, filled from the root-owned env file the unit loads. The ONLY place a product reads
 * {@code System.getenv} for a secret — an architecture test may hold it to that.
 *
 * <p>{@link #of(Map)} stands in for the environment in tests (an in-memory map, never a store).
 */
public final class EnvSecrets implements SecretResolver {
    public static final String SCHEME = "env:";
    private static final Pattern VARIABLE = Pattern.compile("[A-Z][A-Z0-9_]*");

    private final Function<String, String> environment;

    private EnvSecrets(Function<String, String> environment) { this.environment = environment; }

    public static EnvSecrets fromProcess() { return new EnvSecrets(System::getenv); }

    public static EnvSecrets of(Map<String, String> variables) { return new EnvSecrets(variables::get); }

    @Override
    public String require(String pointer) {
        String variable = variableOf(pointer);
        String value = environment.apply(variable);
        if (value == null || value.isBlank()) {
            throw new SecretUnavailable("the environment has no " + variable + " (pointer " + pointer + ")");
        }
        return value;
    }

    @Override
    public Optional<String> find(String pointer) {
        String value = environment.apply(variableOf(pointer));
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /** The variable a pointer names — refuses every other scheme, so a store path can never sneak back in. */
    public static String variableOf(String pointer) {
        if (pointer == null || !pointer.startsWith(SCHEME)) {
            throw new SecretUnavailable("not an env: pointer: " + pointer);
        }
        String variable = pointer.substring(SCHEME.length());
        if (!VARIABLE.matcher(variable).matches()) {
            throw new SecretUnavailable("not a variable name: " + pointer);
        }
        return variable;
    }
}
