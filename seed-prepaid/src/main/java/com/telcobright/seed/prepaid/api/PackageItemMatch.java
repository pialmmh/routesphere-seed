package com.telcobright.seed.prepaid.api;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.sessionflow.spi.LedgerPort;

/**
 * §15 · What the walk matched for ONE package account of the payer: the item of its package that carries one of the request's product
 * keys (the FIRST key that hit, best first), and the product of that key where the ledger caches products (null where it does not — the
 * call's prefix needs none). The lane's {@code itemRate} prices the request on it: a 30-second video on the "video 30 s" item is one view.
 */
public record PackageItemMatch(PackageAccount account, LedgerPort.Item item, LedgerPort.Product product, String key) {

    /** The matched item's unit — the account's unit, as a purchase opened it. */
    public String uom() { return item.uom(); }

    /** The product's seconds attribute, or null (an image, a call). */
    public Integer seconds() { return product == null ? null : product.seconds(); }

    /** The product's kind (video, image, call), or null where no product is cached. */
    public String kind() { return product == null ? null : product.kind(); }
}
