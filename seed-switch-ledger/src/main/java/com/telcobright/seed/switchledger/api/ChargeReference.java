package com.telcobright.seed.switchledger.api;

import com.telcobright.core.cache.CacheableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * L2b item 2b · The DURABLE reference of a final charge (CHARGE_NOW, the SMS's debit): ONE row per charge reference in the tier's
 * {@code packageaccountcharge}, written IN THE SAME UNIT (one WAL record) as the debit it names, updated in the same unit as its refund.
 * After a restart the ledger loads it with the accounts: a repeated charge finds it and takes nothing; a refund finds its charge (and that
 * it was not refunded yet). The hold has its own durable reference already — its {@code packageaccountreserve} row.
 *
 * <p>No routesphere-core table fits: {@code packageaccountreserve} rows are the orphan reaper's input (a charge kept there with its amount
 * would be given back after the reaper's age), {@code acc_reserve} is not a cached entity. The DDL (MySQL and PostgreSQL) is in the round's
 * report; nothing creates it — a switch ledger registers this entity only when its root's ledger says so ({@code ledger.charge-references}).
 */
@Entity
@Table(name = "packageaccountcharge")
public class ChargeReference implements CacheableEntity<String, ChargeReference> {

    @Id
    @Column(name = "reference", nullable = false)
    private String reference;

    @Column(name = "id_packageaccount", nullable = false)
    private Long idPackageAccount;

    @Column(name = "uom", nullable = false)
    private String uom;

    @Column(name = "amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal amount;

    @Column(name = "balanceBefore", nullable = false, precision = 20, scale = 6)
    private BigDecimal balanceBefore;

    @Column(name = "balanceAfter", nullable = false, precision = 20, scale = 6)
    private BigDecimal balanceAfter;

    /** What a refund gave back of it (null = not refunded). */
    @Column(name = "refunded", precision = 20, scale = 6)
    private BigDecimal refunded;

    @Column(name = "time")
    private String time;

    public ChargeReference() {}

    public ChargeReference(String reference, Long idPackageAccount, String uom, BigDecimal amount, BigDecimal balanceBefore, BigDecimal balanceAfter,
                           BigDecimal refunded, String time) {
        this.reference = reference;
        this.idPackageAccount = idPackageAccount;
        this.uom = uom;
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.refunded = refunded;
        this.time = time;
    }

    /** The same charge, refunded {@code back} now (a new object: the cached row is never changed in place). */
    public ChargeReference refunded(BigDecimal back, String at) {
        return new ChargeReference(reference, idPackageAccount, uom, amount, balanceBefore, balanceAfter, back, at);
    }

    @Override public String getKey() { return reference; }

    /** Never used: the switch ledger writes whole post-images only. */
    @Override public CacheableEntity<String, ChargeReference> applyUpdate(ChargeReference delta) { return delta == null ? this : delta; }

    @Override public Class<ChargeReference> getDeltaClass() { return ChargeReference.class; }

    public String getReference() { return reference; }
    public void setReference(String v) { this.reference = v; }
    public Long getIdPackageAccount() { return idPackageAccount; }
    public void setIdPackageAccount(Long v) { this.idPackageAccount = v; }
    public String getUom() { return uom; }
    public void setUom(String v) { this.uom = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public BigDecimal getBalanceBefore() { return balanceBefore; }
    public void setBalanceBefore(BigDecimal v) { this.balanceBefore = v; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal v) { this.balanceAfter = v; }
    public BigDecimal getRefunded() { return refunded; }
    public void setRefunded(BigDecimal v) { this.refunded = v; }
    public String getTime() { return time; }
    public void setTime(String v) { this.time = v; }

    @Override public String toString() { return "ChargeReference[" + reference + " account " + idPackageAccount + " " + amount + " " + uom + " refunded " + refunded + "]"; }
}
