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
    void aNumbersTextHasABound_andALongerOneIsRefusedBeforeItIsRead() {
        String at = "1." + "2".repeat(RowJson.NUMBER_TEXT_MAX - 2), over = at + "2";
        assertThat(at).hasSize(64);
        LedgerAccount taken = RowJson.rowOf(LedgerAccount.class, "{\"balanceAfter\":\"" + at + "\"}");
        assertThat(taken.getBalanceAfter()).as("a decimal of exactly %s characters is TAKEN (mutant N2: one character too strict)", RowJson.NUMBER_TEXT_MAX).isEqualByComparingTo(at);

        assertThatThrownBy(() -> RowJson.rowOf(LedgerAccount.class, "{\"balanceAfter\":\"" + over + "\"}"))
            .as("routesphere's gate: a decimal's text is parsed at the cost of its length's SQUARE — a million digits took 41.8 s of a worker (mutant N1: no bound)")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PackageAccount.balanceAfter is a number of 65 characters: a number's text is 64 at most (it was not read)");

        String noNumber = "x".repeat(2_000);
        assertThatThrownBy(() -> RowJson.rowOf(LedgerAccount.class, "{\"balanceAfter\":\"" + noNumber + "\"}"))
            .as("the length is judged BEFORE the parse: a long text that is no number is refused for its LENGTH, not by the parser (mutant N3: the text is parsed first), "
                + "and the refusal never carries the text")
            .hasMessageContaining("is a number of 2000 characters").hasMessageNotContaining("xxxxxxxx");

        assertThatThrownBy(() -> RowJson.rowOf(LedgerAccount.class, "{\"balanceAfter\":" + "9".repeat(65) + "}"))
            .as("the same bound for a number written as a JSON number (mutant N4: only a quoted number is bounded)").hasMessageContaining("is a number of 65 characters");
        assertThatThrownBy(() -> RowJson.rowOf(LedgerAccount.class, "{\"idpackagePurchase\":" + "9".repeat(65) + "}"))
            .as("… and for a whole-number field").hasMessageContaining("PackageAccount.idpackagePurchase is a number of 65 characters");
        assertThatThrownBy(() -> RowJson.keyOf(LedgerAccount.class, "9".repeat(65)))
            .as("… and for a row's key read from its text (mutant N5: a key has no bound)").hasMessageContaining("the key of a LedgerAccount is a number of 65 characters");
        assertThat(RowJson.keyOf(PackageAccountReserve.class, "c".repeat(300))).as("a TEXT key is a text, not a number: any length").isEqualTo("c".repeat(300));

        assertThatThrownBy(() -> RowJson.rowOf(LedgerAccount.class, "{\"balanceAfter\":\"" + "1O".repeat(30) + "\"}"))
            .as("a short text that is no number is refused as before, by the parser").hasMessageContaining("is not one");
        assertThatThrownBy(() -> RowJson.keyOf(LedgerAccount.class, "k".repeat(60)))
            .hasMessageContaining("is no key of LedgerAccount");

        assertThatThrownBy(() -> RowJson.rowOf(com.telcobright.seed.switchledger.api.LedgerPurchase.class, "{\"expireDate\":\"" + "z".repeat(2_000) + "\"}"))
            .as("a refusal says a long value SHORT: never a megabyte of it in an answer and a log line (mutant N6: the value is echoed whole)")
            .hasMessageContaining("is not one").hasMessageContaining("characters)")
            .satisfies(refusal -> assertThat(refusal.getMessage().length()).isLessThan(300));
    }

    @Test
    void aShortTextThatNamesAVastNumber_neverBecomesARow() {
        for (String vast : List.of("1E+2147483647", "12E+2147483646", "-1E+2147483647", "1E+1000000", "1E-1000000", "1E+64", "0E-2147483647", "0E-64", "0E-63")) {
            assertThatThrownBy(() -> RowJson.rowOf(LedgerAccount.class, "{\"balanceAfter\":\"" + vast + "\"}"))
                .as("stream-tester's A-0153: %s is a short text, and as a row its plain form is written on every read — the account's reads failed for memory, "
                    + "PostgreSQL silently held 0.000000 and the next start never came up (mutant V1: only the TEXT has a bound)", vast)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PackageAccount.balanceAfter is a number whose plain text has ")
                .hasMessageContaining("a number's text is 64 at most")
                .satisfies(refusal -> assertThat(refusal.getMessage().length()).as("the refusal itself is short").isLessThan(300));
        }
        assertThatThrownBy(() -> RowJson.rowOf(LedgerAccount.class, "{\"balanceAfter\":\"1E+2147483647\"}"))
            .as("the digits are counted in a LONG (mutant V3: in an int the count overflows to nothing and the number is taken)")
            .hasMessageContaining("plain text has 2147483648 characters (1E+2147483647)");

        assertThat(RowJson.rowOf(LedgerAccount.class, "{\"balanceAfter\":\"1E+63\"}").getBalanceAfter())
            .as("64 characters exactly are TAKEN (mutant V2: one too strict)").isEqualByComparingTo(new BigDecimal("1E+63"));
        assertThat(RowJson.rowOf(LedgerAccount.class, "{\"balanceAfter\":\"" + "9".repeat(57) + ".999999\"}").getBalanceAfter().precision())
            .as("a text of exactly 64 characters with 63 digits: taken").isEqualTo(63);
        assertThat(RowJson.rowOf(LedgerAccount.class, "{\"balanceAfter\":\"0E+1000000\"}").getBalanceAfter().toPlainString())
            .as("a zero with an exponent ABOVE zero is the one digit 0 (mutant V4: such a zero is counted as its exponent's zeros)").isEqualTo("0");
        assertThat(RowJson.rowOf(LedgerAccount.class, "{\"balanceAfter\":\"0E-62\"}").getBalanceAfter().signum())
            .as("a zero of 64 characters (0.000…0) is taken; 0E-63, 0E-64 and 0E-2147483647 above are not (mutant V6: every zero is taken — routesphere's "
                + "measure: the plain form of 0E-2147483647 is too large to build)").isZero();
        for (String[] counted : new String[][] {{"1E+3", "4"}, {"0.001", "4"}, {"0E-5", "6"}, {"0E+5", "1"}, {"12.5", "3"}, {"-12.5", "3"}, {"100", "3"}, {"0", "1"}}) {
            BigDecimal number = new BigDecimal(counted[0]);
            assertThat(RowJson.plainDigitsOf(number)).as("the digits of %s, counted without building its text", counted[0])
                .isEqualTo(Long.parseLong(counted[1])).isEqualTo(number.toPlainString().chars().filter(Character::isDigit).count());
            assertThat(RowJson.plainLengthOf(number)).as("the characters of %s: its digits, its point, its sign", counted[0]).isEqualTo(number.toPlainString().length());
        }
        assertThat(RowJson.rowOf(LedgerAccount.class, "{\"balanceAfter\":\"12345678901234.123456\"}").getBalanceAfter())
            .as("the widest money the prepaid model holds").isEqualByComparingTo("12345678901234.123456");

        assertThatThrownBy(() -> RowJson.rowOf(LedgerAccount.class, "{\"balanceAfter\":1E+100}"))
            .as("the same for a number written as a JSON number: 1E+100 is a finite number to the JSON reader, 101 digits as a row (mutant V5: only a quoted "
                + "number is judged)")
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whose plain text has 101 characters");
    }

    /**
     * THE EDGE TABLE of "a number's plain text is 64 characters at most": a value, and the length of its plain text. The SAME rows are
     * pinned in recall-db's RowsTheDatabaseTakesTest (routesphere, 2026-11-11: two copies of one rule drift unless both pin one table; measured there
     * in jshell on JDK 21, the length of toPlainString against a count in a long). Change both tables or neither.
     */
    private static final String[][] EDGE = {
        {"0E-62", "64"}, {"0E-63", "65"},
        {"1E-62", "64"}, {"1E-63", "65"},
        {"-1E-61", "64"}, {"-1E-62", "65"},
        {"1E+63", "64"}, {"1E+64", "65"},
        {"-1E+62", "64"}, {"-1E+63", "65"},
        {"1E+2147483647", "2147483648"}, {"12E+2147483646", "2147483648"}, {"0E-2147483647", "2147483649"}, {"-1E-2147483647", "2147483650"}, {"0E+2147483647", "1"},
    };

    @Test
    void theEdgeTable_isTheGuardsOwn() {
        for (String[] edge : EDGE) {
            BigDecimal number = new BigDecimal(edge[0]);
            long length = Long.parseLong(edge[1]);
            assertThat(RowJson.plainLengthOf(number)).as("the plain text of %s, counted without building it", edge[0]).isEqualTo(length);
            if (length <= RowJson.NUMBER_TEXT_MAX) {
                assertThat(RowJson.requirePlainShort(number, "a number")).as("%s is written in %s characters: taken", edge[0], length).isSameAs(number);
                assertThat(number.toPlainString()).hasSize((int) length);
            } else {
                assertThatThrownBy(() -> RowJson.requirePlainShort(number, "a number")).as("%s would be written in %s characters: refused", edge[0], length)
                    .hasMessageContaining("whose plain text has " + length + " characters");
            }
        }
        assertThat(RowJson.NUMBER_TEXT_MAX).isEqualTo(64);
    }

    @Test
    void whatIsWrittenIsAlwaysATextAReaderTakes() {
        // stream-tester's A-0154 F1: the bound counted DIGITS, the reader counts CHARACTERS — 1E-63 (64 digits) was taken and then
        // written as 65 characters, and every reader refused the row it was given.
        for (String taken : List.of("1E+63", "-1E+62", "0E-62", "1E-62", "-1E-61", "9".repeat(64), "-" + "9".repeat(56) + ".999999")) {
            LedgerAccount account = account(11, "0");
            account.setBalanceAfter(RowJson.rowOf(LedgerAccount.class, "{\"balanceAfter\":\"" + taken + "\"}").getBalanceAfter());
            String written = RowJson.textOf(account);
            assertThat(account.getBalanceAfter().toPlainString()).as("%s is taken, and its written text is exactly at the bound", taken).hasSize(64);
            assertThat(RowJson.rowOf(LedgerAccount.class, written).getBalanceAfter())
                .as("the row as it is WRITTEN is read back by the same reader (%s)", taken).isEqualByComparingTo(new BigDecimal(taken));
        }
        for (String oneTooLong : List.of("1E-63", "0E-63", "-1E+63", "-1E-62")) {
            assertThatThrownBy(() -> RowJson.rowOf(LedgerAccount.class, "{\"balanceAfter\":\"" + oneTooLong + "\"}"))
                .as("%s would be WRITTEN as 65 characters, which no reader takes: it never becomes a row (mutant V8: the point is not counted; V9: the sign "
                    + "is not counted)", oneTooLong)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whose plain text has 65 characters").hasMessageContaining("a number's text is 64 at most");
        }
        java.util.Random random = new java.util.Random(20261111);                 // the same numbers on every run
        int taken = 0, refused = 0;
        for (int i = 0; i < 20_000; i++) {
            BigDecimal number = new BigDecimal(new java.math.BigInteger(1 + random.nextInt(230), random), random.nextInt(141) - 70);
            if (random.nextBoolean()) number = number.negate();
            String plain = number.toPlainString();
            assertThat(RowJson.plainLengthOf(number)).as("the length of %s, counted without building its text", number).isEqualTo(plain.length());
            if (plain.length() <= RowJson.NUMBER_TEXT_MAX) {
                taken++;
                assertThat(RowJson.decimalOf(plain, "a number")).as("taken, written, read: %s", number).isEqualByComparingTo(number);
                assertThat(RowJson.requirePlainShort(number, "a number")).isSameAs(number);
            } else {
                refused++;
                BigDecimal tooLong = number;
                assertThatThrownBy(() -> RowJson.requirePlainShort(tooLong, "a number")).as("its written text would be %s characters", plain.length())
                    .hasMessageContaining("whose plain text has " + plain.length() + " characters");
            }
        }
        assertThat(taken).as("the 20 000 numbers fall on both sides of the bound (taken %s, refused %s)", taken, refused).isGreaterThan(2_000);
        assertThat(refused).isGreaterThan(2_000);
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
