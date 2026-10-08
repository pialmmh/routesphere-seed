package com.telcobright.seed.tenant.internal;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.tenant.spi.TreeSource;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The tree of one served tenant from prime-context — routesphere's road, {@code POST <base>/get-specific-tenant-root?name=<tenant>},
 * answered with the whole tree as JSON. One connect / read timeout (the REMOTE-CALL rule); an optional bearer for a facade that asks
 * one (prime-context's read roads are open on the management network today). The parse and the rebuild are {@link Trees}'.
 */
public final class PrimeContextTreeSource implements TreeSource {

    private final String base;
    private final Duration timeout;
    private final Supplier<String> bearer;
    private final HttpClient client;

    /** @param bearer the token for a facade that asks one; null = the road is open. */
    public PrimeContextTreeSource(String baseUrl, Duration timeout, Supplier<String> bearer) {
        this.base = baseUrl.replaceAll("/+$", "");
        this.timeout = timeout;
        this.bearer = bearer;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Tenant fetch(String tenantName) {
        URI uri = URI.create(base + "/get-specific-tenant-root?name=" + URLEncoder.encode(tenantName, StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody());
        String token = bearer == null ? null : bearer.get();
        if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new TreeUnavailable("prime-context " + uri.getHost() + ":" + uri.getPort() + " did not answer for tenant " + tenantName + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TreeUnavailable("interrupted while waiting for prime-context (tenant " + tenantName + ")", e);
        }
        if (response.statusCode() / 100 != 2) throw new TreeUnavailable("prime-context answered HTTP " + response.statusCode() + " for tenant " + tenantName);
        return Trees.parse(response.body());
    }
}
