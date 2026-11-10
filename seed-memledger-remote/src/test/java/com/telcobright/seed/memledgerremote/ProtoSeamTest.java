package com.telcobright.seed.memledgerremote;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.telcobright.seed.memledgerremote.wire.ApplyReply;
import com.telcobright.seed.memledgerremote.wire.ApplyRequest;
import com.telcobright.seed.memledgerremote.wire.HealthReply;
import com.telcobright.seed.memledgerremote.wire.MemLedgerGrpc;
import com.telcobright.seed.memledgerremote.wire.MemLedgerProto;
import com.telcobright.seed.memledgerremote.wire.Op;
import com.telcobright.seed.memledgerremote.wire.WalEntry;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-0117 item 1 — the wire's frozen seam from day one (a change is the architect's): the service's name and its eight methods (mem-ledger's
 * own surface: {@code Apply} = {@code execCrud(WALEntryBatch)}, the only write), the fields of {@code ApplyRequest} and {@code WalEntry}
 * with their numbers, the three operations a batch may carry (f739824 accepts exactly INSERT, UPDATE, DELETE), the answer's four fields,
 * and no float or double anywhere (a row's money travels inside {@code entity_json} as decimal text).
 */
class ProtoSeamTest {

    private static final List<String> METHODS = List.of("Apply", "GetEntity", "GetAllEntities", "CacheStatistics", "RegisteredDatabases", "ReloadNewRows", "AddDatabase", "Health");

    @Test
    void theServiceIsNamed_andHasExactlyTheEightMethodsOfMemLedgersSurface() {
        ServiceDescriptor service = MemLedgerGrpc.getServiceDescriptor();
        assertThat(service.getName()).isEqualTo("telcobright.memledger.v1.MemLedger");
        assertThat(service.getMethods()).extracting(MethodDescriptor::getBareMethodName).containsExactlyInAnyOrderElementsOf(METHODS);
    }

    @Test
    void applyIsUnary_theWholeTableIsAStreamFromTheServer_everyOtherVerbIsUnary() {
        for (MethodDescriptor<?, ?> method : MemLedgerGrpc.getServiceDescriptor().getMethods()) {
            MethodDescriptor.MethodType expected = "GetAllEntities".equals(method.getBareMethodName()) ? MethodDescriptor.MethodType.SERVER_STREAMING : MethodDescriptor.MethodType.UNARY;
            assertThat(method.getType()).as(method.getBareMethodName()).isEqualTo(expected);
        }
    }

    @Test
    void theBatchAndItsEntriesKeepTheirFieldNumbers() {
        assertThat(numbered(ApplyRequest.getDescriptor())).containsExactly("1 transaction_id", "2 entries");
        assertThat(numbered(WalEntry.getDescriptor())).containsExactly("1 db_name", "2 entity_name", "3 op", "4 entity_id", "5 entity_json");
        assertThat(WalEntry.getDescriptor().findFieldByName("entity_id").getType()).as("an id is text: a number, a call's uuid, a product key").isEqualTo(FieldDescriptor.Type.STRING);
        assertThat(WalEntry.getDescriptor().findFieldByName("entity_json").getType()).isEqualTo(FieldDescriptor.Type.STRING);
        assertThat(ApplyRequest.getDescriptor().findFieldByName("entries").isRepeated()).isTrue();
    }

    @Test
    void aBatchCarriesInsertUpdateOrDelete_nothingElse() {
        assertThat(Op.getDescriptor().getValues()).extracting(v -> v.getNumber() + " " + v.getName())
            .containsExactly("0 OP_UNSPECIFIED", "1 INSERT", "2 UPDATE", "3 DELETE");
    }

    @Test
    void theAnswersKeepTheirFieldNumbers() {
        assertThat(numbered(ApplyReply.getDescriptor())).containsExactly("1 applied", "2 message", "3 refusal_code", "4 already_applied");
        assertThat(numbered(HealthReply.getDescriptor())).containsExactly("1 ready", "2 wal_lag", "3 databases", "4 persistence", "5 version");
        assertThat(numbered(MemLedgerProto.getDescriptor().findMessageTypeByName("Database"))).containsExactly("1 name", "2 loaded");
    }

    @Test
    void noFloatOrDoubleAnywhereOnTheWire() {
        for (Descriptor message : MemLedgerProto.getDescriptor().getMessageTypes()) {
            for (FieldDescriptor field : message.getFields()) {
                assertThat(field.getType()).as(message.getName() + "." + field.getName()).isNotIn(FieldDescriptor.Type.FLOAT, FieldDescriptor.Type.DOUBLE);
            }
        }
    }

    private static List<String> numbered(Descriptor message) {
        return message.getFields().stream().map(f -> f.getNumber() + " " + f.getName()).toList();
    }
}
