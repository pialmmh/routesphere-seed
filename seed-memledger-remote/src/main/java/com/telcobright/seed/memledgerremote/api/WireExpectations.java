package com.telcobright.seed.memledgerremote.api;

import com.telcobright.seed.memledgerremote.wire.ApplyRequest;
import com.telcobright.seed.memledgerremote.wire.Expectation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * ARCH-0118 S2 · The rows a batch's unit READ, on the wire ({@code ApplyRequest.expects}): each row as the client read it — its JSON as
 * {@link RowJson#textOf} writes a row — or {@code present = false} for a row it saw none of. The server reads them back, each key in its
 * own type, and checks them under the batch's row locks against what its cache holds ({@link RowJson#differenceFrom}). What cannot be
 * read is {@link WireBatch.Unreadable}, in mem-ledger's shape of words, naming the expectation.
 */
public final class WireExpectations {

    /** One row as the batch read it: there (with its JSON) or not. */
    public record Expected(String tier, String entity, Object id, boolean present, String json) {

        /** The row in words: {@code res_2.PackageAccount id=11}. */
        public String named() { return tier + "." + entity + " id=" + id; }
    }

    private WireExpectations() {}

    // ── the server's side ───────────────────────────────────────────────────

    /** The batch's expectations as read; {@code registered} answers the class an entity's name is registered with (null = none). */
    public static List<Expected> expectedOf(ApplyRequest request, Function<String, Class<?>> registered) {
        List<Expected> rows = new ArrayList<>(request.getExpectsCount());
        for (int i = 0; i < request.getExpectsCount(); i++) {
            rows.add(expectedOf(request.getExpects(i), registered, "expectation " + (i + 1) + " of " + request.getExpectsCount()));
        }
        return rows;
    }

    private static Expected expectedOf(Expectation wire, Function<String, Class<?>> registered, String where) {
        String row = wire.getDbName() + "." + wire.getEntityName() + " id=" + wire.getEntityId();
        Class<?> type = registered.apply(wire.getEntityName());
        if (type == null) throw new WireBatch.Unreadable(where + " (" + row + ") names an entity this ledger does not register");
        if (wire.getPresent() && wire.getEntityJson().isEmpty()) throw new WireBatch.Unreadable(where + " (" + row + ") says the row was there and carries none");
        Object id;
        try {
            id = RowJson.keyOf(type, wire.getEntityId());
        } catch (IllegalArgumentException e) {
            throw new WireBatch.Unreadable(where + " (" + row + "): " + e.getMessage());
        }
        if (id == null || wire.getDbName().isEmpty()) throw new WireBatch.Unreadable(where + " (" + row + ") names no tier or no key");
        return new Expected(wire.getDbName(), wire.getEntityName(), id, wire.getPresent(), wire.getEntityJson());
    }
}
