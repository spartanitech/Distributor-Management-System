package com.spartan.dms.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * One-time, self-healing fix for a stale schema constraint that breaks
 * saves across several tables with:
 *   "This record can't be saved or deleted because it conflicts with —
 *    or is still referenced by — another record."
 *
 * Root cause: this app models a 3-tier hierarchy (Company -> Super
 * Stockist -> Distributor -> Shop), and several entities have a
 * distributor / superStockist / shop @ManyToOne that is legitimately left
 * null depending on which tier a given row belongs to (e.g. a
 * COMPANY_TO_SUPER_STOCKIST Invoice has no distributor; a Payment on that
 * same invoice has no distributor either — see InvoiceService and
 * PaymentService). None of these @JoinColumns declare nullable = false in
 * Java, but several of the physical MySQL columns still carry a NOT NULL
 * constraint left over from before multi-level support existed.
 * spring.jpa.hibernate.ddl-auto=update never relaxes an existing NOT NULL
 * column on MySQL, so the mismatch silently survives every restart and
 * throws a raw DataIntegrityViolationException (MySQL error 1048) on
 * save — that's the generic conflict message above. First seen on
 * invoices.distributor_id; the identical bug then showed up on
 * payments.distributor_id, which is why this now checks every table
 * built on the same tier pattern instead of just one.
 *
 * This runner checks INFORMATION_SCHEMA on every boot and only issues an
 * ALTER TABLE for a column that is still NOT NULL, preserving that
 * column's existing type exactly (only the nullability changes) — so
 * this is a no-op on every future restart once the schema is fixed, and
 * never touches a column that's already correct or one that's genuinely
 * required (e.g. shops.distributor_id, which really is nullable = false
 * in the entity, is not in the list below).
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaSelfHealRunner implements CommandLineRunner {

    /** { table, column } pairs whose @JoinColumn has no nullable = false in the entity. */
    private static final String[][] NULLABLE_TIER_COLUMNS = {
            {"invoices", "distributor_id"},
            {"invoices", "super_stockist_id"},
            {"invoices", "shop_id"},
            {"payments", "distributor_id"},
            {"payments", "super_stockist_id"},
            {"payments", "shop_id"},
            {"product_ledger", "distributor_id"},
            {"product_ledger", "super_stockist_id"},
            {"product_ledger", "shop_id"},
            {"product_requests", "distributor_id"},
            {"product_requests", "super_stockist_id"},
            {"purchase_returns", "distributor_id"},
            {"purchase_returns", "super_stockist_id"},
            {"users", "distributor_id"},
            {"users", "super_stockist_id"},
            {"warehouses", "distributor_id"},
            {"warehouses", "super_stockist_id"},
            {"distributors", "super_stockist_id"},
    };

    private final DataSource dataSource;

    public SchemaSelfHealRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) {
        try (Connection conn = dataSource.getConnection()) {
            String schema = conn.getCatalog();
            for (String[] tableColumn : NULLABLE_TIER_COLUMNS) {
                fixColumnIfStillNotNull(conn, schema, tableColumn[0], tableColumn[1]);
            }
        } catch (Exception e) {
            // Never block application startup over this — log loudly so it's
            // visible in the console, and the app keeps booting either way.
            log.error("Schema self-heal check failed. If a save still fails with a " +
                    "'conflicts with... another record' error, this needs to be fixed " +
                    "manually (see the class-level comment on SchemaSelfHealRunner for " +
                    "the exact ALTER TABLE needed).", e);
        }
    }

    private void fixColumnIfStillNotNull(Connection conn, String schema, String table, String column) throws Exception {
        String columnType;
        boolean isNullable;

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT IS_NULLABLE, COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Table/column doesn't exist yet (fresh DB, or an older
                    // version of the schema) -- nothing to fix, ddl-auto will
                    // create it correctly (nullable) from scratch.
                    return;
                }
                isNullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                columnType = rs.getString("COLUMN_TYPE"); // e.g. "bigint(20)" — preserves exact existing type
            }
        }

        if (isNullable) {
            return; // already correct — nothing to do, every subsequent boot hits this branch
        }

        log.warn("Schema self-heal: {}.{} is NOT NULL in the database, but the entity " +
                "legitimately leaves this field null for some rows. Altering it to be " +
                "nullable now (one-time fix, column type preserved as {}).",
                table, column, columnType);

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + table + " MODIFY COLUMN " + column + " " + columnType + " NULL");
        }

        log.warn("Schema self-heal: {}.{} is now nullable.", table, column);
    }
}
