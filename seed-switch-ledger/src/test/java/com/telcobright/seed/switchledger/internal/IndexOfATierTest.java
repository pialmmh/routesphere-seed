package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Item (a) of the remote-fixes round · the indexes a view reads first — the partner's accounts ({@link Purchases}) and the tier's catalog
 * ({@link CatalogIndex}) — are made from whole-table reads, on the wire when the ledger is a server. Made OUTSIDE the map's lock: a
 * {@code ConcurrentHashMap.computeIfAbsent} that waits on the wire holds its bin (a forget, every other asker) and pins a virtual thread's
 * carrier. A read that fails is a LedgerFault in words (the admission's system fault), never a raw fault of the client.
 */
class IndexOfATierTest {

    private static final Set<String> HELD = Set.of(Books.ACCOUNT, Books.RESERVE, Books.PURCHASE, Books.PACKAGE, Books.PACKAGE_ITEM);

    @Test
    void aForgetWhileTheCatalogIsBeingMade_doesNotWaitForTheWire() throws Exception {
        SlowWire wire = new SlowWire();
        CatalogIndex catalog = new CatalogIndex(wire.books());

        assertThat(wire.forgetsWhileAsked(() -> catalog.of("btcl"), catalog::forget))
            .as("the catalog is made outside the map's lock: a forget meanwhile is not held by the wire").isTrue();
    }

    @Test
    void aForgetWhileThePartnerIndexIsBeingMade_doesNotWaitForTheWire() throws Exception {
        SlowWire wire = new SlowWire();
        Purchases purchases = new Purchases(wire.books());

        assertThat(wire.forgetsWhileAsked(() -> purchases.accountsOf(buyer()), purchases::forgetTheIndex))
            .as("the partner index is made outside the map's lock: a forget meanwhile is not held by the wire").isTrue();
    }

    @Test
    void aReadThatFails_isALedgerFaultInWords_namingTheTier() {
        Books books = new Books(ledgerWhoseTableReads(() -> { throw new IllegalStateException("the server did not answer GetAllEntities in 300 ms"); }),
            Clock.systemUTC(), HELD);

        assertThatThrownBy(() -> new CatalogIndex(books).of("btcl")).isInstanceOf(LedgerFault.class)
            .hasMessageContaining("btcl").hasMessageContaining("did not answer GetAllEntities in 300 ms");
        assertThatThrownBy(() -> new Purchases(books).accountsOf(buyer())).isInstanceOf(LedgerFault.class)
            .hasMessageContaining("btcl").hasMessageContaining("did not answer GetAllEntities in 300 ms");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** A server whose whole-table reads wait until the test lets them answer (empty tables). */
    private static final class SlowWire {
        private final CountDownLatch reading = new CountDownLatch(1), answer = new CountDownLatch(1);

        Books books() { return new Books(ledgerWhoseTableReads(this::waitForTheAnswer), Clock.systemUTC(), HELD); }

        /** {@code ask} on a virtual thread, held on the wire; meanwhile {@code forget}: true = it returned within a second. */
        boolean forgetsWhileAsked(Runnable ask, Runnable forget) throws InterruptedException {
            Thread asker = Thread.ofVirtual().start(ask);
            assertThat(reading.await(5, TimeUnit.SECONDS)).as("the index's first read is on the wire").isTrue();
            Thread forgetter = Thread.ofPlatform().daemon().start(forget);
            forgetter.join(1_000);
            boolean forgot = !forgetter.isAlive();
            answer.countDown();
            asker.join(5_000);
            return forgot;
        }

        private Map<Object, Object> waitForTheAnswer() {
            reading.countDown();
            try {
                answer.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of();
        }
    }

    /** A MemLedger whose whole-table reads are {@code read} (the wire of a slow or a silent server); nothing else is asked here. */
    private static MemLedger ledgerWhoseTableReads(Supplier<Map<Object, Object>> read) {
        return (MemLedger) Proxy.newProxyInstance(MemLedger.class.getClassLoader(), new Class<?>[] {MemLedger.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getAllEntities" -> read.get();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "a ledger whose table reads are the test's";
            default -> throw new UnsupportedOperationException(method.getName() + " is not asked here");
        });
    }

    private static LevelAdmission buyer() {
        Partner partner = new Partner();
        partner.setIdPartner(4);
        return new LevelAdmission(0, new Tenant("btcl"), partner, null);
    }
}
