package com.telcobright.seed.ledgerclient.internal;

import com.telcobright.seed.ledgerclient.api.RemoteLedgerFault;
import com.telcobright.seed.ledgerclient.spi.Transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** C2 · The roads over HTTP (JDK HttpClient): every request bounded by the timeout; no answer is a {@link RemoteLedgerFault}. */
public final class HttpTransport implements Transport {

    private final URI mount;
    private final HttpClient http;
    private final Duration timeout;

    /** @param mount the application's ledger mount, ending in {@code /} ({@code http://10.10.199.30:7150/ledger/}) */
    public HttpTransport(URI mount, Duration timeout) {
        this.mount = mount;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Response send(String method, String path, String json, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(mount.resolve(path)).timeout(timeout).header("Accept", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if ("POST".equals(method)) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        else request.GET();
        try {
            HttpResponse<String> answer = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(answer.statusCode(), answer.body());
        } catch (IOException e) {
            throw new RemoteLedgerFault(mount + " did not answer: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteLedgerFault(mount + ": interrupted while waiting for the answer", e);
        }
    }
}
