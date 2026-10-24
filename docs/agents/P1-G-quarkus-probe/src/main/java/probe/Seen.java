package probe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** What each reader saw of probe.value, and the execution mode it read it in. */
public final class Seen {
    public static final Map<String, String> BY_READER = new ConcurrentHashMap<>();
    private Seen() {}
    public static void read(String reader, String value) { BY_READER.put(reader, value + " @" + io.quarkus.runtime.ExecutionMode.current()); }
}
