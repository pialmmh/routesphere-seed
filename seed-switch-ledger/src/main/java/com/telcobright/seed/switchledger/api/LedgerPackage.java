package com.telcobright.seed.switchledger.api;

import com.telcobright.core.cache.CacheableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * C4 · A package of the catalog as the ledger caches it — the row of {@code package}, FLAT (rtc-domain's {@code Package} carries JPA
 * relations a MemLedger cannot load). The owner, 2026-10-28 ("go as you recommend best"): the catalog goes through the cached ledger too,
 * so every write of the prepaid model takes ONE path — and a purchase's package is checked in it before the WAL (the database's
 * {@code packagepurchase.id_package} references it). Its items are {@link LedgerPackageItem}; {@code validity} is in seconds.
 */
@Entity
@Table(name = "package")
public class LedgerPackage implements CacheableEntity<Long, LedgerPackage> {

    @Id @Column(name = "id", nullable = false) private Long id;
    @Column(name = "name", nullable = false, length = 255) private String name;
    @Column(name = "description") private String description;
    @Column(name = "basePrice", precision = 10, scale = 2) private BigDecimal basePrice;
    @Column(name = "vatPercent", nullable = false, precision = 5, scale = 2) private BigDecimal vatPercent;
    @Column(name = "createDate", nullable = false) private LocalDateTime createDate;
    @Column(name = "validity", nullable = false) private Long validity;
    @Column(name = "aitPercent", nullable = false, precision = 5, scale = 2) private BigDecimal aitPercent;
    @Column(name = "activeStatus", nullable = false) private Boolean activeStatus;

    public LedgerPackage() {}

    @Override public Long getKey() { return id; }

    @Override public CacheableEntity<Long, LedgerPackage> applyUpdate(LedgerPackage delta) { return delta == null ? this : delta; }

    @Override public Class<LedgerPackage> getDeltaClass() { return LedgerPackage.class; }

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public BigDecimal getBasePrice() { return basePrice; }
    public void setBasePrice(BigDecimal v) { basePrice = v; }
    public BigDecimal getVatPercent() { return vatPercent; }
    public void setVatPercent(BigDecimal v) { vatPercent = v; }
    public LocalDateTime getCreateDate() { return createDate; }
    public void setCreateDate(LocalDateTime v) { createDate = v; }
    public Long getValidity() { return validity; }
    public void setValidity(Long v) { validity = v; }
    public BigDecimal getAitPercent() { return aitPercent; }
    public void setAitPercent(BigDecimal v) { aitPercent = v; }
    public Boolean getActiveStatus() { return activeStatus; }
    public void setActiveStatus(Boolean v) { activeStatus = v; }

    @Override public String toString() { return "LedgerPackage[" + id + " " + name + (Boolean.TRUE.equals(activeStatus) ? "" : " inactive") + "]"; }
}
