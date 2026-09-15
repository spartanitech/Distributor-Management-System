package com.spartan.dms.service;

import com.spartan.dms.dto.ApiResponse;
import com.spartan.dms.repository.InvoiceItemRepository;
import com.spartan.dms.repository.InvoiceRepository;
import com.spartan.dms.repository.PaymentProofRepository;
import com.spartan.dms.repository.PaymentRepository;
import com.spartan.dms.repository.ProductDistributorPriceRepository;
import com.spartan.dms.repository.ProductLedgerRepository;
import com.spartan.dms.repository.ProductRepository;
import com.spartan.dms.repository.ProductRequestRepository;
import com.spartan.dms.repository.ProductSuperStockistPriceRepository;
import com.spartan.dms.repository.PurchaseReturnRepository;
import com.spartan.dms.repository.SalesReturnRepository;
import com.spartan.dms.repository.StockMovementRepository;
import com.spartan.dms.repository.StockTransferRepository;
import com.spartan.dms.repository.WarehouseRepository;
import com.spartan.dms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-shot admin maintenance action: wipes catalog/transactional data left
 * over from setup/testing (products, invoices, payments, stock, product
 * ledger, returns, custom pricing) while leaving every account and business
 * profile untouched (Users -- admin/super stockist/distributor logins --
 * plus the SuperStockist/Distributor/Shop/Category/CompanySettings/AuditLog
 * records that back them). Mirrors repairMissingProfiles() in spirit: a
 * deliberate, admin-only, audited one-time action rather than something run
 * automatically.
 *
 * Table order matters -- every row here is deleted child-before-parent so
 * no foreign key referencing a still-live row is ever left dangling:
 *   payment_proofs -> sales_returns -> payments -> invoice_items -> invoices
 *   -> purchase_returns -> product_requests -> stock_transfers
 *   -> stock_movements -> product_ledger -> product_distributor_prices
 *   -> product_super_stockist_prices -> warehouses -> products
 */
@Service
@RequiredArgsConstructor
public class DataResetService {

    private final PaymentProofRepository paymentProofRepository;
    private final SalesReturnRepository salesReturnRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final InvoiceRepository invoiceRepository;
    private final PurchaseReturnRepository purchaseReturnRepository;
    private final ProductRequestRepository productRequestRepository;
    private final StockTransferRepository stockTransferRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ProductLedgerRepository productLedgerRepository;
    private final ProductDistributorPriceRepository productDistributorPriceRepository;
    private final ProductSuperStockistPriceRepository productSuperStockistPriceRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final SecurityUtils securityUtils;
    private final AuditLogService auditLogService;

    @Transactional
    public ApiResponse<String> resetCatalogAndTransactionalData() {

        securityUtils.assertAdmin();

        long products = productRepository.count();
        long invoices = invoiceRepository.count();

        paymentProofRepository.deleteAllInBatch();
        salesReturnRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        invoiceItemRepository.deleteAllInBatch();
        invoiceRepository.deleteAllInBatch();
        purchaseReturnRepository.deleteAllInBatch();
        productRequestRepository.deleteAllInBatch();
        stockTransferRepository.deleteAllInBatch();
        stockMovementRepository.deleteAllInBatch();
        productLedgerRepository.deleteAllInBatch();
        productDistributorPriceRepository.deleteAllInBatch();
        productSuperStockistPriceRepository.deleteAllInBatch();
        warehouseRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();

        String details = "Cleared " + products + " product(s), " + invoices
                + " invoice(s), plus all linked payments, invoice items, product ledger, "
                + "stock movements, stock transfers, purchase/sales returns, stock requests, "
                + "per-partner product pricing and warehouse stock. "
                + "Users, Super Stockists, Distributors, Shops, Categories and Company Settings were left untouched.";

        auditLogService.log("RESET", "DATA", null, details);

        return ApiResponse.<String>builder()
                .success(true)
                .message("Catalog and transactional data reset successfully. " + details)
                .data("Reset")
                .build();
    }
}
