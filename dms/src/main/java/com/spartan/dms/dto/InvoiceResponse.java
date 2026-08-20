package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceResponse {

    private Long id;

    private String invoiceNumber;

    private String invoiceLevel;

    // Who raised it (vs. which parties it's between) -- lets Admin's
    // invoice list show/filter by creator.
    private String createdByUsername;
    private String createdByRole;

    private Long superStockistId;
    private String superStockistName;

    private Long distributorId;
    private String distributorName;

    private Long shopId;
    private String shopName;

    private LocalDate invoiceDate;

    private BigDecimal subTotal;

    private BigDecimal discountAmount;

    private BigDecimal taxAmount;

    private BigDecimal totalAmount;

    private BigDecimal paidAmount;

    private BigDecimal balanceAmount;

    private String paymentStatus;

    private String paymentMethod;

    private String watermark;

    private String pdfUrl;

    private String qrCodeUrl;

    private String remarks;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // Real persisted line items for this invoice (from invoice_items table).
    // Empty for invoices created before this was wired up.
    private java.util.List<InvoiceItemResponse> items;

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class InvoiceItemResponse {
        private Long id;
        private Long productId;
        private String productName;
        private String productCode;
        private String unit;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal discountAmount;
        private BigDecimal gstPercentage;
        private BigDecimal gstAmount;
        private BigDecimal totalAmount;
    }
}