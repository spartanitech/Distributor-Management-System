package com.spartan.dms.repository;

import com.spartan.dms.dto.TopDistributorResponse;
import com.spartan.dms.entity.Invoice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    boolean existsByInvoiceNumber(String invoiceNumber);

    // BUG-H4 fix: shared fragment excluding CANCELLED invoices from every
    // business aggregate below (sales/paid/pending totals, trends,
    // distributor/product rankings). A cancelled invoice never represents
    // real revenue or a real receivable, so it must not inflate any of
    // these numbers. NULL is treated as "not cancelled" so pre-existing
    // invoices with no invoiceStatus set at all (the common case before
    // this feature existed) keep counting exactly as before.
    // (JPQL has no way to share a WHERE fragment across @Query strings, so
    // this constant is duplicated by hand into each query below — kept as
    // one named constant so every occurrence is easy to find/audit.)

    // Used by the dashboard "Sales by District/Distributor/Super Stockist"
    // charts — these are always end-customer (retail) sales, i.e. the
    // DISTRIBUTOR_TO_SHOP leg, joined eagerly so grouping in Java doesn't
    // trigger N+1 queries per invoice.
    @Query("SELECT i FROM Invoice i " +
           "LEFT JOIN FETCH i.shop s " +
           "LEFT JOIN FETCH i.distributor d " +
           "LEFT JOIN FETCH d.superStockist ss " +
           "WHERE i.invoiceLevel = com.spartan.dms.enums.InvoiceLevel.DISTRIBUTOR_TO_SHOP " +
           "AND (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED')")
    List<Invoice> findAllShopInvoicesForCharts();

    /* ---------- Delete-guard checks (Shop/Distributor/Super Stockist deletion) ---------- */

    boolean existsByShopId(Long shopId);

    boolean existsByDistributorId(Long distributorId);

    boolean existsBySuperStockistId(Long superStockistId);

    // A Super Stockist's network sales: every DISTRIBUTOR_TO_SHOP invoice
    // issued by any distributor assigned to them. Used by Reports.
    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.shop LEFT JOIN FETCH i.distributor " +
            "WHERE i.distributor.superStockist.id = :superStockistId ORDER BY i.invoiceDate DESC")
    List<Invoice> findByDistributorSuperStockistId(@Param("superStockistId") Long superStockistId);

    // "Invoices I raised." Role-based visibility keys off WHO created the
    // invoice, not which parties it sits between -- a Super Stockist's own
    // list must not include the COMPANY_TO_SUPER_STOCKIST bill Admin
    // raised against them, nor the DISTRIBUTOR_TO_SHOP invoices their
    // distributors raised. Those are reachable through the separate
    // "billed to me" view instead (see InvoiceService.billedToMe).
    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.shop LEFT JOIN FETCH i.distributor " +
            "WHERE i.createdByUserId = :userId ORDER BY i.invoiceDate DESC, i.id DESC")
    List<Invoice> findByCreatedByUserId(@Param("userId") Long userId);

    /* ---------- Shop scoping ---------- */

    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.shop LEFT JOIN FETCH i.distributor " +
            "WHERE i.shop.id = :shopId ORDER BY i.invoiceDate ASC, i.id ASC")
    List<Invoice> findByShopIdOrderByInvoiceDateAsc(@Param("shopId") Long shopId);

    /* ---------- Distributor scoping ---------- */
    // LEFT JOIN, not INNER: a SUPER_STOCKIST_TO_DISTRIBUTOR invoice has no
    // shop, and an inner JOIN FETCH would silently drop it from a
    // distributor's own invoice list.

    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.shop LEFT JOIN FETCH i.distributor " +
            "WHERE i.distributor.id = :distributorId ORDER BY i.invoiceDate DESC")
    List<Invoice> findByDistributorId(@Param("distributorId") Long distributorId);

    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.shop LEFT JOIN FETCH i.distributor " +
            "WHERE i.distributor.id = :distributorId ORDER BY i.invoiceDate ASC, i.id ASC")
    List<Invoice> findByDistributorIdOrderByInvoiceDateAsc(@Param("distributorId") Long distributorId);

    /* ---------- Super Stockist scoping ---------- */
    // Covers both legs a Super Stockist can see: COMPANY_TO_SUPER_STOCKIST
    // (received from Admin) and SUPER_STOCKIST_TO_DISTRIBUTOR (issued to
    // their own distributors) — both have superStockist set to them.

    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.distributor LEFT JOIN FETCH i.superStockist " +
            "WHERE i.superStockist.id = :superStockistId ORDER BY i.invoiceDate DESC")
    List<Invoice> findBySuperStockistId(@Param("superStockistId") Long superStockistId);

    /* ---------- Revenue / payment totals ---------- */
    // BUG-H4 fix: every aggregate below now excludes CANCELLED invoices —
    // see the comment above findAllShopInvoicesForCharts() for why.

    @Query("SELECT COALESCE(SUM(i.totalAmount), 0) FROM Invoice i " +
            "WHERE (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED')")
    BigDecimal sumTotalAmount();

    @Query("SELECT COALESCE(SUM(i.paidAmount), 0) FROM Invoice i " +
            "WHERE (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED')")
    BigDecimal sumPaidAmount();

    @Query("SELECT COALESCE(SUM(i.balanceAmount), 0) FROM Invoice i " +
            "WHERE (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED')")
    BigDecimal sumPendingAmount();

    @Query("SELECT COALESCE(SUM(i.balanceAmount), 0) FROM Invoice i " +
            "WHERE UPPER(i.paymentStatus) IN ('PARTIAL', 'PARTIALLY_PAID', 'PARTIALLY PAID') " +
            "AND (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED')")
    BigDecimal sumPartialPaidAmount();

    /* ---------- Invoice status counts ---------- */

    @Query("SELECT COUNT(i) FROM Invoice i WHERE UPPER(i.paymentStatus) = 'PAID' " +
            "AND (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED')")
    long countPaidInvoices();

    @Query("SELECT COUNT(i) FROM Invoice i " +
            "WHERE UPPER(i.paymentStatus) IN ('PARTIAL', 'PARTIALLY_PAID', 'PARTIALLY PAID') " +
            "AND (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED')")
    long countPartiallyPaidInvoices();

    @Query("SELECT COUNT(i) FROM Invoice i WHERE UPPER(i.paymentStatus) = 'UNPAID' " +
            "AND (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED')")
    long countUnpaidInvoices();

    /* ---------- Date-scoped aggregates (today / this month / trends) ---------- */
    // These were derived (method-name) queries before, which can't express
    // the CANCELLED exclusion — converted to explicit @Query JPQL so the
    // exclusion applies here too.

    @Query("SELECT COUNT(i) FROM Invoice i WHERE i.invoiceDate = :date " +
            "AND (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED')")
    long countByInvoiceDate(@Param("date") LocalDate invoiceDate);

    @Query("SELECT COALESCE(SUM(i.totalAmount), 0) FROM Invoice i WHERE i.invoiceDate = :date " +
            "AND (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED')")
    BigDecimal sumTotalAmountByDate(@Param("date") LocalDate date);

    @Query("SELECT COUNT(i) FROM Invoice i WHERE i.invoiceDate BETWEEN :start AND :end " +
            "AND (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED')")
    long countByInvoiceDateBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("SELECT COALESCE(SUM(i.totalAmount), 0) FROM Invoice i " +
            "WHERE i.invoiceDate BETWEEN :start AND :end " +
            "AND (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED')")
    BigDecimal sumTotalAmountBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("SELECT COALESCE(SUM(i.balanceAmount), 0) FROM Invoice i " +
            "WHERE i.invoiceDate BETWEEN :start AND :end " +
            "AND (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED')")
    BigDecimal sumBalanceAmountBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /* ---------- Outstanding KPI drill-down ---------- */
    // Every invoice that still has a balance owed, with shop/distributor
    // eagerly fetched so the service can group by party (district, name,
    // contact) without N+1 queries. Items/products are loaded separately
    // per invoice in the service (see DashboardService#getOutstandingDetails).

    @Query("SELECT i FROM Invoice i " +
           "LEFT JOIN FETCH i.shop s " +
           "LEFT JOIN FETCH i.distributor d " +
           "WHERE i.balanceAmount IS NOT NULL AND i.balanceAmount > 0 " +
           "AND (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED') " +
           "ORDER BY i.balanceAmount DESC")
    List<Invoice> findAllWithOutstandingBalance();

    /* ---------- Day Book ---------- */
    // Every invoice regardless of level (Company->SS, SS->Distributor,
    // Distributor->Shop), with every possible party eagerly fetched, so
    // the Day Book can show one flat journal across the whole business.
    @Query("SELECT i FROM Invoice i " +
           "LEFT JOIN FETCH i.shop LEFT JOIN FETCH i.distributor LEFT JOIN FETCH i.superStockist " +
           "ORDER BY i.invoiceDate ASC, i.id ASC")
    List<Invoice> findAllOrderByInvoiceDateAsc();

    /* ---------- Recent invoices (dashboard widget) ---------- */

    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.shop LEFT JOIN FETCH i.distributor " +
            "ORDER BY i.invoiceDate DESC, i.createdAt DESC")
    List<Invoice> findRecentInvoices(Pageable pageable);

    /* ---------- Monthly sales trend (for chart, grouped in Java by month) ---------- */

    @Query("SELECT i FROM Invoice i WHERE i.invoiceDate >= :fromDate " +
            "AND (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED') " +
            "ORDER BY i.invoiceDate ASC")
    List<Invoice> findAllFromDate(@Param("fromDate") LocalDate fromDate);

    /* ---------- Top distributors by revenue ---------- */

    @Query("SELECT new com.spartan.dms.dto.TopDistributorResponse(" +
            "i.distributor.distributorName, COUNT(i), SUM(i.totalAmount)) " +
            "FROM Invoice i " +
            "WHERE i.distributor IS NOT NULL " +
            "AND (i.invoiceStatus IS NULL OR i.invoiceStatus <> 'CANCELLED') " +
            "GROUP BY i.distributor.distributorName " +
            "ORDER BY SUM(i.totalAmount) DESC")
    List<TopDistributorResponse> findTopDistributors(Pageable pageable);
}
