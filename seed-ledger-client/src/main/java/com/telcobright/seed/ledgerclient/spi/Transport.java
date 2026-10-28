package com.telcobright.seed.ledgerclient.spi;

/**
 * C2 · How a request reaches the application that keeps the cached ledger: HTTP by default; a test hands the application's roads in
 * directly. Every path is below the application's ledger mount: {@code <root>/units}, {@code <root>/rows/<tier>/<table>/<id>},
 * {@code <root>/rows/<tier>/<table>?field=value…}.
 */
@FunctionalInterface
public interface Transport {

    /** What the application answered: its status and its JSON. */
    record Response(int status, String body) {}

    /**
     * @param method GET or POST
     * @param json   the request's JSON (POST), else null
     * @param token  the service token, sent as {@code Authorization: Bearer …}; null = none
     * @throws com.telcobright.seed.ledgerclient.api.RemoteLedgerFault the application did not answer
     */
    Response send(String method, String path, String json, String token);
}
