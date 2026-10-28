package com.telcobright.seed.switchledger.api;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * C2b · The account row as the seed's ledger caches it: rtc-domain's {@code PackageAccount} and the two columns of the same table that
 * TelcoREST writes — {@code creditLimit} (a postpaid account's limit) and {@code isSelected} — so EVERY column of {@code packageaccount}
 * goes through the cached ledger, none by SQL beside it (the owner, 2026-10-28: "do as you recommend"). The live voice keeps caching the
 * plain class: its rows never carry the two, so its writes never overwrite them. mem-ledger maps a persistent parent's columns first
 * (its round inherited-columns), so this row is the parent's columns and these two.
 *
 * <p>Every copy of an account the ledger writes keeps its class and all its fields — a move of the balance never empties the limit.
 * The columns must be in every database the ledger caches (prime-context's changelog; TelcoREST's MySQL has them).
 */
@Entity
@Table(name = "packageaccount")
public class LedgerAccount extends PackageAccount {

    @Column(name = "creditLimit", precision = 20, scale = 6)
    private BigDecimal creditLimit;

    @Column(name = "isSelected", nullable = false)
    private boolean isSelected;

    public LedgerAccount() {}

    public BigDecimal getCreditLimit() { return creditLimit; }

    public void setCreditLimit(BigDecimal creditLimit) { this.creditLimit = creditLimit; }

    public boolean isSelected() { return isSelected; }

    public void setSelected(boolean selected) { this.isSelected = selected; }
}
