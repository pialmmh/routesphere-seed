package com.telcobright.seed.sessionflow.testkit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-F item 6 · the testkit's {@code FakeKafka} links only the producer constructor kafka-clients 3.7.1 (the seed's) and 4.0.0 (Quarkus
 * 3.26.1's, ad-sphere's) both have. 4.0 removed {@code MockProducer(boolean, Serializer, Serializer)}: the seed built on 3.7.1, and an
 * application on 4.0.0 met a {@code NoSuchMethodError} at its first producer. Read in FakeKafka's own class file: the removed descriptor is
 * not there, the four-argument one is. The run on both versions is proven outside the reactor (P1-F-state.md, item 6).
 */
class FakeKafkaOnKafka4Test {

    private static final String SERIALIZER = "Lorg/apache/kafka/common/serialization/Serializer;";
    /** MockProducer(boolean, Serializer, Serializer) — 3.x only. */
    private static final String REMOVED_IN_4_0 = "(Z" + SERIALIZER + SERIALIZER + ")V";
    /** MockProducer(boolean, Partitioner, Serializer, Serializer) — in 3.7.1 and in 4.0.0. */
    private static final String IN_BOTH = "(ZLorg/apache/kafka/clients/producer/Partitioner;" + SERIALIZER + SERIALIZER + ")V";

    @Test
    void fakeKafkaBuildsItsProducers_withTheConstructorBothVersionsHave() throws IOException {
        String classFile = constantsOf("FakeKafka.class");
        assertThat(classFile.contains(REMOVED_IN_4_0)).as("FakeKafka links MockProducer%s, which kafka-clients 4.0 removed", REMOVED_IN_4_0).isFalse();
        assertThat(classFile.contains(IN_BOTH)).as("FakeKafka links MockProducer%s, in 3.7.1 and in 4.0.0", IN_BOTH).isTrue();
    }

    private static String constantsOf(String classFile) throws IOException {
        try (InputStream in = FakeKafka.class.getResourceAsStream(classFile)) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
