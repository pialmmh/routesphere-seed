package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.RecordId;
import com.telcobright.seed.sessionflow.api.RecordStatement;
import com.telcobright.seed.sessionflow.dependencies.RecordDialect;
import com.telcobright.seed.sessionflow.dependencies.RecordStore;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The base's default CDR SQL (P1-R §A): one tier record as billing-core's {@code cdr} row in the tier's own schema ({@code <tier>.cdr}),
 * written once — exactly as billing-core keeps it once ({@code MySqlCdrBatchRunner.DropWhatMustNotBeBilledAgain}):
 *
 * <pre>
 *   1. the probe   a row of billing-core's key in the tier is the record written already:
 *                    PostgreSQL ChannelCallUuid in cdr and cdrerror · MySQL UniqueBillId in cdr ({@code IdempotencyKey})
 *   2. the INSERT  the row, its IdCall seeded under the batch lock ({@code MaxIdSeededAutoIncrementManager}); a plain INSERT, as
 *                  billing-core's: a duplicate that gets past the probe fails the batch on the unique index, and the retry's probe drops it
 * </pre>
 *
 * The probe — not {@code INSERT IGNORE} / {@code ON CONFLICT DO NOTHING} — is billing-core's rule: it holds on a MySQL tier without the
 * by-hand unique index ({@code cdr_idempotency.sql}), and a MySQL {@code IGNORE} would also turn a value the column cannot take into a
 * warning and a changed row.
 */
public final class CdrTableSql {

    /** A tier's name goes into the SQL as text (a schema cannot be a parameter): only a plain name is taken. */
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private CdrTableSql() {}

    /** The record's two statements: the probe of billing-core's key, then the INSERT. */
    public static List<RecordStatement> statementsOf(CdrEvent record, RecordStore store) {
        String tier = tierOf(record);
        boolean postgres = store.dialect() == RecordDialect.POSTGRESQL;
        List<Object> values = CdrColumns.valuesOf(record, store.switchId(), idCallOf(record), postgres);
        return List.of(probeOf(tier, record, postgres), insertOf(tier, postgres, values));
    }

    /** The record's IdCall: the INSERT and the summary entry name the same one. */
    public static RecordId idCallOf(CdrEvent record) {
        String tier = tierOf(record);
        return new RecordId(idCallSeedOf(tier), tier + "|" + record.channelCallUuid);
    }

    /** The tier's schema — the record's {@code tenant}, a plain name. */
    public static String tierOf(CdrEvent record) {
        String tier = record.tenant;
        if (tier == null || !PLAIN_NAME.matcher(tier).matches()) {
            throw new IllegalArgumentException("the record of " + record.channelCallUuid + " names the tier '" + tier + "': not a schema name");
        }
        return tier;
    }

    /** billing-core's seed of "cdr": the greater of the IdCalls in {@code cdr} and {@code cdrerror} (an error row keeps its id). */
    static String idCallSeedOf(String tier) {
        return "select greatest(coalesce((select max(IdCall) from " + tier + ".cdr), 0), coalesce((select max(IdCall) from " + tier + ".cdrerror), 0))";
    }

    private static RecordStatement probeOf(String tier, CdrEvent record, boolean postgres) {
        if (postgres) {
            return RecordStatement.probe("select 1 from " + tier + ".cdr where ChannelCallUuid = ? union all select 1 from " + tier
                + ".cdrerror where ChannelCallUuid = ?", record.channelCallUuid, record.channelCallUuid);
        }
        return RecordStatement.probe("select 1 from " + tier + ".cdr where UniqueBillId = ?", record.callId);
    }

    private static RecordStatement insertOf(String tier, boolean postgres, List<Object> values) {
        List<String> columns = CdrColumns.columns(postgres);
        String marks = String.join(",", Collections.nCopies(columns.size(), "?"));
        return new RecordStatement("insert into " + tier + ".cdr (" + String.join(",", columns) + ") values (" + marks + ")", values);
    }
}
