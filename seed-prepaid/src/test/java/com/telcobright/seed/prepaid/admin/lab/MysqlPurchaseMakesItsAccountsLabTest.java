package com.telcobright.seed.prepaid.admin.lab;

/** L2c item 4 on the local MySQL (127.0.0.1:3306; databases l2clabbtcl, l2clabres2, l2clabres3 — the root's word a valid tenant id (ARCH-0100) — dropped at the end). */
class MysqlPurchaseMakesItsAccountsLabTest extends PurchaseMakesItsAccountsScenario {
    @Override
    protected PurchaseLabEngine engine() { return new MysqlPurchaseLab(); }
}
