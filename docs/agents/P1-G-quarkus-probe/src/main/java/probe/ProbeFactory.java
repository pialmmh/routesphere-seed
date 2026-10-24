package probe;

import io.quarkus.runtime.ExecutionMode;
import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.common.MapBackedConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Registered as seed-bootstrap-config's factory is (META-INF/services, no @StaticInitSafe), serving probe.value at its ordinal 275. */
public final class ProbeFactory implements ConfigSourceFactory {

    /** Every call, in order: the execution mode at that moment. */
    public static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        CALLS.add(String.valueOf(ExecutionMode.current()));
        return List.of(new MapBackedConfigSource("probe-factory", Map.of("probe.value", "from-the-factory"), 275) { });
    }
}
