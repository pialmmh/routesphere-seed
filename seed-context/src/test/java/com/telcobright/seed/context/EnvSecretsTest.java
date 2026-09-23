package com.telcobright.seed.context;

import com.telcobright.seed.context.dependencies.EnvSecrets;
import com.telcobright.seed.context.spi.SecretUnavailable;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The secreteer contract: a pointer names a variable, a missing one names the VARIABLE, no other scheme ever resolves. */
class EnvSecretsTest {

    private final EnvSecrets env = EnvSecrets.of(Map.of("TENANT_BTCL_ODOO_PASSWORD", "s3cr3t", "BLANK", "  "));

    @Test
    void a_pointer_resolves_to_the_variable_it_names() {
        assertEquals("s3cr3t", env.require("env:TENANT_BTCL_ODOO_PASSWORD"));
        assertEquals("s3cr3t", env.find("env:TENANT_BTCL_ODOO_PASSWORD").orElseThrow());
        assertEquals("TENANT_BTCL_ODOO_PASSWORD", EnvSecrets.variableOf("env:TENANT_BTCL_ODOO_PASSWORD"));
    }

    @Test
    void a_missing_or_blank_variable_fails_naming_the_variable_and_never_a_value() {
        SecretUnavailable e = assertThrows(SecretUnavailable.class, () -> env.require("env:TENANT_N_ODOO_PASSWORD"));
        assertTrue(e.getMessage().contains("TENANT_N_ODOO_PASSWORD"), e.getMessage());
        assertFalse(e.getMessage().contains("s3cr3t"));
        assertThrows(SecretUnavailable.class, () -> env.require("env:BLANK"));
        assertTrue(env.find("env:BLANK").isEmpty());
        assertTrue(env.find("env:NOPE").isEmpty());
    }

    @Test
    void only_the_env_scheme_and_only_a_variable_name_are_accepted() {
        assertThrows(SecretUnavailable.class, () -> env.require("kv/pn-sphere/btcl/mysql#password"));
        assertThrows(SecretUnavailable.class, () -> env.require("openbao:kv/data/x"));
        assertThrows(SecretUnavailable.class, () -> env.require("env:lower_case"));
        assertThrows(SecretUnavailable.class, () -> env.require("env:"));
        assertThrows(SecretUnavailable.class, () -> env.require(null));
        assertThrows(SecretUnavailable.class, () -> env.find("kv/x"));
    }

    @Test
    void the_process_environment_is_the_default_source() {
        // PATH exists in every process environment; no secret is involved, only the mechanism is proven
        assertTrue(EnvSecrets.fromProcess().find("env:PATH").isPresent());
    }
}
