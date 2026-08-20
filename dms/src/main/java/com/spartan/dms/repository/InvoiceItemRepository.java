package com.spartan.dms.repository;

import com.spartan.dms.dto.CategorySalesResponse;
import com.spartan.dms.dto.TopProductResponse;
import com.spartan.dms.entity.InvoiceItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, Long> {

    List<InvoiceItem> findByInvoiceId(Long invoiceId);

    @Query("SELECT ii FROM InvoiceItem ii LEFT JOIN FETCH ii.product LEFT JOIN FETCH ii.product.category WHERE ii.invoice.id = :invoiceId ORDER BY ii.id ASC")
    List<InvoiceItem> findByInvoiceIdWithProduct(@org.springframework.data.repository.query.Param("invoiceId") Long invoiceId);

    // Batch variant of the above. Fetching items one invoice at a time
    // inside a list loop is an N+1: rendering 200 invoices fired 201
    // queries. This pulls every line item for the whole page in ONE query
    // so the caller can group them in memory (see
    // InvoiceService.attachItemsBulk).
    @Query("SELECT ii FROM InvoiceItem ii LEFT JOIN FETCH ii.product LEFT JOIN FETCH ii.product.category "
            + "WHERE ii.invoice.id IN :invoiceIds ORDER BY ii.invoice.id ASC, ii.id ASC")
    List<InvoiceItem> findByInvoiceIdsWithProduct(
            @org.springframework.data.repository.query.Param("invoiceIds") java.util.Collection<Long> invoiceIds);

    boolean existsByProductId(Long productId);

    // Bulk product-name lookup for a batch of invoices (Outstanding KPI
    // drill-down) — one query instead of N+1 per invoice.
    @Query("SELECT ii.invoice.id, ii.product.productName FROM InvoiceItem ii WHERE ii.invoice.id IN :invoiceIds")
    List<Object[]> findProductNamesByInvoiceIds(@org.springframework.data.repository.query.Param("invoiceIds") List<Long> invoiceIds);

    /* ---------- Top-selling products (by revenue) ---------- */
    // BUG-H4 fix: exclude line items belonging to a CANCELLED invoice from
    // both product-ranking queries below, for the same reason cancelled
    // invoices are excluded from every Dashboard total in InvoiceRepository.

    @Query("SELECT new com.spartan.dms.dto.TopProductResponse(" +
            "ii.product.productName, ii.product.category.categoryName, " +
            "SUM(ii.quantity), SUM(ii.totalAmount)) " +
            "FROM InvoiceItem ii " +
            "WHERE (ii.invoice.invoiceStatus IS NULL OR ii.invoice.invoiceStatus <> 'CANCELLED') " +
            "GROUP BY ii.product.productName, ii.product.category.categoryName " +
            "ORDER BY SUM(ii.totalAmount) DESC")
    List<TopProductResponse> findTopSellingProducts(Pageable pageable);

    /* ---------- Sales grouped by category (for the category chart) ---------- */

    @Query("SELECT new com.spartan.dms.dto.CategorySalesResponse(" +
            "ii.product.category.categoryName, SUM(ii.quantity), SUM(ii.totalAmount)) " +
            "FROM InvoiceItem ii " +
            "WHERE (ii.invoice.invoiceStatus IS NULL OR ii.invoice.invoiceStatus <> 'CANCELLED') " +
            "GROUP BY ii.product.category.categoryName " +
            "ORDER BY SUM(ii.totalAmount) DESC")
    List<CategorySalesResponse> findCategorySales();
}