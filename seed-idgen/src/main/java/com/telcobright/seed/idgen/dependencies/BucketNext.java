package com.telcobright.seed.idgen.dependencies;

import com.telcobright.seed.idgen.internal.BucketNextSource;
import com.telcobright.seed.idgen.internal.Shard;
import com.telcobright.seed.idgen.spi.IdSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The id source of a bucket-next cluster: every shard's base URL (from the product's own config file: no secret, the service has no
 * authentication and lives on the private network) and the time one call may take.
 *
 * <pre>
 *   IdSource ids = BucketNext.builder().shards("http://10.10.199.21:7001,http://10.10.198.21:7001").timeout(Duration.ofSeconds(2)).build();
 *   int idPartner = new PartnerIds(ids).next("btcl", highestInTree);
 * </pre>
 */
public final class BucketNext {

    private BucketNext() {}

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<URI> shards = new ArrayList<>();
        private Duration timeout = Duration.ofSeconds(2);

        private Builder() {}

        /** One shard's base URL ({@code http://host:port}). */
        public Builder shard(String baseUrl) {
            if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("a shard's base URL is required");
            String url = baseUrl.trim();
            shards.add(URI.create(url.endsWith("/") ? url : url + "/"));
            return this;
        }

        /** Every shard, comma-separated — the shape of one config key. */
        public Builder shards(String commaSeparated) {
            for (String url : commaSeparated.split(",")) if (!url.isBlank()) shard(url);
            return this;
        }

        /** How long one call to one shard may take before the next shard is asked (default 2 s). */
        public Builder timeout(Duration v) {
            if (v == null || v.isNegative() || v.isZero()) throw new IllegalArgumentException("the timeout must be positive");
            this.timeout = v;
            return this;
        }

        /** Refuses to build without a shard: there is no default cluster. */
        public IdSource build() {
            if (shards.isEmpty()) throw new IllegalStateException("bucket-next needs at least one shard's base URL — there is no default");
            HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
            return new BucketNextSource(shards.stream().map(base -> new Shard(base, http, timeout)).toList());
        }
    }
}
