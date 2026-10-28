package com.telcobright.seed.switchledger.api;

import com.telcobright.core.cache.CacheableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * C4 · One item of a catalog package as the ledger caches it — the row of {@code packageitem}, FLAT: what a purchase of the package opens,
 * ONE account per item ({@code quantity} of {@code idUom}). Its package is {@link LedgerPackage} ({@code id_package}).
 */
@Entity
@Table(name = "packageitem")
public class LedgerPackageItem implements CacheableEntity<Long, LedgerPackageItem> {

    @Id @Column(name = "id", nullable = false) private Long id;
    @Column(name = "id_package", nullable = false) private Long idPackage;
    @Column(name = "quantity", nullable = false, precision = 10, scale = 2) private BigDecimal quantity;
    @Column(name = "id_UOM", nullable = false, length = 20) private String idUom;
    @Column(name = "category", nullable = false) private Integer category;
    @Column(name = "prefix", nullable = false, length = 200) private String prefix;
    @Column(name = "description", nullable = false, length = 255) private String description;

    public LedgerPackageItem() {}

    @Override public Long getKey() { return id; }

    @Override public CacheableEntity<Long, LedgerPackageItem> applyUpdate(LedgerPackageItem delta) { return delta == null ? this : delta; }

    @Override public Class<LedgerPackageItem> getDeltaClass() { return LedgerPackageItem.class; }

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getIdPackage() { return idPackage; }
    public void setIdPackage(Long v) { idPackage = v; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal v) { quantity = v; }
    public String getIdUom() { return idUom; }
    public void setIdUom(String v) { idUom = v; }
    public Integer getCategory() { return category; }
    public void setCategory(Integer v) { category = v; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String v) { prefix = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }

    @Override public String toString() { return "LedgerPackageItem[" + id + " of package " + idPackage + ": " + quantity + " " + idUom + "]"; }
}
