package com.telcobright.seed.prepaid.admin.lab;

import java.util.List;

/**
 * L2c · The ad lane's money tables as the MySQL (and H2-in-MySQL-mode) twins of prime-context's PostgreSQL lines — mem-ledger's lab twins
 * (identity → AUTO_INCREMENT, numeric → DECIMAL, timestamp(6) → DATETIME(6)). No reference table: the ad lane does not adopt
 * {@code packageaccountcharge} (the owner, 2026-10-27: an ad view is ONE call, held once, no retry).
 */
final class MysqlTwins {

    private MysqlTwins() {}

    static final String PACKAGEACCOUNT =
        "CREATE TABLE %s.packageaccount (id_packageaccount BIGINT NOT NULL AUTO_INCREMENT, balanceAfter DECIMAL(20,6) NOT NULL, balanceBefore DECIMAL(20,6) NOT NULL, id_PackagePurchase BIGINT NOT NULL, lastAmount DECIMAL(20,6) NOT NULL, name VARCHAR(255), uom VARCHAR(255) NOT NULL, PRIMARY KEY (id_packageaccount))";
    static final String PACKAGEACCOUNTRESERVE =
        "CREATE TABLE %s.packageaccountreserve (channel_call_uuid VARCHAR(255) NOT NULL, id_packageaccount BIGINT NOT NULL, id_PackagePurchase BIGINT NOT NULL, name VARCHAR(255), reserveUnit DECIMAL(20,6), time VARCHAR(255), uom VARCHAR(255), PRIMARY KEY (channel_call_uuid))";
    static final String PACKAGEPURCHASE =
        "CREATE TABLE %s.packagepurchase (id BIGINT NOT NULL AUTO_INCREMENT, AIT DECIMAL(10,2) NOT NULL, autoRenewalStatus BOOLEAN NOT NULL, discount INT NOT NULL, expireDate DATETIME(6) NOT NULL, id_Partner BIGINT NOT NULL, onSelectPriority INT, paid BIGINT NOT NULL, price DECIMAL(10,2) NOT NULL, priority INT NOT NULL, purchaseDate DATETIME(6) NOT NULL, status VARCHAR(50) NOT NULL, vat DECIMAL(10,2) NOT NULL, id_package BIGINT NOT NULL UNIQUE, PRIMARY KEY (id))";

    /** The three tables of one tier (its name in place of {@code %s}). */
    static List<String> of(String tier) {
        return List.of(PACKAGEACCOUNT, PACKAGEACCOUNTRESERVE, PACKAGEPURCHASE).stream().map(ddl -> String.format(ddl, tier)).toList();
    }
}
