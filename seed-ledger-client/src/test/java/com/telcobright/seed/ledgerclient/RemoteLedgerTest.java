package com.telcobright.seed.ledgerclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.telcobright.seed.ledgerclient.api.RemoteLedger;
import com.telcobright.seed.ledgerclient.api.RemoteLedgerFault;
import com.telcobright.seed.ledgerclient.dependencies.RemoteLedgers;
import com.telcobright.seed.ledgerclient.internal.Wire;
import com.telcobright.seed.ledgerclient.spi.Transport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C2 · the client's side of the wire, on a transport that records each request and answers what it is told: ONE request per unit, its
 * moves in order, a new row's key as {@code {"$key": n}}, numbers exact, the token asked at each request; every answer mapped — 200,
 * 409 refused, 400 a mistake, 403 not allowed, 404 no row, 5xx and no JSON a fault (send again under the reference).
 */
class RemoteLedgerTest {

    private final List<String[]> sent = new ArrayList<>();
    private Transport.Response next = new Transport.Response(200, "{\"committed\":true,\"repeated\":false,\"keys\":[10,31],\"rows\":[]}");
    private final java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();

    /** The token is asked at every request (a rotated one is taken at once): t-1, t-2, … */
    private final RemoteLedger ledger = RemoteLedgers.builder().root("btcl").token(() -> "t-" + asked.incrementAndGet()).transport((method, path, json, token) -> {
        sent.add(new String[] {method, path, json, token});
        return next;
    }).build();

    @Test
    void aUnitIsOneRequest_itsMovesInOrder_aNewRowsKeyAsAPlaceholder_numbersExact() throws Exception {
        RemoteLedger.Unit unit = ledger.unit("topup:pay-1");
        RemoteLedger.Key purchase = unit.insert("btcl", "packagepurchase", Map.of("idPartner", 4, "purchaseDate", LocalDateTime.of(2026, 10, 28, 3, 0)));
        unit.update("btcl", "packageaccount", 30, Map.of("idpackagePurchase", purchase))
            .add("btcl", "packageaccount", 30, "balanceAfter", new BigDecimal("0.10"))
            .requireEqual("btcl", "packageaccount", 31, "balanceAfter", new BigDecimal("1000.000000"))
            .requireNone("btcl", "packageaccount", Map.of("uom", "TF_min"));

        RemoteLedger.Committed done = (RemoteLedger.Committed) unit.commit();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)[0]).isEqualTo("POST");
        assertThat(sent.get(0)[1]).isEqualTo("btcl/units");
        assertThat(sent.get(0)[3]).isEqualTo("t-1");
        JsonNode request = Wire.JSON.readTree(sent.get(0)[2]);
        assertThat(request.get("reference").asText()).isEqualTo("topup:pay-1");
        assertThat(request.get("moves")).extracting(move -> move.get("op").asText()).containsExactly("insert", "update", "add", "require", "requireNone");
        assertThat(request.at("/moves/0/row/purchaseDate").asText()).isEqualTo("2026-10-28T03:00");
        assertThat(request.at("/moves/1/set/idpackagePurchase/$key").asInt()).isEqualTo(0);
        assertThat(sent.get(0)[2]).contains("\"delta\":0.10", "\"value\":1000.000000");
        assertThat(done.keyOf(purchase)).isEqualTo(10L);
    }

    @Test
    void everyAnswerIsMapped() {
        next = new Transport.Response(409, "{\"committed\":false,\"refused\":\"PackageAccount 30 of btcl: balanceAfter is 40, not 50 (require equal to)\"}");
        assertThat(((RemoteLedger.Refused) ledger.unit().add("btcl", "packageaccount", 30, "balanceAfter", BigDecimal.ONE).commit()).reason()).contains("not 50");

        next = new Transport.Response(400, "{\"error\":\"PackageAccount has no field 'x'\"}");
        assertThatThrownBy(() -> ledger.unit().add("btcl", "packageaccount", 30, "x", BigDecimal.ONE).commit()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no field 'x'");

        next = new Transport.Response(403, "{\"error\":\"not allowed\"}");
        assertThatThrownBy(() -> ledger.rows("btcl", "packageaccount", Map.of())).isInstanceOf(IllegalStateException.class).hasMessageContaining("not allowed");

        next = new Transport.Response(404, "{\"error\":\"no packageaccount 99 in btcl\"}");
        assertThat(ledger.row("btcl", "packageaccount", 99)).isEmpty();

        next = new Transport.Response(404, "{\"error\":\"this application keeps no cached ledger for the root 'btcl'\"}");
        assertThatThrownBy(() -> ledger.row("btcl", "packageaccount", 99)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no cached ledger");

        next = new Transport.Response(503, "{\"error\":\"the lock of account 30 in btcl was not free within 2000 ms\"}");
        assertThatThrownBy(() -> ledger.unit("r").add("btcl", "packageaccount", 30, "balanceAfter", BigDecimal.ONE).commit()).isInstanceOf(RemoteLedgerFault.class).hasMessageContaining("503");

        next = new Transport.Response(502, "<html>bad gateway</html>");
        assertThatThrownBy(() -> ledger.unit("r").add("btcl", "packageaccount", 30, "balanceAfter", BigDecimal.ONE).commit()).isInstanceOf(RemoteLedgerFault.class).hasMessageContaining("no JSON");
    }

    @Test
    void readsCarryTheirRowsWayAndQuery_encoded() {
        next = new Transport.Response(200, "{\"rows\":[{\"id\":30,\"balanceAfter\":50.000000,\"uom\":\"BDT\"}]}");

        List<Map<String, Object>> rows = ledger.rows("btcl", "packageaccount", Map.of("name", "Top Up&x"));

        assertThat(sent.get(0)[1]).isEqualTo("btcl/rows/btcl/packageaccount?name=Top+Up%26x");
        assertThat(rows.get(0)).containsEntry("id", 30L).containsEntry("uom", "BDT");
        assertThat((BigDecimal) rows.get(0).get("balanceAfter")).isEqualByComparingTo("50");
    }

    @Test
    void aUnitCommitsOnce() {
        RemoteLedger.Unit unit = ledger.unit();
        unit.add("btcl", "packageaccount", 30, "balanceAfter", BigDecimal.ONE).commit();

        assertThatThrownBy(unit::commit).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> unit.add("btcl", "packageaccount", 30, "balanceAfter", BigDecimal.ONE)).isInstanceOf(IllegalStateException.class);
    }
}
