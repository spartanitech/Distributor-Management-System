package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceRequest {

    private String invoiceNumber;

    // "COMPANY_TO_SUPER_STOCKIST" | "SUPER_STOCKIST_TO_DISTRIBUTOR" | "DISTRIBUTOR_TO_SHOP".
    // Optional — defaults to DISTRIBUTOR_TO_SHOP (the original behavior) when blank.
    private String invoiceLevel;

    private Long superStockistId;

    private Long distributorId;

    private Long shopId;

    private LocalDate invoiceDate;

    private BigDecimal subTotal;

    private BigDecimal discountAmount;

    private BigDecimal taxAmount;

    private BigDecimal totalAmount;

    private BigDecimal paidAmount;

    private BigDecimal balanceAmount;

    private String paymentStatus;

    private String paymentMethod;

    private String remarks;

    // Real line items for this invoice (product/qty/price/tax per row).
    // Optional — omitting it keeps the old header-totals-only behavior.
    private java.util.List<InvoiceItemRequest> items;

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class InvoiceItemRequest {
        private Long productId;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal discountAmount;
        private BigDecimal gstPercentage;
    }
}