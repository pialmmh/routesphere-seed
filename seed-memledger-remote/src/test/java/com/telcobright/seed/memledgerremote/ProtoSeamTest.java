package com.telcobright.seed.memledgerremote;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.telcobright.seed.memledgerremote.api.Capabilities;
import com.telcobright.seed.memledgerremote.wire.ApplyReply;
import com.telcobright.seed.memledgerremote.wire.ApplyRequest;
import com.telcobright.seed.memledgerremote.wire.HealthReply;
import com.telcobright.seed.memledgerremote.wire.MemLedgerGrpc;
import com.telcobright.seed.memledgerremote.wire.MemLedgerProto;
import com.telcobright.seed.memledgerremote.wire.Op;
import com.telcobright.seed.memledgerremote.wire.ReloadReply;
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
 *
 * <p>ARCH-0118 item 1 — two writers on one instance, ADDITIVE in both directions (every old number unchanged): the rows a batch read
 * travel with it ({@code ApplyRequest.expects = 3}, one {@code Expectation} per row), the reload says the ghosts the server took out
 * ({@code ReloadReply.ghosts_taken_out = 5}), Health says what the server does ({@code HealthReply.capabilities = 6}).
 *
 * <p>ARCH-0119 — concurrency, ADDITIVE again (every number above unchanged; the owner's word of 2026-11-10 made the WiFi architect the
 * owner of this change): road 1, a fourth operation {@code PATCH} with its {@code changes}, {@code guards} and {@code insert_if_absent}
 * on {@code WalEntry} (6, 7, 8) and the answer's {@code results}, {@code failed_entry}, {@code failed_row_json} (5, 6, 7); road 2,
 * three more methods ({@code ReadForUpdate}, {@code ApplyLocked}, {@code Unlock}); {@code ApplyRequest} itself is unchanged.
 */
class ProtoSeamTest {

    private static final List<String> METHODS = List.of("Apply", "GetEntity", "GetAllEntities", "CacheStatistics", "RegisteredDatabases", "ReloadNewRows", "AddDatabase", "Health");

    /** ARCH-0119 road 2 · The lease's three methods, beside the eight. */
    private static final List<String> LEASE_METHODS = List.of("ReadForUpdate", "ApplyLocked", "Unlock");

    @Test
    void theServiceIsNamed_andHasExactlyTheEightMethodsOfMemLedgersSurface() {
        ServiceDescriptor service = MemLedgerGrpc.getServiceDescriptor();
        assertThat(service.getName()).isEqualTo("telcobright.memledger.v1.MemLedger");
        assertThat(service.getMethods()).extracting(MethodDescriptor::getBareMethodName).as("the eight methods of mem-ledger's surface are all still there").containsAll(METHODS);
        List<String> all = new java.util.ArrayList<>(METHODS);
        all.addAll(LEASE_METHODS);
        assertThat(service.getMethods()).extracting(MethodDescriptor::getBareMethodName).as("the eight, and since ARCH-0119 the lease's three — no other").containsExactlyInAnyOrderElementsOf(all);
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
        assertThat(numbered(ApplyRequest.getDescriptor())).as("ApplyRequest is as ARCH-0118 left it: a lock travels in a METHOD of its own (ApplyLocked) — an older server would ignore a new field and write unlocked")
            .containsExactly("1 transaction_id", "2 entries", "3 expects");
        assertThat(numbered(WalEntry.getDescriptor())).containsExactly("1 db_name", "2 entity_name", "3 op", "4 entity_id", "5 entity_json",
            "6 changes", "7 guards", "8 insert_if_absent");
        assertThat(WalEntry.getDescriptor().findFieldByName("entity_id").getType()).as("an id is text: a number, a call's uuid, a product key").isEqualTo(FieldDescriptor.Type.STRING);
        assertThat(WalEntry.getDescriptor().findFieldByName("entity_json").getType()).isEqualTo(FieldDescriptor.Type.STRING);
        assertThat(ApplyRequest.getDescriptor().findFieldByName("entries").isRepeated()).isTrue();
    }

    @Test
    void aBatchCarriesInsertUpdateOrDelete_nothingElse() {
        assertThat(Op.getDescriptor().getValues()).extracting(v -> v.getNumber() + " " + v.getName())
            .as("the three operations keep their numbers; since ARCH-0119 a fourth, PATCH, which the SERVER turns into an UPDATE or an INSERT before mem-ledger sees it")
            .containsExactly("0 OP_UNSPECIFIED", "1 INSERT", "2 UPDATE", "3 DELETE", "4 PATCH");
    }

    @Test
    void theAnswersKeepTheirFieldNumbers() {
        assertThat(numbered(ApplyReply.getDescriptor())).containsExactly("1 applied", "2 message", "3 refusal_code", "4 already_applied",
            "5 results", "6 failed_entry", "7 failed_row_json");
        assertThat(numbered(HealthReply.getDescriptor())).containsExactly("1 ready", "2 wal_lag", "3 databases", "4 persistence", "5 version", "6 capabilities");
        assertThat(numbered(ReloadReply.getDescriptor())).containsExactly("1 added", "2 scanned", "3 failures", "4 kept_not_read", "5 ghosts_taken_out");
        assertThat(numbered(MemLedgerProto.getDescriptor().findMessageTypeByName("Database"))).containsExactly("1 name", "2 loaded");
    }

    /** ARCH-0118 · A row as the client READ it travels with the batch that changes what it read; {@code present = false} = it saw none. */
    @Test
    void theRowsABatchReadTravelWithIt_oneExpectationPerRow() {
        Descriptor expectation = MemLedgerProto.getDescriptor().findMessageTypeByName("Expectation");
        assertThat(expectation).as("the message Expectation").isNotNull();
        assertThat(numbered(expectation)).containsExactly("1 db_name", "2 entity_name", "3 entity_id", "4 present", "5 entity_json");
        assertThat(expectation.findFieldByName("present").getType()).isEqualTo(FieldDescriptor.Type.BOOL);
        assertThat(expectation.findFieldByName("entity_json").getType()).as("the row as JSON of its fields, as WalEntry's").isEqualTo(FieldDescriptor.Type.STRING);
        FieldDescriptor expects = ApplyRequest.getDescriptor().findFieldByName("expects");
        assertThat(expects.isRepeated()).isTrue();
        assertThat(expects.getMessageType().getFullName()).isEqualTo("telcobright.memledger.v1.Expectation");
    }

    /** ARCH-0118 · What the server does, said in Health: a word per capability; the reload's ghosts as a count. */
    @Test
    void healthSaysTheServersCapabilities_asWords_andTheReloadCountsTheGhostsItTookOut() {
        FieldDescriptor capabilities = HealthReply.getDescriptor().findFieldByName("capabilities");
        assertThat(capabilities.isRepeated()).isTrue();
        assertThat(capabilities.getType()).isEqualTo(FieldDescriptor.Type.STRING);
        assertThat(ReloadReply.getDescriptor().findFieldByName("ghosts_taken_out").getType()).isEqualTo(FieldDescriptor.Type.INT32);
        assertThat(List.of(Capabilities.GHOSTS, Capabilities.EXPECT)).as("the words both sides send and read").containsExactly("ghosts", "expect");
    }

    /** ARCH-0119 road 1 · A PATCH says what to change and what must hold; a number is decimal TEXT here too. */
    @Test
    void aPatchCarriesItsChangesAndItsGuards_andTheAnswerCarriesTheRows() {
        assertThat(numbered(message("Change"))).containsExactly("1 field", "2 kind", "3 value", "4 from_field");
        assertThat(numbered(message("Guard"))).containsExactly("1 kind", "2 field", "3 value");
        assertThat(numbered(message("RowResult"))).containsExactly("1 db_name", "2 entity_name", "3 entity_id", "4 inserted", "5 entity_json");
        assertThat(valuesOf("ChangeKind")).containsExactly("0 CHANGE_UNSPECIFIED", "1 ADD", "2 SET", "3 COPY", "4 MIN");
        assertThat(valuesOf("GuardKind")).containsExactly("0 GUARD_UNSPECIFIED", "1 FIELD_GE", "2 FIELD_EQ", "3 FIELD_LE");
        assertThat(message("Change").findFieldByName("value").getType()).as("a decimal is text, never a float").isEqualTo(FieldDescriptor.Type.STRING);
        assertThat(message("Guard").findFieldByName("value").getType()).isEqualTo(FieldDescriptor.Type.STRING);
        assertThat(WalEntry.getDescriptor().findFieldByName("changes").isRepeated()).isTrue();
        assertThat(WalEntry.getDescriptor().findFieldByName("guards").isRepeated()).isTrue();
        assertThat(ApplyReply.getDescriptor().findFieldByName("results").getMessageType().getFullName()).isEqualTo("telcobright.memledger.v1.RowResult");
    }

    /** ARCH-0119 road 2 · A lock is asked by the caller's own id, for rows, a lease and a wait; a batch names the lock it is written under. */
    @Test
    void aLockIsAskedForRows_andABatchNamesTheLockItIsWrittenUnder() {
        assertThat(numbered(message("LockRequest"))).containsExactly("1 lock_id", "2 rows", "3 lease_ms", "4 max_wait_ms", "5 client");
        assertThat(numbered(message("LockReply"))).containsExactly("1 granted", "2 refusal_code", "3 message", "4 token", "5 lease_ms", "6 rows");
        assertThat(numbered(message("LockedRow"))).containsExactly("1 key", "2 found", "3 entity_json");
        assertThat(numbered(message("ApplyLockedRequest"))).containsExactly("1 lock_id", "2 token", "3 batch");
        assertThat(numbered(message("UnlockRequest"))).containsExactly("1 lock_id");
        assertThat(numbered(message("UnlockReply"))).containsExactly("1 released");
        assertThat(message("LockRequest").findFieldByName("rows").getMessageType().getFullName()).as("a row is named as GetEntity names it").isEqualTo("telcobright.memledger.v1.EntityKey");
        assertThat(message("ApplyLockedRequest").findFieldByName("batch").getMessageType().getFullName()).as("the batch is Apply's own").isEqualTo("telcobright.memledger.v1.ApplyRequest");
        assertThat(List.of(Capabilities.PATCH, Capabilities.LEASE)).as("the words both sides send and read").containsExactly("patch", "lease");
    }

    private static Descriptor message(String name) {
        Descriptor found = MemLedgerProto.getDescriptor().findMessageTypeByName(name);
        assertThat(found).as("the message " + name).isNotNull();
        return found;
    }

    private static List<String> valuesOf(String enumName) {
        return MemLedgerProto.getDescriptor().findEnumTypeByName(enumName).getValues().stream().map(v -> v.getNumber() + " " + v.getName()).toList();
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
