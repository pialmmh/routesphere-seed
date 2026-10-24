package com.telcobright.seed.campaign;

import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.TaskState;
import com.telcobright.seed.campaign.internal.DefaultCampaignService;
import com.telcobright.seed.campaign.jdbc.Dialect;
import com.telcobright.seed.campaign.jdbc.JdbcCampaignStore;
import com.telcobright.seed.campaign.jdbc.JdbcCampaignStore.Counters;
import com.telcobright.seed.campaign.spi.CampaignStore;
import com.telcobright.seed.campaign.spi.StoreChange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-M item 1 · a task's id is the lane's (the ad's is built from the request's MAC) and a store's words may quote it: no campaign line
 * is forged by a CR/LF in them. seed-campaign cleans what it logs through the seed's ONE cleaner, seed-logtext's {@code LogText}.
 */
class NoForgedCampaignLogLinesTest {

    static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    static final Instant NOON = Instant.parse("2026-09-27T06:00:00Z");

    @Test
    void aStoreThatFails_saysItsTaskOnOneLine_theTasksIdAndItsOwnWordsEscaped() {
        DefaultCampaignService service = new DefaultCampaignService(failingStore(), Clock.fixed(NOON, DHAKA), DHAKA);
        CampaignTask minted = CampaignTask.minted("ad-btcl-1\nFORGED-ID", "btcl", CampaignKind.AD, "88017", "zone0", null, "wifi-9", NOON,
            Map.of("facts", Map.of("zone", "zone0")));

        List<String> lines = theLogOf(() -> service.handedToCore(minted)).lines().toList();

        assertThat(lines).noneMatch(l -> l.startsWith("FORGED"));
        assertThat(lines).filteredOn(l -> l.contains("campaign store failed at")).singleElement().asString()
            .contains("for task ad-btcl-1\\nFORGED-ID").contains("the store is away\\nFORGED-STORE");
    }

    @Test
    void aTextCutToItsColumn_saysItsTaskOnOneLine() throws Exception {
        JdbcCampaignStore store = CampaignCounterTableTest.store(CampaignCounterTableTest.open(Dialect.MYSQL), Dialect.MYSQL, Counters.COUNTER_TABLE);
        CampaignTask task = new CampaignTask("v1\nFORGED-CUT", "wroot", 42, 701, CampaignKind.AD, "aa:bb:cc:dd:ee:01", "c-77", "zone0", "s".repeat(100),
            "wifi-1", TaskState.PROCESSING, NOON, null, null, 0, null, null, Map.of("viewSeconds", 15));

        List<String> lines = theLogOf(() -> store.write(List.of(new StoreChange.TaskInserted(task)))).lines().toList();

        assertThat(lines).noneMatch(l -> l.startsWith("FORGED"));
        assertThat(lines).filteredOn(l -> l.contains("was cut to the column's")).singleElement().asString().contains("(task v1\\nFORGED-CUT;");
    }

    /** A store that answers nothing: every write fails with words that carry a line break. */
    private static CampaignStore failingStore() {
        return (CampaignStore) Proxy.newProxyInstance(CampaignStore.class.getClassLoader(), new Class<?>[]{CampaignStore.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "toString" -> "a failing store";
                case "hashCode" -> 0;
                case "equals" -> proxy == args[0];
                case "campaigns" -> List.of();
                default -> throw new IllegalStateException("the store is away\nFORGED-STORE");
            });
    }

    /** What the log wrote while {@code act} ran (slf4j-simple, the tests' binding, writes to System.err as it is at each line). */
    static String theLogOf(Runnable act) {
        PrintStream before = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            act.run();
        } finally {
            System.setErr(before);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
