package com.telcobright.seed.sessionflow.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.seed.sessionflow.spi.BootstrapSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * prime-context's bootstrap over HTTP (§C.1): {@code GET <url>} with {@code Authorization: Bearer <the service's own token>} — the token is
 * read from the environment by its variable's NAME, at every ask (a rotated token is taken); {@code If-None-Match} with the last etag, so
 * a 304 answers the envelope already held. One timeout for the connect and the read (the REMOTE-CALL rule).
 */
public final class HttpBootstrapSource implements BootstrapSource {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI url;
    private final String tokenVar;
    private final Function<String, String> environment;
    private final Duration timeout;
    private final HttpClient client;
    private Map<String, Object> last;
    private String lastEtag;

    public HttpBootstrapSource(String url, String tokenVar, Function<String, String> environment, Duration timeout) {
        if (url == null || url.isBlank()) throw new IllegalStateException("the application's yml names no prime-context.bootstrap-url — it is the one thing it must hold (R8)");
        if (tokenVar == null || tokenVar.isBlank()) throw new IllegalStateException("the application's yml names no prime-context.token-env: the NAME of the variable holding its token");
        this.url = URI.create(url.trim());
        this.tokenVar = tokenVar.trim();
        this.environment = environment;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public synchronized Map<String, Object> envelope() {
        HttpResponse<String> response = send();
        if (response.statusCode() == 304 && last != null) return last;
        if (response.statusCode() / 100 != 2) throw new BootstrapUnavailable("prime-context answered HTTP " + response.statusCode() + " for " + where());
        keep(response);
        return last;
    }

    @Override public String origin() { return url.getScheme() + "://" + url.getRawAuthority(); }

    @Override public String where() { return url.toString(); }

    private HttpResponse<String> send() {
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(timeout).header("Accept", "application/json").header("Authorization", "Bearer " + token()).GET();
        if (lastEtag != null) request.header("If-None-Match", lastEtag);
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new BootstrapUnavailable("prime-context did not answer at " + where() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BootstrapUnavailable("interrupted while waiting for prime-context at " + where(), e);
        }
    }

    private String token() {
        String token = environment.apply(tokenVar);
        if (token == null || token.isBlank()) throw new IllegalStateException("the bootstrap token's variable " + tokenVar + " is empty — the start is refused (S20)");
        return token.trim();
    }

    private void keep(HttpResponse<String> response) {
        try {
            last = JSON.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new BootstrapUnavailable("prime-context's envelope at " + where() + " is not JSON: " + e.getMessage(), e);
        }
        lastEtag = response.headers().firstValue("ETag").orElse(last.get("etag") == null ? null : String.valueOf(last.get("etag")));
    }
}
