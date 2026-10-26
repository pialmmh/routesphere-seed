package com.telcobright.seed.switchledger.api;

import com.telcobright.core.cache.CacheableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * L2b item 4 · A package purchase as the ledger caches it — the row of {@code packagepurchase}, FLAT: rtc-domain's {@code PackagePurchase}
 * is the same table but no {@code CacheableEntity} and it carries JPA relations ({@code @ManyToOne Package pkg}, {@code @OneToMany
 * packageAccounts}) a MemLedger cannot load — so the ledger holds this twin, column for column ({@code id_package} as the package's id).
 * The third money entity (design §5.3): a purchase made through the ledger is ONE unit with its accounts; the partner's accounts and the
 * expiry an admission reads come from here (S4), not from prime-context's tree.
 */
@Entity
@Table(name = "packagepurchase")
public class LedgerPurchase implements CacheableEntity<Long, LedgerPurchase> {

    @Id @Column(name = "id", nullable = false) private Long id;
    @Column(name = "id_package", nullable = false) private Long idPackage;
    @Column(name = "id_Partner", nullable = false) private Long idPartner;
    @Column(name = "purchaseDate", nullable = false) private LocalDateTime purchaseDate;
    @Column(name = "expireDate", nullable = false) private LocalDateTime expireDate;
    @Column(name = "status", nullable = false, length = 50) private String status;
    @Column(name = "paid", nullable = false) private Long paid;
    @Column(name = "autoRenewalStatus", nullable = false) private Boolean autoRenewalStatus;
    @Column(name = "price", nullable = false, precision = 10, scale = 2) private BigDecimal price;
    @Column(name = "vat", nullable = false, precision = 10, scale = 2) private BigDecimal vat;
    @Column(name = "AIT", nullable = false, precision = 10, scale = 2) private BigDecimal ait;
    @Column(name = "priority", nullable = false) private Integer priority;
    @Column(name = "onSelectPriority") private Integer onSelectPriority;
    @Column(name = "discount", nullable = false) private Integer discount;

    public LedgerPurchase() {}

    /** A purchase made now: ACTIVE, paid, no renewal, no tax, no discount, priority 1 — the offer's price and validity. */
    public static LedgerPurchase made(long id, long packageId, long partnerId, LocalDateTime at, LocalDateTime expires, BigDecimal price) {
        LedgerPurchase p = new LedgerPurchase();
        p.id = id;
        p.idPackage = packageId;
        p.idPartner = partnerId;
        p.purchaseDate = at;
        p.expireDate = expires;
        p.status = "ACTIVE";
        p.paid = 1L;
        p.autoRenewalStatus = false;
        p.price = price;
        p.vat = BigDecimal.ZERO;
        p.ait = BigDecimal.ZERO;
        p.priority = 1;
        p.discount = 0;
        return p;
    }

    /** Expired at {@code now}: its expiry is not after now (an account of it pays nothing). */
    public boolean expiredAt(LocalDateTime now) { return expireDate != null && !expireDate.isAfter(now); }

    @Override public Long getKey() { return id; }
    @Override public CacheableEntity<Long, LedgerPurchase> applyUpdate(LedgerPurchase delta) { return delta == null ? this : delta; }
    @Override public Class<LedgerPurchase> getDeltaClass() { return LedgerPurchase.class; }

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getIdPackage() { return idPackage; }
    public void setIdPackage(Long v) { idPackage = v; }
    public Long getIdPartner() { return idPartner; }
    public void setIdPartner(Long v) { idPartner = v; }
    public LocalDateTime getPurchaseDate() { return purchaseDate; }
    public void setPurchaseDate(LocalDateTime v) { purchaseDate = v; }
    public LocalDateTime getExpireDate() { return expireDate; }
    public void setExpireDate(LocalDateTime v) { expireDate = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public Long getPaid() { return paid; }
    public void setPaid(Long v) { paid = v; }
    public Boolean getAutoRenewalStatus() { return autoRenewalStatus; }
    public void setAutoRenewalStatus(Boolean v) { autoRenewalStatus = v; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal v) { price = v; }
    public BigDecimal getVat() { return vat; }
    public void setVat(BigDecimal v) { vat = v; }
    public BigDecimal getAit() { return ait; }
    public void setAit(BigDecimal v) { ait = v; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer v) { priority = v; }
    public Integer getOnSelectPriority() { return onSelectPriority; }
    public void setOnSelectPriority(Integer v) { onSelectPriority = v; }
    public Integer getDiscount() { return discount; }
    public void setDiscount(Integer v) { discount = v; }

    @Override public String toString() { return "LedgerPurchase[" + id + " partner " + idPartner + " package " + idPackage + " until " + expireDate + "]"; }
}
