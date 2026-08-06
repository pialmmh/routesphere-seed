package com.telcobright.seed.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The convention under test is routesphere's own (tenants[i] rows + active.tenant
 * + config/tenants/&lt;t&gt;/&lt;p&gt;/profile-&lt;p&gt;.yml + channels/&lt;concept&gt;/&lt;instance&gt;.yml):
 * enabled-only loading, sysprop precedence, dotted-path access, channel lookup,
 * typed binding, and the external-dir override a deployed service uses.
 */
class TenantConfigRegistryTest {

    @AfterEach
    void clearSysProps() {
        System.clearProperty("active.tenant");
        System.clearProperty("tenants[0].profile");
    }

    @Test
    void loads_enabled_tenants_only_and_the_active_profile() {
        TenantConfigRegistry reg = TenantConfigRegistry.load(null);
        assertEquals(List.of("wifi"),
            reg.tenants().stream().map(TenantRef::name).toList()); // disabled_one filtered
        assertEquals("wifi", reg.activeTenant());
        assertEquals("dev", reg.active().profileName());
    }

    @Test
    void dotted_path_reads_the_profile_yml() {
        TenantProfile p = TenantConfigRegistry.load(null).active();
        assertEquals(24, p.get("wifi.session.maxHours"));
        assertEquals("127.0.0.1", p.get("wifi.redis.host"));
        assertNull(p.get("wifi.no.such.path"));
    }

    @Test
    void channel_lookup_finds_instances_and_absence_is_null() {
        TenantProfile p = TenantConfigRegistry.load(null).active();
        assertEquals(List.of("omniqueue-main"), p.channelInstances("omniqueue"));
        Map<String, Object> q = p.channel("omniqueue", "omniqueue-main");
        assertNotNull(q);
        assertEquals("wifi-main", p.bind(q, QueueCfg.class).queue.name);
        assertNull(p.channel("omniqueue", "no-such-instance"));
        assertNull(p.channel("sigtran", "anything")); // absent concept = information
    }

    @Test
    void system_properties_override_file_properties() {
        System.setProperty("active.tenant", "wifi");
        System.setProperty("tenants[0].profile", "dev"); // same value; proves the path
        TenantConfigRegistry reg = TenantConfigRegistry.load(null);
        assertEquals("wifi", reg.activeTenant());
    }

    @Test
    void external_dir_overrides_the_classpath_tree(@TempDir Path dir) throws Exception {
        Path tree = dir.resolve("config/tenants/wifi/dev");
        Files.createDirectories(tree.resolve("channels/omniqueue"));
        Files.writeString(tree.resolve("profile-dev.yml"),
            "wifi:\n  session:\n    maxHours: 5\n");
        Files.writeString(tree.resolve("channels/omniqueue/omniqueue-main.yml"),
            "queue:\n  name: external-wins\n");

        TenantProfile p = TenantConfigRegistry.load(dir).active();
        assertEquals(5, p.get("wifi.session.maxHours")); // external file won
        assertEquals("external-wins",
            p.bind(p.channel("omniqueue", "omniqueue-main"), QueueCfg.class).queue.name);
    }

    /** snakeyaml bean-binding target for the channel yml. */
    public static class QueueCfg {
        public Queue queue;
        public static class Queue {
            public String name;
            public String segmentDir;
            public int maxSegmentMb;
        }
    }
}
