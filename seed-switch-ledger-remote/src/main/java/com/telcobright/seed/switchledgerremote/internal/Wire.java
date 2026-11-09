package com.telcobright.seed.switchledgerremote.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerRefusal;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;
import com.telcobright.seed.switchledger.api.Credit;
import com.telcobright.seed.switchledgerremote.wire.Ack;
import com.telcobright.seed.switchledgerremote.wire.Balance;
import com.telcobright.seed.switchledgerremote.wire.Buyer;
import com.telcobright.seed.switchledgerremote.wire.Credited;
import com.telcobright.seed.switchledgerremote.wire.Hold;
import com.telcobright.seed.switchledgerremote.wire.Move;
import com.telcobright.seed.switchledgerremote.wire.Refusal;
import com.telcobright.seed.switchledgerremote.wire.RefundRequest;
import com.telcobright.seed.switchledgerremote.wire.ReleaseRequest;
import com.telcobright.seed.switchledgerremote.wire.Settled;
import com.telcobright.seed.switchledgerremote.wire.Settlement;
import com.telcobright.seed.switchledgerremote.wire.Tier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The wire's words for the port's own types — both ways, so the client and the server adapter read one translation: a level ⇄ {@code Tier}
 * (the six fields of design §2, plus what a settlement reads beside them), money ⇄ decimal text, a reserve's answer ⇄ {@code Hold}, a
 * settlement ⇄ {@code Settled}, a credit ⇄ {@code Credited}, a refusal of the ledger's own ⇄ {@code Refusal} (carried with status OK).
 */
public final class Wire {

    private Wire() {}

    // ── money and time: text, never float ───────────────────────────────────

    public static String text(BigDecimal money) { return money == null ? "" : money.toPlainString(); }

    public static BigDecimal decimal(String text) { return text == null || text.isEmpty() ? null : new BigDecimal(text); }

    public static String text(LocalDateTime at) { return at == null ? "" : at.toString(); }

    public static LocalDateTime dateTime(String text) { return text == null || text.isEmpty() ? null : LocalDateTime.parse(text); }

    public static String nullIfEmpty(String text) { return text == null || text.isEmpty() ? null : text; }

    public static String orEmpty(String text) { return text == null ? "" : text; }

    // ── the tier ────────────────────────────────────────────────────────────

    /** The six fields a money move reads from the level (design §2). */
    public static Tier tierOf(LevelAdmission level) {
        Tier.Builder tier = Tier.newBuilder()
            .setDbName(orEmpty(level.getDbName()))
            .setLevelIndex(level.getLevelIndex())
            .setPackageAccountId(level.getPackageAccountId() == null ? 0 : level.getPackageAccountId())
            .setPartnerId(level.getPartner() == null ? 0 : level.getPartnerId())
            .setDebitReference(orEmpty(level.getDebitReference()));
        String uom = level.getUom() != null ? level.getUom() : level.getAccount() == null ? null : level.getAccount().getUom();
        return tier.setUom(orEmpty(uom)).build();
    }

    /** The level as the engine reads it, rebuilt from the tier's six fields (no account when the tier names none: the refusal NO_ACCOUNT). */
    public static LevelAdmission levelOf(Tier tier) {
        Partner partner = new Partner();
        partner.setIdPartner((int) tier.getPartnerId());
        PackageAccount account = null;
        if (tier.getPackageAccountId() != 0) {
            account = new PackageAccount();
            account.setId(tier.getPackageAccountId());
            account.setUom(nullIfEmpty(tier.getUom()));
        }
        LevelAdmission level = new LevelAdmission(tier.getLevelIndex(), new Tenant(tier.getDbName()), partner, account);
        level.setUom(nullIfEmpty(tier.getUom()));
        level.setDebitReference(nullIfEmpty(tier.getDebitReference()));
        level.setReservedAmount(BigDecimal.ZERO);
        return level;
    }

    /** The level with what its settlement reads beside the tier: everything it reserved, in how many windows. */
    public static LevelAdmission levelOf(Tier tier, String totalReserved, int reservationCount) {
        LevelAdmission level = levelOf(tier);
        BigDecimal reserved = decimal(totalReserved);
        if (reserved != null && reserved.signum() != 0) level.setReservedAmount(reserved);
        for (int i = 0; i < reservationCount; i++) level.incrementReservationCount();
        return level;
    }

    /** A buyer: its tier's schema and its partner (no account). */
    public static LevelAdmission levelOf(Buyer buyer) {
        return levelOf(Tier.newBuilder().setDbName(buyer.getDbName()).setPartnerId(buyer.getPartnerId()).setLevelIndex(buyer.getLevelIndex()).build());
    }

    public static Buyer buyerOf(LevelAdmission level) {
        return Buyer.newBuilder().setDbName(orEmpty(level.getDbName())).setPartnerId(level.getPartner() == null ? 0 : level.getPartnerId()).setLevelIndex(level.getLevelIndex()).build();
    }

    // ── the moves ───────────────────────────────────────────────────────────

    public static Move moveOf(LevelAdmission level, BigDecimal amount, String reference) {
        return Move.newBuilder().setTier(tierOf(level)).setAmount(text(amount)).setReference(orEmpty(reference)).build();
    }

    public static Settlement settlementOf(LevelAdmission level, BigDecimal charged) {
        return Settlement.newBuilder().setTier(tierOf(level)).setCharged(text(charged)).setTotalReserved(text(TierSettlement.reservedOf(level)))
            .setReservationCount(level.getReservationCount()).build();
    }

    public static ReleaseRequest releaseOf(LevelAdmission level, String why) {
        return ReleaseRequest.newBuilder().setTier(tierOf(level)).setWhy(orEmpty(why)).setTotalReserved(text(TierSettlement.reservedOf(level)))
            .setReservationCount(level.getReservationCount()).build();
    }

    public static RefundRequest refundOf(LevelAdmission level, BigDecimal amount, String why) {
        return RefundRequest.newBuilder().setTier(tierOf(level)).setAmount(text(amount)).setWhy(orEmpty(why)).setTotalReserved(text(TierSettlement.reservedOf(level)))
            .setReservationCount(level.getReservationCount()).build();
    }

    // ── a reserve's answer ──────────────────────────────────────────────────

    /** The port's answer on the wire: empty = this account cannot pay ({@code held = false}, nothing else said). */
    public static Hold holdOf(Optional<Reservation> answer) {
        if (answer.isEmpty()) return Hold.newBuilder().setHeld(false).build();
        Reservation r = answer.get();
        return Hold.newBuilder().setHeld(true).setRepeated(r.repeated()).setAccount(r.account() == null ? 0 : r.account()).setUom(orEmpty(r.uom()))
            .setReserved(text(r.reserved())).setBalanceBefore(text(r.balanceBefore())).setBalanceAfter(text(r.balanceAfter())).build();
    }

    public static Hold refusedHold(LedgerRefusal refusal) {
        return Hold.newBuilder().setHeld(false).setRefusalCode(refusal.code()).setRefusalMessage(orEmpty(refusal.getMessage())).build();
    }

    /** The wire's answer as the port's: a refusal of the ledger's own is thrown as today; not held = empty. */
    public static Optional<Reservation> reservationOf(Hold hold) {
        if (!hold.getRefusalCode().isEmpty()) throw new LedgerRefusal(hold.getRefusalCode(), messageOf(hold.getRefusalMessage(), hold.getRefusalCode()));
        if (!hold.getHeld()) return Optional.empty();
        return Optional.of(new Reservation(hold.getAccount() == 0 ? null : hold.getAccount(), nullIfEmpty(hold.getUom()), decimal(hold.getReserved()),
            decimal(hold.getBalanceBefore()), decimal(hold.getBalanceAfter()), hold.getRepeated()));
    }

    // ── a settlement ────────────────────────────────────────────────────────

    public static Settled settledOf(TierSettlement s) {
        return Settled.newBuilder().setLevelIndex(s.levelIndex()).setReserved(text(s.reserved())).setCharged(text(s.charged())).setReturned(text(s.returned()))
            .setBalanceAfter(text(s.balanceAfter())).setClosed(s.closed()).setNote(orEmpty(s.note())).build();
    }

    public static Settled refusedSettled(LedgerRefusal refusal) { return Settled.newBuilder().setRefusal(refusalOf(refusal)).build(); }

    public static TierSettlement settlementOf(Settled s) {
        throwIfRefused(s.hasRefusal(), s.getRefusal());
        return new TierSettlement(s.getLevelIndex(), decimal(s.getReserved()), decimal(s.getCharged()), decimal(s.getReturned()), decimal(s.getBalanceAfter()), s.getClosed(),
            nullIfEmpty(s.getNote()));
    }

    // ── acks, balances ──────────────────────────────────────────────────────

    public static Ack ack() { return Ack.getDefaultInstance(); }

    public static Ack refusedAck(LedgerRefusal refusal) { return Ack.newBuilder().setRefusal(refusalOf(refusal)).build(); }

    public static void acked(Ack ack) { throwIfRefused(ack.hasRefusal(), ack.getRefusal()); }

    public static Balance balanceOf(Optional<BigDecimal> balance) {
        return balance.map(b -> Balance.newBuilder().setPresent(true).setBalance(text(b)).build()).orElse(Balance.newBuilder().setPresent(false).build());
    }

    public static Balance refusedBalance(LedgerRefusal refusal) { return Balance.newBuilder().setRefusal(refusalOf(refusal)).build(); }

    public static Optional<BigDecimal> balanceOf(Balance b) {
        throwIfRefused(b.hasRefusal(), b.getRefusal());
        return b.getPresent() ? Optional.ofNullable(decimal(b.getBalance())) : Optional.empty();
    }

    // ── credits ─────────────────────────────────────────────────────────────

    public static Credited creditedOf(LedgerPort.Credited c) {
        return Credited.newBuilder().setAccountId(c.accountId()).setBefore(text(c.before())).setAfter(text(c.after())).setPurchaseId(c.purchaseId()).build();
    }

    public static Credited creditedOf(Credit.Credited c) {
        return Credited.newBuilder().setAccountId(c.accountId()).setBefore(text(c.before())).setAfter(text(c.after())).setPurchaseId(c.purchaseId()).build();
    }

    public static Credited refusedCredited(LedgerRefusal refusal) { return Credited.newBuilder().setRefusal(refusalOf(refusal)).build(); }

    public static LedgerPort.Credited portCreditedOf(Credited c) {
        throwIfRefused(c.hasRefusal(), c.getRefusal());
        return new LedgerPort.Credited(c.getAccountId(), decimal(c.getBefore()), decimal(c.getAfter()), c.getPurchaseId());
    }

    public static Credit.Credited doorCreditedOf(Credited c) {
        throwIfRefused(c.hasRefusal(), c.getRefusal());
        return new Credit.Credited(c.getAccountId(), decimal(c.getBefore()), decimal(c.getAfter()), c.getPurchaseId());
    }

    // ── refusals ────────────────────────────────────────────────────────────

    public static Refusal refusalOf(LedgerRefusal refusal) { return Refusal.newBuilder().setCode(refusal.code()).setMessage(orEmpty(refusal.getMessage())).build(); }

    /** A refusal carried in a reply is thrown as the port throws it. */
    public static void throwIfRefused(boolean present, Refusal refusal) {
        if (present) throw new LedgerRefusal(refusal.getCode(), messageOf(refusal.getMessage(), refusal.getCode()));
    }

    /** {@code LedgerRefusal} prefixes its code to the message; the server sent "code: message" — the prefix is not repeated. */
    private static String messageOf(String message, String code) {
        String prefix = code + ": ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }
}
