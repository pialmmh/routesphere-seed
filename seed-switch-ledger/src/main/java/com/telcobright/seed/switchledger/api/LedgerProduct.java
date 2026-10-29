package com.telcobright.seed.switchledger.api;

import com.telcobright.core.cache.CacheableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * §15 (the owner, 2026-10-29: "I want to use existing product entity and attributes management for odoo … an ad product shall have the
 * attributes like 10 sec, 30 sec") · A PRODUCT as the ledger caches it — ONE flat row of {@code product_catalog}: on a tier whose schema
 * lives in the Odoo database it is a VIEW over Odoo's product tables ({@code product_product} × {@code product_template} × the variant's
 * attribute values; prime-context's changelog makes it), elsewhere a plain table. Read-only: loaded at the start and with a tier added,
 * never written by the ledger, never re-read by a reload of new rows (a product is config, not money).
 *
 * <p>{@code productKey} is the key the package items carry in {@code packageitem.prefix}: the Odoo product's id as text for the ad, the
 * dialing prefix for the call (a call needs no product row: its key is the prefix itself). {@code kind} and {@code seconds} are the
 * attributes the lanes read (video 30 s, image); {@code uom} the unit the item's quantity counts (views, minutes).
 */
@Entity
@Table(name = "product_catalog")
public class LedgerProduct implements CacheableEntity<String, LedgerProduct> {

    @Id @Column(name = "product_key", nullable = false, length = 200) private String productKey;
    @Column(name = "id") private Long id;
    @Column(name = "code", length = 64) private String code;
    @Column(name = "name", length = 255) private String name;
    @Column(name = "kind", length = 20) private String kind;
    @Column(name = "seconds") private Integer seconds;
    @Column(name = "uom", length = 20) private String uom;
    @Column(name = "active") private Boolean active;

    public LedgerProduct() {}

    @Override public String getKey() { return productKey; }

    @Override public CacheableEntity<String, LedgerProduct> applyUpdate(LedgerProduct delta) { return delta == null ? this : delta; }

    @Override public Class<LedgerProduct> getDeltaClass() { return LedgerProduct.class; }

    public String getProductKey() { return productKey; }
    public void setProductKey(String v) { productKey = v; }
    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public String getCode() { return code; }
    public void setCode(String v) { code = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { kind = v; }
    public Integer getSeconds() { return seconds; }
    public void setSeconds(Integer v) { seconds = v; }
    public String getUom() { return uom; }
    public void setUom(String v) { uom = v; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean v) { active = v; }

    @Override public String toString() { return "LedgerProduct[" + productKey + " " + kind + (seconds == null ? "" : " " + seconds + "s") + " " + name + "]"; }
}
