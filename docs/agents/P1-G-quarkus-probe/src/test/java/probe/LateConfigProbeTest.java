package probe;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1-G item 6 · WHEN Quarkus 3.26.1 asks a ConfigSourceFactory registered as seed-bootstrap-config's is (META-INF/services, no
 * {@code @StaticInitSafe}), and who sees its value. Run offline, under the shared lock: {@code mvn -o -q test} (and the prod jar:
 * {@code mvn -o -q package -DskipTests; java -jar target/quarkus-app/quarkus-run.jar; curl 127.0.0.1:7795/seen}).
 */
@QuarkusTest
class LateConfigProbeTest {

    @Test
    void theFactoryIsAskedAtRuntimeInit_aBeanQuarkusRestBuildsAtStaticInitSeesTheJarsOwn() {
        Seen.BY_READER.forEach((reader, seen) -> System.out.println("PROBE " + reader + " saw " + seen));
        assertEquals(List.of("RUNTIME_INIT"), ProbeFactory.CALLS, "asked once, for the run-time config — never for the static-init one");
        assertEquals("from-the-factory @RUNTIME_INIT", Seen.BY_READER.get("plain @Startup bean"));
        assertEquals("from-the-factory @RUNTIME_INIT", Seen.BY_READER.get("@Observes StartupEvent (ConfigProvider.getConfig())"));
        assertEquals("from-the-jar @STATIC_INIT", Seen.BY_READER.get("@Startup bean a REST resource injects"));
        assertEquals("from-the-factory @RUNTIME_INIT", Seen.BY_READER.get("@Startup bean a REST resource reaches by Instance<>"));
    }
}
