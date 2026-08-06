package com.telcobright.seed.config;

import org.yaml.snakeyaml.Yaml;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One tenant's fully-loaded configuration for one profile:
 *
 * <pre>
 * config/tenants/&lt;tenant&gt;/&lt;profile&gt;/
 *   profile-&lt;profile&gt;.yml          → {@link #profile()}
 *   channels/&lt;concept&gt;/&lt;name&gt;.yml  → {@link #channel(String, String)}
 *   &lt;anything-else&gt;/&lt;name&gt;.yml     → {@link #section(String, String)}  (e.g. pipelines/)
 * </pre>
 *
 * All YAML is exposed as nested {@code Map<String,Object>}; {@link #bind} turns
 * any node into a typed POJO when a class is more convenient than a map.
 */
public final class TenantProfile {

    private final String tenant;
    private final String profileName;
    private final Map<String, Object> profileYaml;
    // sectionName -> instanceName -> yaml   ("channels" is just the best-known section)
    private final Map<String, Map<String, Map<String, Object>>> sections;

    TenantProfile(String tenant, String profileName,
                  Map<String, Object> profileYaml,
                  Map<String, Map<String, Map<String, Object>>> sections) {
        this.tenant = tenant;
        this.profileName = profileName;
        this.profileYaml = profileYaml;
        this.sections = sections;
    }

    public String tenant() { return tenant; }
    public String profileName() { return profileName; }

    /** The entry-point yml (profile-&lt;profile&gt;.yml) as a map; never null. */
    public Map<String, Object> profile() { return profileYaml; }

    /** channels/&lt;concept&gt;/&lt;instance&gt;.yml, or null when absent (absence is information). */
    public Map<String, Object> channel(String concept, String instance) {
        return section("channels/" + concept, instance);
    }

    /** Instance names under channels/&lt;concept&gt;/ — empty when the concept folder is absent. */
    public List<String> channelInstances(String concept) {
        Map<String, Map<String, Object>> m = sections.get("channels/" + concept);
        return m == null ? Collections.emptyList() : List.copyOf(m.keySet());
    }

    /** Any other config folder (pipelines/…): section("pipelines", "sms-pipelines"). */
    public Map<String, Object> section(String sectionPath, String instance) {
        Map<String, Map<String, Object>> m = sections.get(sectionPath);
        return m == null ? null : m.get(instance);
    }

    /**
     * Dotted-path lookup into the profile yml: {@code get("wifi.session.maxHours")}.
     * Returns null when any hop is missing — callers decide their own defaults.
     */
    @SuppressWarnings("unchecked")
    public Object get(String dottedPath) {
        Object node = profileYaml;
        for (String hop : dottedPath.split("\\.")) {
            if (!(node instanceof Map)) return null;
            node = ((Map<String, Object>) node).get(hop);
            if (node == null) return null;
        }
        return node;
    }

    /** Re-serialize any yaml node into a typed POJO (snakeyaml bean rules). */
    public <T> T bind(Map<String, Object> node, Class<T> type) {
        if (node == null) return null;
        Yaml yaml = new Yaml();
        return yaml.loadAs(yaml.dump(node), type);
    }

    static Map<String, Object> emptyYaml() { return new LinkedHashMap<>(); }
}
