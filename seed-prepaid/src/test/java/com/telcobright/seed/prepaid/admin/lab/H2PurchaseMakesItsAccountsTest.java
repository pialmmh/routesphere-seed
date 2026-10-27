package com.telcobright.seed.prepaid.admin.lab;

/** L2c item 4 on H2 in MySQL mode (in process, nothing listens): the account key AUTO_INCREMENT, as the lab MySQL's twin. */
class H2PurchaseMakesItsAccountsTest extends PurchaseMakesItsAccountsScenario {
    @Override
    protected PurchaseLabEngine engine() { return new H2PurchaseLab(); }
}
