package com.telcobright.seed.switchledger;

import com.telcobright.seed.switchledger.internal.LedgerEntities;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mem-ledger takes ANY field named {@code id} as an entity's key (its EntityMetadataExtractor: {@code name == "id" || @Id}, the last one
 * wins). An entity whose key is another field ({@code @Id productKey}) and that ALSO declares a field {@code id} would be loaded, marked and
 * written by the wrong column — 2026-10-30: the product's Odoo variant was a field {@code id}; the load failed on a portal product and an
 * update would have written {@code WHERE id = …}. Every entity the switch ledger knows has ONE key: its {@code @Id} field, or a field named
 * {@code id} — never both apart. Mutation seen red: the product's {@code odooProductId} renamed back to {@code id}.
 */
class KnownEntitiesHaveOneKeyTest {

    @Test
    void everyKnownEntityHasOneKey_noFieldNamedIdBesideAnotherKey() {
        List<String> traps = new ArrayList<>();
        for (LedgerEntities.Known known : LedgerEntities.known()) {
            Field annotated = null, namedId = null;
            for (Class<?> c = known.type(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.isAnnotationPresent(Id.class)) annotated = f;
                    if (f.getName().equalsIgnoreCase("id")) namedId = f;
                }
            }
            if (annotated != null && namedId != null && !annotated.equals(namedId)) {
                traps.add(known.type().getSimpleName() + ": key " + annotated.getName() + " and a field named " + namedId.getName());
            }
        }
        assertThat(traps).as("mem-ledger would take the field named id as the key").isEmpty();
    }
}
