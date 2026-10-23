package com.telcobright.seed.sessionflow.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.telcobright.seed.sessionflow.api.RecordId;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * The {@code data} of one {@code summary_affected} row (P1-R §B): the entries of the records a batch wrote into one tier, as JSON → gzip →
 * base64 — billing-core's {@code SummaryOutboxWriter.EncodeJson}, and as its mapper writes them: empty values left out, a time as
 * {@code [y, M, d, H, m, s]} (its JavaTimeModule's array; the seconds only when not 0), a {@link RecordId} as the number this try gave it.
 * summary-service reads it back with {@code OutboxCodec.decode} and a mapper that does not mind the case of a name.
 */
public final class SummaryBlob {

    private static final String IDS = "the ids of the try";
    private static final ObjectMapper JSON = new ObjectMapper()
        .setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
        .registerModule(new SimpleModule("summary-blob")
            .addSerializer(RecordId.class, new IdAsItsNumber())
            .addSerializer(LocalDateTime.class, new TimeAsBillingWritesIt()));

    private SummaryBlob() {}

    /** The entries as one row's {@code data}. */
    public static String encode(List<?> entries, IdBook ids) {
        try {
            byte[] json = JSON.writer().withAttribute(IDS, ids).writeValueAsBytes(entries);
            ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(gzipped)) {
                gz.write(json);
            }
            return Base64.getEncoder().encodeToString(gzipped.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("the summary entries could not be encoded: " + e.getMessage(), e);
        }
    }

    private static final class IdAsItsNumber extends JsonSerializer<RecordId> {
        @Override
        public void serialize(RecordId id, JsonGenerator out, SerializerProvider provider) throws IOException {
            Object ids = provider.getAttribute(IDS);
            out.writeNumber(ids instanceof IdBook book ? book.given(id) : 0L);
        }
    }

    /** jackson-datatype-jsr310's {@code LocalDateTimeSerializer} as an array, which billing-core's blob carries. */
    private static final class TimeAsBillingWritesIt extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime t, JsonGenerator out, SerializerProvider provider) throws IOException {
            out.writeStartArray();
            out.writeNumber(t.getYear());
            out.writeNumber(t.getMonthValue());
            out.writeNumber(t.getDayOfMonth());
            out.writeNumber(t.getHour());
            out.writeNumber(t.getMinute());
            if (t.getSecond() > 0 || t.getNano() > 0) out.writeNumber(t.getSecond());
            if (t.getNano() > 0) out.writeNumber(t.getNano());
            out.writeEndArray();
        }
    }
}
