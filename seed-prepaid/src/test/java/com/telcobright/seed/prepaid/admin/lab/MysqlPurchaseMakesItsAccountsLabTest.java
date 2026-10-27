package com.telcobright.seed.prepaid.admin.lab;

/** L2c item 4 on the local MySQL (127.0.0.1:3306; databases l2c_lab_btcl, l2c_lab_res_2, l2c_lab_res_3, dropped at the end). */
class MysqlPurchaseMakesItsAccountsLabTest extends PurchaseMakesItsAccountsScenario {
    @Override
    protected PurchaseLabEngine engine() { return new MysqlPurchaseLab(); }
}
