package com.telcobright.seed.callflow.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.seed.callflow.api.CdrEvent;

import java.util.List;
import java.util.Map;

/** The CDR wire as JSON: one call = one array of its tier records, the leaf first. */
public final class CdrJson {

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<List<CdrEvent>> TIERS = new TypeReference<>() {};

    private CdrJson() {}

    /** The Kafka value of one call. */
    public static String ofCall(List<CdrEvent> tiers) {
        try {
            return JSON.writeValueAsString(tiers);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a CDR could not be written as JSON: " + e.getMessage(), e);
        }
    }

    public static List<CdrEvent> toCall(String json) {
        try {
            return JSON.readValue(json, TIERS);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not a CDR array: " + e.getMessage(), e);
        }
    }

    /** The application's facts as the one JSON object of {@code additionalMetaData}. Null when there are none. */
    public static String ofMeta(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the CDR's meta data could not be written as JSON: " + e.getMessage(), e);
        }
    }
}
