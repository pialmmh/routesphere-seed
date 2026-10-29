package com.telcobright.seed.switchledger.api;

import com.telcobright.core.cache.CacheableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * §15.3 · A SERVICE GROUP as the ledger caches it — a row of {@code enumservicegroup}: the revenue stream a charge belongs to (1 DomesticICX,
 * 2 IntlOutICX, 3 IntlInICX, …, 30 AdView, 101 the WiFi lane). Read-only: loaded at the start and with a tier added, never written. A
 * product names its group; the walk keeps the request's group; the charge record carries it (postpaid invoices per group, never mixed —
 * the owner, 2026-10-29).
 */
@Entity
@Table(name = "enumservicegroup")
public class LedgerServiceGroup implements CacheableEntity<Integer, LedgerServiceGroup> {

    @Id @Column(name = "id", nullable = false) private Integer id;
    @Column(name = "name", length = 100) private String name;

    public LedgerServiceGroup() {}

    @Override public Integer getKey() { return id; }

    @Override public CacheableEntity<Integer, LedgerServiceGroup> applyUpdate(LedgerServiceGroup delta) { return delta == null ? this : delta; }

    @Override public Class<LedgerServiceGroup> getDeltaClass() { return LedgerServiceGroup.class; }

    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }

    @Override public String toString() { return "LedgerServiceGroup[" + id + " " + name + "]"; }
}
