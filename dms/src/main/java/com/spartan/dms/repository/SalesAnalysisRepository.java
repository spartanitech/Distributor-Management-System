package com.spartan.dms.repository;

import com.spartan.dms.dto.CategorySalesResponse;
import com.spartan.dms.dto.PartySalesRaw;
import com.spartan.dms.dto.TopProductResponse;
import com.spartan.dms.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Sales Analysis — Party-wise / Product-wise / Category-wise, scoped to
 * exactly one leg of Company -> Super Stockist -> Distributor -> Shop at a
 * time, matching InvoiceLevel:
 *
 *   COMPANY_TO_SUPER_STOCKIST     — Admin's own sales, grouped by Super Stockist
 *   SUPER_STOCKIST_TO_DISTRIBUTOR — one Super Stockist's sales, grouped by Distributor
 *   DISTRIBUTOR_TO_SHOP           — one Distributor's sales, grouped by Shop
 *
 * SalesAnalysisService picks which of the three query variants to call
 * based on the caller's role and any drill-down id they passed in — see
 * that class for the access rules. This mirrors ProductLedger's existing
 * (superStockistId/distributorId) scoping pattern rather than inventing a
 * new one.
 */
public interface SalesAnalysisRepository extends JpaRepository<Invoice, Long> {

    /* ---------------- Party-wise ---------------- */

    @Query("SELECT new com.spartan.dms.dto.PartySalesRaw(" +
            "i.superStockist.id, i.superStockist.superStockistName, COUNT(i), COALESCE(SUM(i.totalAmount), 0)) " +
            "FROM Invoice i " +
            "WHERE i.invoiceLevel = com.spartan.dms.enums.InvoiceLevel.COMPANY_TO_SUPER_STOCKIST " +
            "GROUP BY i.superStockist.id, i.superStockist.superStockistName " +
            "ORDER BY SUM(i.totalAmount) DESC")
    List<PartySalesRaw> partySalesBySuperStockist();

    @Query("SELECT new com.spartan.dms.dto.PartySalesRaw(" +
            "i.distributor.id, i.distributor.distributorName, COUNT(i), COALESCE(SUM(i.totalAmount), 0)) " +
            "FROM Invoice i " +
            "WHERE i.invoiceLevel = com.spartan.dms.enums.InvoiceLevel.SUPER_STOCKIST_TO_DISTRIBUTOR " +
            "AND i.superStockist.id = :superStockistId " +
            "GROUP BY i.distributor.id, i.distributor.distributorName " +
            "ORDER BY SUM(i.totalAmount) DESC")
    List<PartySalesRaw> partySalesByDistributorForSuperStockist(@Param("superStockistId") Long superStockistId);

    @Query("SELECT new com.spartan.dms.dto.PartySalesRaw(" +
            "i.shop.id, i.shop.shopName, COUNT(i), COALESCE(SUM(i.totalAmount), 0)) " +
            "FROM Invoice i " +
            "WHERE i.invoiceLevel = com.spartan.dms.enums.InvoiceLevel.DISTRIBUTOR_TO_SHOP " +
            "AND i.distributor.id = :distributorId " +
            "GROUP BY i.shop.id, i.shop.shopName " +
            "ORDER BY SUM(i.totalAmount) DESC")
    List<PartySalesRaw> partySalesByShopForDistributor(@Param("distributorId") Long distributorId);

    /* ---------------- Product-wise ---------------- */

    @Query("SELECT new com.spartan.dms.dto.TopProductResponse(" +
            "ii.product.productName, ii.product.category.categoryName, SUM(ii.quantity), SUM(ii.totalAmount)) " +
            "FROM InvoiceItem ii JOIN ii.invoice i " +
            "WHERE i.invoiceLevel = com.spartan.dms.enums.InvoiceLevel.COMPANY_TO_SUPER_STOCKIST " +
            "GROUP BY ii.product.productName, ii.product.category.categoryName " +
            "ORDER BY SUM(ii.totalAmount) DESC")
    List<TopProductResponse> productSalesAtCompanyLevel();

    @Query("SELECT new com.spartan.dms.dto.TopProductResponse(" +
            "ii.product.productName, ii.product.category.categoryName, SUM(ii.quantity), SUM(ii.totalAmount)) " +
            "FROM InvoiceItem ii JOIN ii.invoice i " +
            "WHERE i.invoiceLevel = com.spartan.dms.enums.InvoiceLevel.SUPER_STOCKIST_TO_DISTRIBUTOR " +
            "AND i.superStockist.id = :superStockistId " +
            "GROUP BY ii.product.productName, ii.product.category.categoryName " +
            "ORDER BY SUM(ii.totalAmount) DESC")
    List<TopProductResponse> productSalesForSuperStockist(@Param("superStockistId") Long superStockistId);

    @Query("SELECT new com.spartan.dms.dto.TopProductResponse(" +
            "ii.product.productName, ii.product.category.categoryName, SUM(ii.quantity), SUM(ii.totalAmount)) " +
            "FROM InvoiceItem ii JOIN ii.invoice i " +
            "WHERE i.invoiceLevel = com.spartan.dms.enums.InvoiceLevel.DISTRIBUTOR_TO_SHOP " +
            "AND i.distributor.id = :distributorId " +
            "GROUP BY ii.product.productName, ii.product.category.categoryName " +
            "ORDER BY SUM(ii.totalAmount) DESC")
    List<TopProductResponse> productSalesForDistributor(@Param("distributorId") Long distributorId);

    /* ---------------- Category-wise ---------------- */

    @Query("SELECT new com.spartan.dms.dto.CategorySalesResponse(" +
            "ii.product.category.categoryName, SUM(ii.quantity), SUM(ii.totalAmount)) " +
            "FROM InvoiceItem ii JOIN ii.invoice i " +
            "WHERE i.invoiceLevel = com.spartan.dms.enums.InvoiceLevel.COMPANY_TO_SUPER_STOCKIST " +
            "GROUP BY ii.product.category.categoryName " +
            "ORDER BY SUM(ii.totalAmount) DESC")
    List<CategorySalesResponse> categorySalesAtCompanyLevel();

    @Query("SELECT new com.spartan.dms.dto.CategorySalesResponse(" +
            "ii.product.category.categoryName, SUM(ii.quantity), SUM(ii.totalAmount)) " +
            "FROM InvoiceItem ii JOIN ii.invoice i " +
            "WHERE i.invoiceLevel = com.spartan.dms.enums.InvoiceLevel.SUPER_STOCKIST_TO_DISTRIBUTOR " +
            "AND i.superStockist.id = :superStockistId " +
            "GROUP BY ii.product.category.categoryName " +
            "ORDER BY SUM(ii.totalAmount) DESC")
    List<CategorySalesResponse> categorySalesForSuperStockist(@Param("superStockistId") Long superStockistId);

    @Query("SELECT new com.spartan.dms.dto.CategorySalesResponse(" +
            "ii.product.category.categoryName, SUM(ii.quantity), SUM(ii.totalAmount)) " +
            "FROM InvoiceItem ii JOIN ii.invoice i " +
            "WHERE i.invoiceLevel = com.spartan.dms.enums.InvoiceLevel.DISTRIBUTOR_TO_SHOP " +
            "AND i.distributor.id = :distributorId " +
            "GROUP BY ii.product.category.categoryName " +
            "ORDER BY SUM(ii.totalAmount) DESC")
    List<CategorySalesResponse> categorySalesForDistributor(@Param("distributorId") Long distributorId);
}
