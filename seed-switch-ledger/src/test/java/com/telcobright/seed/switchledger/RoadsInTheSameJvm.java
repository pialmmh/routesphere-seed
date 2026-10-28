package com.telcobright.seed.switchledger;

import com.telcobright.seed.ledgerclient.api.RemoteLedgerFault;
import com.telcobright.seed.ledgerclient.spi.Transport;
import com.telcobright.seed.switchledger.api.CachedLedgerRoads;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * C2 · The client's transport straight into the application's roads, in the same JVM (no socket) — and the mount an application gives
 * them: {@code POST <root>/units}, {@code GET <root>/rows/<tier>/<table>/<id>}, {@code GET <root>/rows/<tier>/<table>?field=value…}.
 * {@link #loseTheNextAnswer} lets one unit be written and its answer lost on the way back (the gateway's timeout of 2026-10-21).
 */
final class RoadsInTheSameJvm implements Transport {

    private final CachedLedgerRoads roads;
    private final AtomicBoolean loseTheNext = new AtomicBoolean();

    RoadsInTheSameJvm(CachedLedgerRoads roads) { this.roads = roads; }

    /** The next unit is written, and its answer never reaches the client. */
    void loseTheNextAnswer() { loseTheNext.set(true); }

    @Override
    public Response send(String method, String path, String json, String token) {
        String[] pathAndQuery = path.split("\\?", 2);
        List<String> segments = List.of(pathAndQuery[0].split("/")).stream().map(RoadsInTheSameJvm::decoded).toList();
        CachedLedgerRoads.Answer answer = answer(method, segments, pathAndQuery.length > 1 ? pathAndQuery[1] : "", json, token);
        if ("POST".equals(method) && loseTheNext.getAndSet(false)) throw new RemoteLedgerFault("the answer was lost on the way back (timeout)");
        return new Response(answer.status(), answer.json());
    }

    private CachedLedgerRoads.Answer answer(String method, List<String> s, String query, String json, String token) {
        if ("POST".equals(method) && s.size() == 2 && s.get(1).equals("units")) return roads.unit(s.get(0), token, json);
        if ("GET".equals(method) && s.size() == 5 && s.get(1).equals("rows")) return roads.row(s.get(0), token, s.get(2), s.get(3), s.get(4));
        if ("GET".equals(method) && s.size() == 4 && s.get(1).equals("rows")) return roads.rows(s.get(0), token, s.get(2), s.get(3), queryOf(query));
        return new CachedLedgerRoads.Answer(404, "{\"error\":\"no such road\"}");
    }

    private static Map<String, String> queryOf(String query) {
        Map<String, String> equals = new LinkedHashMap<>();
        if (query.isEmpty()) return equals;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            equals.put(decoded(kv[0]), kv.length > 1 ? decoded(kv[1]) : "");
        }
        return equals;
    }

    private static String decoded(String text) { return URLDecoder.decode(text, StandardCharsets.UTF_8); }
}
