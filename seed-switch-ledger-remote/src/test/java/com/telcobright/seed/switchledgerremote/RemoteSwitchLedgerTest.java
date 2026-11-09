package com.telcobright.seed.switchledgerremote;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerRefusal;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.api.Credit;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import com.telcobright.seed.switchledgerremote.api.RemoteSwitchLedger;
import com.telcobright.seed.switchledgerremote.dependencies.RemoteLedgerSettings;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Item 2's reds: a fault on the wire is a {@link LedgerFault} (the pipeline says BILLING_SYSTEM_ERROR), never a balance cause; a cap
 * repeated with one reference caps twice (a cap is a ceiling, not a recorded move — the WiFi clock's contract); an account the schema
 * does not hold is an {@code IllegalArgumentException} on the cap door, as the embedded {@link Credit} throws it.
 */
class RemoteSwitchLedgerTest {

    private LoopbackLedger loop;

    @AfterEach
    void stop() { if (loop != null) loop.close(); }

    private static LevelAdmission tier(String db, Long accountId) {
        Partner partner = new Partner();
        partner.setIdPartner(701);
        PackageAccount account = null;
        if (accountId != null) {
            account = new PackageAccount();
            account.setId(accountId);
            account.setUom("OTH_ea");
        }
        LevelAdmission level = new LevelAdmission(0, new Tenant(db), partner, account);
        level.setReservedAmount(BigDecimal.ZERO);
        return level;
    }

    @Test
    void aWireFaultIsALedgerFault_neverABalanceCause() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { closedPort = s.getLocalPort(); }
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", closedPort).usePlaintext().build();
        try (RemoteSwitchLedger nobody = RemoteSwitchLedger.over(channel, new RemoteLedgerSettings("127.0.0.1:" + closedPort, 500, "LOOPBACK_TOKEN"), "unused")) {
            Throwable t = catchThrowable(() -> nobody.reserve(tier("res_2", 11L), new BigDecimal("10"), "c-1#L0"));
            assertThat(t).as("no listener = a fault of the billing system, never a refusal").isInstanceOf(LedgerFault.class).isNotInstanceOf(LedgerRefusal.class);
            assertThat(t.getMessage()).contains("127.0.0.1:" + closedPort).contains("reserve");
            assertThat(nobody.slowestAnswerMs()).isEqualTo(500);
        }
    }

    @Test
    void aBadBearerIsALedgerFaultNamingTheVariable() {
        loop = LoopbackLedger.serve(LedgerLab.open().schema("res_2").account("res_2", 11, 1, "OTH_ea", "100"), "res_2");
        try (RemoteSwitchLedger stranger = loop.anotherClient("not-the-token")) {
            Throwable t = catchThrowable(() -> stranger.balanceOf(tier("res_2", 11L)));
            assertThat(t).isInstanceOf(LedgerFault.class);
            assertThat(t.getMessage()).contains("LOOPBACK_TOKEN").doesNotContain("not-the-token").doesNotContain(loop.token);
        }
    }

    @Test
    void theRoundTripMovesMoney_andRefusalsKeepTheirCode() {
        loop = LoopbackLedger.serve(LedgerLab.open().schema("res_2").account("res_2", 11, 1, "OTH_ea", "100"), "res_2");
        LevelAdmission level = tier("res_2", 11L);
        Reservation held = loop.client.reserve(level, new BigDecimal("10"), "c-1#L0").orElseThrow();
        assertThat(held.reserved()).isEqualByComparingTo("10");
        assertThat(held.balanceAfter()).isEqualByComparingTo("90");
        level.setDebitReference("c-1#L0");
        level.setReservedAmount(held.reserved());
        level.incrementReservationCount();
        TierSettlement done = loop.client.settle(level, new BigDecimal("4"));
        assertThat(done.charged()).isEqualByComparingTo("4");
        assertThat(done.balanceAfter()).isEqualByComparingTo("96");
        assertThat(loop.lab.cachedBalance("res_2", 11)).isEqualByComparingTo("96");
        assertThat(loop.client.balanceOf(tier("res_2", 11L)).orElseThrow()).isEqualByComparingTo("96");

        Throwable t = catchThrowable(() -> loop.client.reserve(tier("res_2", 999L), new BigDecimal("10"), "c-2#L0"));
        assertThat(t).isInstanceOf(LedgerRefusal.class);
        assertThat(((LedgerRefusal) t).code()).isEqualTo(MemLedgerPort.ACCOUNT_NOT_FOUND);
        assertThat(loop.client.reserve(tier("res_2", 11L), new BigDecimal("500"), "c-3#L0")).as("cannot pay = empty, as the port says it").isEmpty();
        assertThat(loop.client.tierLoaded("res_2")).isTrue();
    }

    @Test
    void aCapRepeatedWithOneReferenceCapsTwice() {
        loop = LoopbackLedger.serve(LedgerLab.open().schema("res_2").account("res_2", 11, 1, "OTH_ea", "100"), "res_2");
        Credit.Credited first = loop.client.credit().capAt("res_2", 11, new BigDecimal("40"), "clock-tick");
        assertThat(first.before()).isEqualByComparingTo("100");
        assertThat(first.after()).isEqualByComparingTo("40");
        loop.client.credit().credit("res_2", 11, new BigDecimal("30"), "topup-1");
        assertThat(loop.lab.cachedBalance("res_2", 11)).isEqualByComparingTo("70");

        Credit.Credited again = loop.client.credit().capAt("res_2", 11, new BigDecimal("40"), "clock-tick");
        assertThat(again.before()).as("the same reference caps again: a cap is a ceiling, not a recorded move").isEqualByComparingTo("70");
        assertThat(again.after()).isEqualByComparingTo("40");
        assertThat(loop.lab.cachedBalance("res_2", 11)).isEqualByComparingTo("40");
    }

    @Test
    void aCapOnAnAccountTheSchemaDoesNotHoldIsAnIllegalArgument() {
        loop = LoopbackLedger.serve(LedgerLab.open().schema("res_2").account("res_2", 11, 1, "OTH_ea", "100"), "res_2");
        Throwable t = catchThrowable(() -> loop.client.credit().capAt("res_2", 999, new BigDecimal("40"), "clock-tick"));
        assertThat(t).isInstanceOf(IllegalArgumentException.class).isNotInstanceOf(LedgerFault.class);
        assertThat(t.getMessage()).contains("999").contains("not held").contains("res_2");
    }
}
