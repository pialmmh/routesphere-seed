package com.telcobright.seed.switchledger.api;

import com.telcobright.core.cache.CacheableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * §15.3 (the owner, 2026-10-30) · A PRODUCT as the ledger caches it — ONE flat row of the TABLE {@code product_catalog}, written ONLY
 * through the cached ledger (the portal's product roads; the Odoo import through the same roads, {@code source = odoo}). {@code productKey}
 * is the key the package items carry in {@code packageitem.prefix}: the Odoo variant's id as text for the ad, the dialing prefix for the
 * call (a call needs no product row). {@code kind} is the service (voice, sms, ad, wifi-internet, …), {@code media} + {@code seconds} the
 * ad's attributes, {@code serviceGroupId} the revenue stream ({@code enumservicegroup}). Rules in {@code ProductRules}.
 *
 * <p>The Odoo variant is {@code odooProductId} → column {@code odoo_product_id}, NEVER a field named {@code id}: mem-ledger takes any
 * field named {@code id} as the entity's key (its EntityMetadataExtractor, the last one wins), so a field {@code id} beside the real key
 * would make the load read the Odoo id as the key (a portal product, null: the load fails) and an update write {@code WHERE id = …}
 * (2026-10-30, found by ProductRulesTest; guarded by {@code KnownEntitiesHaveOneKeyTest}). The WIRE keeps the name {@code id}.
 */
@Entity
@Table(name = "product_catalog")
public class LedgerProduct implements CacheableEntity<String, LedgerProduct> {

    @Id @Column(name = "product_key", nullable = false, length = 200) private String productKey;
    @Column(name = "odoo_product_id") private Long odooProductId;
    @Column(name = "code", length = 64) private String code;
    @Column(name = "name", length = 255) private String name;
    @Column(name = "kind", length = 32) private String kind;
    @Column(name = "media", length = 16) private String media;
    @Column(name = "seconds") private Integer seconds;
    @Column(name = "uom", length = 20) private String uom;
    @Column(name = "active") private Boolean active;
    @Column(name = "source", length = 16) private String source;
    @Column(name = "service_group_id") private Integer serviceGroupId;

    public LedgerProduct() {}

    @Override public String getKey() { return productKey; }

    @Override public CacheableEntity<String, LedgerProduct> applyUpdate(LedgerProduct delta) { return delta == null ? this : delta; }

    @Override public Class<LedgerProduct> getDeltaClass() { return LedgerProduct.class; }

    public String getProductKey() { return productKey; }
    public void setProductKey(String v) { productKey = v; }
    public Long getOdooProductId() { return odooProductId; }
    public void setOdooProductId(Long v) { odooProductId = v; }
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
    public String getMedia() { return media; }
    public void setMedia(String v) { media = v; }
    public String getSource() { return source; }
    public void setSource(String v) { source = v; }
    public Integer getServiceGroupId() { return serviceGroupId; }
    public void setServiceGroupId(Integer v) { serviceGroupId = v; }

    @Override public String toString() { return "LedgerProduct[" + productKey + " " + kind + (media == null ? "" : " " + media) + (seconds == null ? "" : " " + seconds + "s") + " group " + serviceGroupId + " " + name + "]"; }
}
