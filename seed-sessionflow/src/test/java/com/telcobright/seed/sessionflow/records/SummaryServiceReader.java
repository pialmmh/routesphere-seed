package com.telcobright.seed.sessionflow.records;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * summary-service's reader of a {@code summary_affected} row, copied step by step (summary-service main 72d5875, read only): the row's
 * {@code data} through {@code outbox/internal/OutboxCodec.decode} (base64 → gunzip), then the ad category's decode
 * ({@code summarybeans/ad/internal/AdSummaryBean.decode}) with {@code call/internal/CdrBlobMapper} (JavaTime, names without case, unknown
 * names ignored) into its {@code AdCallEntry} / {@code AdCallCdr} / {@code Chargeable} — the fields it reads, as it declares them.
 */
final class SummaryServiceReader {

    /** {@code CdrBlobMapper.create()}. */
    private static final ObjectMapper BLOB = JsonMapper.builder().addModule(new JavaTimeModule())
        .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private SummaryServiceReader() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AdCallEntry(AdCallCdr cdr, Chargeable customer, List<Chargeable> chargeables) {
        /** {@code CdrBlobEntry.customerLegOf}: the leg of direction 1, else the first. */
        Chargeable customerLeg() {
            List<Chargeable> legs = chargeables != null && !chargeables.isEmpty() ? chargeables : customer != null ? List.of(customer) : List.of();
            return legs.stream().filter(l -> l.assignedDirection() != null && l.assignedDirection() == 1).findFirst().orElse(legs.isEmpty() ? null : legs.get(0));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AdCallCdr(Integer serviceGroup, Integer inPartnerId, String originatingCalledNumber, String codec, String hangupCause,
                     LocalDateTime startTime, LocalDateTime answerTime, BigDecimal durationSec, String additionalMetaData) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Chargeable(int servicegroup, int servicefamily, Integer assignedDirection, long productId, String idBilledUom, String prefix,
                      LocalDateTime transactionTime, BigDecimal unitPriceOrCharge, BigDecimal billedAmount, BigDecimal quantity) {}

    /** {@code OutboxCodec.decode}, then the ad bean's decode. */
    static List<AdCallEntry> read(String data) throws IOException {
        return BLOB.readValue(decode(data), BLOB.getTypeFactory().constructCollectionType(List.class, AdCallEntry.class));
    }

    /** {@code OutboxCodec.decode}: base64 → gunzip → the JSON bytes. */
    static byte[] decode(String base64Gzip) throws IOException {
        byte[] gzipped = Base64.getDecoder().decode(base64Gzip);
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
