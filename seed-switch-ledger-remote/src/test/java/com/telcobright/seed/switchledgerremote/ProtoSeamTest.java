package com.telcobright.seed.switchledgerremote;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.telcobright.seed.switchledgerremote.wire.SwitchLedgerGrpc;
import com.telcobright.seed.switchledgerremote.wire.SwitchLedgerProto;
import com.telcobright.seed.switchledgerremote.wire.Tier;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-0116 item 1 — the wire's frozen seam from day one (ratification by the architect only): the service's name and its eighteen
 * methods (design §3.3), the six fields of {@code Tier} with their numbers 1–6 in the order of design §2, money as decimal text (no float
 * or double anywhere in the file), and the unit as a stream both ways (lock, moves answered as they will be, commit — one WAL record).
 */
class ProtoSeamTest {

    private static final List<String> METHODS = List.of("Reserve", "Charge", "Settle", "Release", "Refund", "Unit", "Credit", "Reparent", "CapAt",
        "BalanceOf", "Purchase", "AccountsOf", "PurchasesOf", "Catalog", "AccountById", "AccountOfPurchase", "HeldAccounts", "Health");

    @Test
    void theServiceIsNamed_andHasExactlyTheEighteenMethodsOfTheDesign() {
        ServiceDescriptor service = SwitchLedgerGrpc.getServiceDescriptor();
        assertThat(service.getName()).isEqualTo("telcobright.switchledger.v1.SwitchLedger");
        assertThat(service.getMethods()).extracting(MethodDescriptor::getBareMethodName).containsExactlyInAnyOrderElementsOf(METHODS);
    }

    @Test
    void theTierHasTheSixFieldsOfTheLevel_numberedOneToSix_inTheDesignsOrder() {
        List<FieldDescriptor> fields = Tier.getDescriptor().getFields();
        assertThat(fields).extracting(FieldDescriptor::getNumber).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(fields).extracting(FieldDescriptor::getName).containsExactly("db_name", "level_index", "package_account_id", "partner_id", "uom", "debit_reference");
        assertThat(fields).extracting(FieldDescriptor::getType).containsExactly(FieldDescriptor.Type.STRING, FieldDescriptor.Type.INT32, FieldDescriptor.Type.INT64,
            FieldDescriptor.Type.INT64, FieldDescriptor.Type.STRING, FieldDescriptor.Type.STRING);
    }

    @Test
    void moneyIsDecimalText_noFloatOrDoubleAnywhereOnTheWire() {
        for (Descriptor message : SwitchLedgerProto.getDescriptor().getMessageTypes()) {
            for (FieldDescriptor field : message.getFields()) {
                assertThat(field.getType()).as(message.getName() + "." + field.getName()).isNotIn(FieldDescriptor.Type.FLOAT, FieldDescriptor.Type.DOUBLE);
            }
        }
        for (String money : List.of("amount", "reserved", "balance_before", "balance_after")) {
            assertThat(fieldType("Hold", money.equals("amount") ? "reserved" : money)).isEqualTo(FieldDescriptor.Type.STRING);
        }
        assertThat(fieldType("Move", "amount")).isEqualTo(FieldDescriptor.Type.STRING);
        assertThat(fieldType("CapRequest", "ceiling")).isEqualTo(FieldDescriptor.Type.STRING);
    }

    @Test
    void theUnitIsAStreamBothWays_everyOtherVerbIsUnary() {
        for (MethodDescriptor<?, ?> method : SwitchLedgerGrpc.getServiceDescriptor().getMethods()) {
            MethodDescriptor.MethodType expected = "Unit".equals(method.getBareMethodName()) ? MethodDescriptor.MethodType.BIDI_STREAMING : MethodDescriptor.MethodType.UNARY;
            assertThat(method.getType()).as(method.getBareMethodName()).isEqualTo(expected);
        }
    }

    private static FieldDescriptor.Type fieldType(String message, String field) {
        return SwitchLedgerProto.getDescriptor().findMessageTypeByName(message).findFieldByName(field).getType();
    }
}
