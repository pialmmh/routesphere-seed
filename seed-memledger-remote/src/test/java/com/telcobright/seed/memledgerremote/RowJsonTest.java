package com.telcobright.seed.memledgerremote;

import com.telcobright.core.wal.WALEntry;
import com.telcobright.core.wal.WALEntryBatch;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.seed.memledgerremote.api.RowJson;
import com.telcobright.seed.memledgerremote.api.WireBatch;
import com.telcobright.seed.memledgerremote.wire.ApplyRequest;
import com.telcobright.seed.memledgerremote.wire.Op;
import com.telcobright.seed.switchledger.api.LedgerAccount;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledger.internal.LedgerEntities;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ARCH-0117 item 2 (the seed's half) — a row crosses the wire whole: every field of every entity class the ledger can register comes
 * back as it went, a decimal with its scale, an empty field empty; a batch comes back as the batch the engine built, its keys in their
 * own types; what cannot be read is refused in mem-ledger's own shape of words, naming the entry.
 */
class RowJsonTest {

    private static final Function<String, Class<?>> KNOWN = name -> LedgerEntities.known().stream().filter(k -> k.name().equals(name)).findFirst().<Class<?>>map(LedgerEntities.Known::type).orElse(null);

    @Test
    void everyFieldOfEveryKnownEntity_comesBackAsItWent() throws Exception {
        for (LedgerEntities.Known known : LedgerEntities.known()) {
            Object row = filled(known.type());
            Object back = RowJson.rowOf(known.type(), RowJson.textOf(row));
            assertThat(back).as(known.name()).isExactlyInstanceOf(known.type());
            assertThat(valuesOf(back)).as(known.name() + ": every field, type and scale").isEqualTo(valuesOf(row));
            assertThat(valuesOf(RowJson.rowOf(known.type(), RowJson.textOf(known.type().getDeclaredConstructor().newInstance()))))
                .as(known.name() + ": a row as its constructor leaves it").isEqualTo(valuesOf(known.type().getDeclaredConstructor().newInstance()));
        }
    }

    @Test
    void moneyIsDecimalTextWithItsScale_neverANumberThatCouldLoseIt() {
        LedgerAccount account = account(11, "100.000000");
        String json = RowJson.textOf(account);
        assertThat(json).contains("\"balanceAfter\":\"100.000000\"").contains("\"id\":11").contains("\"creditLimit\":null");
        assertThat(RowJson.rowOf(LedgerAccount.class, json).getBalanceAfter()).isEqualTo(new BigDecimal("100.000000"));
    }

    @Test
    void aFieldTheClassDoesNotHave_orAValueThatIsNotItsType_isRefusedNamingBoth() {
        assertThatThrownBy(() -> RowJson.rowOf(LedgerAccount.class, "{\"id\":11,\"colour\":\"red\"}"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LedgerAccount has no field 'colour'");
        assertThatThrownBy(() -> RowJson.rowOf(LedgerAccount.class, "{\"id\":\"eleven\"}"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PackageAccount.id is a Long");
        assertThatThrownBy(() -> RowJson.keyOf(LedgerAccount.class, "eleven")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is no key of LedgerAccount");
    }

    @Test
    void aBatchComesBackAsTheEngineBuiltIt_keysInTheirOwnTypes_andAnUnreadableEntryIsRefusedNamingIt() {
        PackageAccountReserve hold = new PackageAccountReserve();
        hold.setChannelCallUuid("c-1#L0");
        hold.setIdPackageAccount(11L);
        hold.setReserveUnit(new BigDecimal("10.000000"));
        WALEntryBatch batch = new WALEntryBatch("SEED_one", List.of(
            entry("res_2", Books.ACCOUNT, WALEntry.OperationType.UPDATE, 11L, account(11, "90.000000")),
            entry("res_2", Books.RESERVE, WALEntry.OperationType.INSERT, "c-1#L0", hold),
            entry("res_2", Books.RESERVE, WALEntry.OperationType.DELETE, "c-0#L0", null)));

        ApplyRequest wire = WireBatch.requestOf(batch);
        WALEntryBatch back = WireBatch.batchOf(wire, KNOWN);

        assertThat(wire.getTransactionId()).isEqualTo("SEED_one");
        assertThat(wire.getEntriesList()).extracting(e -> e.getOp() + " " + e.getEntityId()).containsExactly("UPDATE 11", "INSERT c-1#L0", "DELETE c-0#L0");
        assertThat(wire.getEntries(2).getEntityJson()).as("a DELETE carries no image").isEmpty();
        assertThat(back.getTransactionId()).isEqualTo("SEED_one");
        assertThat(back.getEntries()).extracting(WALEntry::getOperationType).containsExactly(WALEntry.OperationType.UPDATE, WALEntry.OperationType.INSERT, WALEntry.OperationType.DELETE);
        assertThat(back.getEntries()).extracting(WALEntry::getEntityId).containsExactly(11L, "c-1#L0", "c-0#L0");
        assertThat(back.getEntries().get(0).getEntityData()).isExactlyInstanceOf(LedgerAccount.class);
        assertThat(((LedgerAccount) back.getEntries().get(0).getEntityData()).getBalanceAfter()).isEqualTo(new BigDecimal("90.000000"));
        assertThat(back.getEntries().get(2).getEntityData()).isNull();

        ApplyRequest broken = wire.toBuilder().setEntries(1, wire.getEntries(1).toBuilder().setEntityJson("{\"reserveUnit\":")).build();
        assertThatThrownBy(() -> WireBatch.batchOf(broken, KNOWN)).isInstanceOf(WireBatch.Unreadable.class)
            .hasMessageStartingWith("Batch refused, nothing changed: entry 2 of 3 (res_2.PackageAccountReserve id=c-1#L0)");
        assertThat(WireBatch.batchOf(wire.toBuilder().setEntries(0, wire.getEntries(0).toBuilder().setOp(Op.OP_UNSPECIFIED)).build(), KNOWN).getEntries().get(0).getOperationType())
            .as("an operation the wire does not know is mem-ledger's to refuse").isNull();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static WALEntry entry(String db, String entity, WALEntry.OperationType op, Object id, Object image) {
        WALEntry e = new WALEntry();
        e.setDbName(db);
        e.setEntityType(entity);
        e.setOperationType(op);
        e.setEntityId(id);
        e.setEntityData(image);
        return e;
    }

    private static LedgerAccount account(long id, String balance) {
        LedgerAccount a = new LedgerAccount();
        a.setId(id);
        a.setIdpackagePurchase(1L);
        a.setName("OTH_ea-" + id);
        a.setUom("OTH_ea");
        a.setLastAmount(BigDecimal.ZERO);
        a.setBalanceBefore(new BigDecimal(balance));
        a.setBalanceAfter(new BigDecimal(balance));
        return a;
    }

    /** A row of the class with every field set to a value of its type (a decimal with a scale that a number would lose). */
    private static Object filled(Class<?> type) throws Exception {
        Object row = type.getDeclaredConstructor().newInstance();
        int n = 1;
        for (Field f : fieldsOf(type)) {
            Class<?> t = f.getType();
            Object v = t == String.class ? "text-" + n : t == BigDecimal.class ? new BigDecimal(n + ".500000")
                : t == Long.class || t == long.class ? (Object) (3_000_000_000_000_000L + n) : t == Integer.class || t == int.class ? (Object) n
                : t == Boolean.class || t == boolean.class ? (Object) Boolean.TRUE : t == LocalDateTime.class ? LocalDateTime.of(2030, 1, 2, 3, 4, n % 60) : null;
            assertThat(v).as(type.getSimpleName() + "." + f.getName() + " is a " + t.getSimpleName() + ": a type the wire must learn").isNotNull();
            f.set(row, v);
            n++;
        }
        return row;
    }

    private static Map<String, Object> valuesOf(Object row) throws Exception {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (Field f : fieldsOf(row.getClass())) values.put(f.getName(), f.get(row));
        return values;
    }

    private static List<Field> fieldsOf(Class<?> type) {
        List<Field> fields = new java.util.ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                f.setAccessible(true);
                fields.add(f);
            }
        }
        return fields.stream().collect(Collectors.toList());
    }
}
