package com.telcobright.seed.idgen.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** One shard of a bucket-next cluster, by its base URL: GET, POST and PUT of JSON, every call bounded by the timeout. */
public final class Shard {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI base;
    private final HttpClient http;
    private final Duration timeout;

    public Shard(URI base, HttpClient http, Duration timeout) {
        this.base = base;
        this.http = http;
        this.timeout = timeout;
    }

    public URI base() { return base; }

    Answer get(String path) { return send(request(path).GET()); }

    Answer post(String path, String json) { return send(request(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json))); }

    Answer put(String path, String json) { return send(request(path).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(json))); }

    private HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(base.resolve(path)).timeout(timeout); }

    private Answer send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> r = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Answer(r.statusCode(), parse(r.body()));
        } catch (IOException e) {
            throw new ShardDown(base + " did not answer: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShardDown(base + ": interrupted while waiting");
        }
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) return JSON.createObjectNode();
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            return JSON.createObjectNode().put("error", "not JSON").put("message", body.length() > 200 ? body.substring(0, 200) : body);
        }
    }
}
