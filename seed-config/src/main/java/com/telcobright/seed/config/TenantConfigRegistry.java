package com.telcobright.seed.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

import org.yaml.snakeyaml.Yaml;

/**
 * Loads the routesphere config convention and answers "what is this service's
 * configuration?" — one registry per process.
 *
 * <pre>
 *   application.properties          which tenants exist / are enabled / active
 *   config/tenants/&lt;t&gt;/&lt;p&gt;/…       the per-tenant per-profile tree
 * </pre>
 *
 * Sources, in priority order:
 * <ol>
 *   <li>System properties ({@code -Dtenants[0].name=…}, {@code -Dactive.tenant=…}) —
 *       always win, same rule as routesphere.</li>
 *   <li>An external config directory ({@code -Dseed.config.dir=/etc/myapp} or
 *       env {@code SEED_CONFIG_DIR}) holding {@code application.properties} and/or
 *       {@code config/tenants/…} — how deployed services override the jar.</li>
 *   <li>The classpath — the config baked into the artifact.</li>
 * </ol>
 *
 * The registry is immutable after {@link #load()}; a config change means a new
 * load (wire the config-manager doorbell to trigger it when live reload lands).
 */
public final class TenantConfigRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(TenantConfigRegistry.class);
    private static final int MAX_TENANT_SLOTS = 50;

    private final List<TenantRef> tenants;
    private final String activeTenant;
    private final Map<String, TenantProfile> profiles; // key: tenant name

    private TenantConfigRegistry(List<TenantRef> tenants, String activeTenant,
                                 Map<String, TenantProfile> profiles) {
        this.tenants = tenants;
        this.activeTenant = activeTenant;
        this.profiles = profiles;
    }

    /** All enabled tenants declared for this process. */
    public List<TenantRef> tenants() { return tenants; }

    /** The active tenant's name (active.tenant), or the single enabled tenant. */
    public String activeTenant() { return activeTenant; }

    /** The active tenant's loaded profile — the common case for a single-tenant service. */
    public TenantProfile active() {
        TenantProfile p = profiles.get(activeTenant);
        if (p == null) throw new IllegalStateException(
            "active tenant '" + activeTenant + "' has no loaded profile — check config/tenants/");
        return p;
    }

    public Optional<TenantProfile> forTenant(String name) {
        return Optional.ofNullable(profiles.get(name));
    }

    // ─────────────────────────────────────────────────────────────────
    // Loading
    // ─────────────────────────────────────────────────────────────────

    public static TenantConfigRegistry load() {
        return load(externalDir());
    }

    /** Load with an explicit external dir (null = classpath only). Visible for tests. */
    public static TenantConfigRegistry load(Path externalDir) {
        Properties props = loadProperties(externalDir);
        List<TenantRef> tenants = readTenantRefs(props);
        if (tenants.isEmpty())
            throw new IllegalStateException(
                "no enabled tenants — declare tenants[0].name/.enabled in application.properties");

        String active = props.getProperty("active.tenant",
            tenants.size() == 1 ? tenants.get(0).name() : null);
        if (active == null)
            throw new IllegalStateException(
                "multiple tenants but no active.tenant in application.properties");

        Map<String, TenantProfile> loaded = new LinkedHashMap<>();
        for (TenantRef t : tenants) {
            loaded.put(t.name(), loadProfile(externalDir, t.name(), t.profile()));
        }
        LOG.info("seed-config: {} tenant(s) loaded, active='{}'", loaded.size(), active);
        return new TenantConfigRegistry(List.copyOf(tenants), active,
            Collections.unmodifiableMap(loaded));
    }

    private static Path externalDir() {
        String dir = System.getProperty("seed.config.dir", System.getenv("SEED_CONFIG_DIR"));
        if (dir == null || dir.isBlank()) return null;
        Path p = Path.of(dir);
        if (!Files.isDirectory(p))
            throw new IllegalStateException("seed.config.dir does not exist: " + dir);
        return p;
    }

    private static Properties loadProperties(Path externalDir) {
        Properties fromFiles = new Properties();
        try (InputStream is = TenantConfigRegistry.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is != null) fromFiles.load(is);
        } catch (IOException e) {
            LOG.warn("cannot read classpath application.properties: {}", e.toString());
        }
        if (externalDir != null) {
            Path ext = externalDir.resolve("application.properties");
            if (Files.isRegularFile(ext)) {
                try (InputStream is = Files.newInputStream(ext)) {
                    fromFiles.load(is); // external overrides classpath, key by key
                } catch (IOException e) {
                    LOG.warn("cannot read {}: {}", ext, e.toString());
                }
            }
        }
        // System properties override everything (the routesphere rule)
        Properties merged = new Properties();
        merged.putAll(fromFiles);
        System.getProperties().forEach((k, v) -> merged.put(k, v));
        return merged;
    }

    private static List<TenantRef> readTenantRefs(Properties props) {
        List<TenantRef> out = new ArrayList<>();
        for (int i = 0; i < MAX_TENANT_SLOTS; i++) {
            String name = props.getProperty("tenants[" + i + "].name");
            if (name == null || name.isBlank()) continue;
            boolean enabled = Boolean.parseBoolean(
                props.getProperty("tenants[" + i + "].enabled", "true"));
            String profile = props.getProperty("tenants[" + i + "].profile", "dev");
            if (enabled) out.add(new TenantRef(name.trim(), profile.trim(), true));
        }
        return out;
    }

    // ── the per-tenant tree ──────────────────────────────────────────

    private static TenantProfile loadProfile(Path externalDir, String tenant, String profile) {
        String base = "config/tenants/" + tenant + "/" + profile;

        Map<String, Object> profileYaml = readYaml(externalDir, base + "/profile-" + profile + ".yml");
        if (profileYaml == null) {
            LOG.warn("seed-config: {} has no profile-{}.yml — starting empty", base, profile);
            profileYaml = TenantProfile.emptyYaml();
        }

        // discover every section folder (channels/<concept>, pipelines, …) with .yml files
        Map<String, Map<String, Map<String, Object>>> sections = new LinkedHashMap<>();
        Path root = resolveDir(externalDir, base);
        if (root != null) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".yml"))
                    .filter(p -> !p.getFileName().toString().equals("profile-" + profile + ".yml"))
                    .forEach(p -> {
                        Path rel = root.relativize(p);
                        if (rel.getNameCount() < 2) return; // section files live in folders
                        String sectionPath = rel.getParent().toString().replace('\\', '/');
                        String instance = stripYml(rel.getFileName().toString());
                        Map<String, Object> yaml = readYamlFile(p);
                        if (yaml != null)
                            sections.computeIfAbsent(sectionPath, k -> new LinkedHashMap<>())
                                    .put(instance, yaml);
                    });
            } catch (IOException e) {
                LOG.warn("seed-config: cannot walk {}: {}", root, e.toString());
            }
        }
        LOG.info("seed-config: tenant '{}' profile '{}' loaded ({} section folder(s))",
            tenant, profile, sections.size());
        return new TenantProfile(tenant, profile, profileYaml,
            Collections.unmodifiableMap(sections));
    }

    /** External dir wins per-file; falls back to a classpath-extracted view. */
    private static Path resolveDir(Path externalDir, String relative) {
        if (externalDir != null) {
            Path p = externalDir.resolve(relative);
            if (Files.isDirectory(p)) return p;
        }
        var url = TenantConfigRegistry.class.getClassLoader().getResource(relative);
        if (url != null && "file".equals(url.getProtocol())) {
            try {
                return Path.of(url.toURI());
            } catch (Exception ignored) { }
        }
        // inside a jar: sections are enumerated via the filesystem only. Services
        // that jar their config should list channels explicitly in profile yml,
        // or ship an external config dir (the deployed norm).
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readYaml(Path externalDir, String relative) {
        if (externalDir != null) {
            Path p = externalDir.resolve(relative);
            if (Files.isRegularFile(p)) return readYamlFile(p);
        }
        try (InputStream is = TenantConfigRegistry.class.getClassLoader()
                .getResourceAsStream(relative)) {
            if (is == null) return null;
            Object o = new Yaml().load(is);
            return o instanceof Map ? (Map<String, Object>) o : TenantProfile.emptyYaml();
        } catch (IOException e) {
            LOG.warn("seed-config: cannot read {}: {}", relative, e.toString());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readYamlFile(Path p) {
        try (InputStream is = Files.newInputStream(p)) {
            Object o = new Yaml().load(is);
            return o instanceof Map ? (Map<String, Object>) o : TenantProfile.emptyYaml();
        } catch (IOException e) {
            LOG.warn("seed-config: cannot read {}: {}", p, e.toString());
            return null;
        }
    }

    private static String stripYml(String name) {
        return name.endsWith(".yml") ? name.substring(0, name.length() - 4) : name;
    }
}
