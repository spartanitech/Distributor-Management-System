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
public class PaymentRequest {

    private Long invoiceId;

    // Required for DISTRIBUTOR_TO_SHOP and SUPER_STOCKIST_TO_DISTRIBUTOR
    // invoices (the paying distributor). Leave null for
    // COMPANY_TO_SUPER_STOCKIST — PaymentService validates the combination
    // against the invoice's actual level.
    private Long distributorId;

    // Required only for DISTRIBUTOR_TO_SHOP invoices.
    private Long shopId;

    // Required for COMPANY_TO_SUPER_STOCKIST (the paying Super Stockist)
    // and SUPER_STOCKIST_TO_DISTRIBUTOR (the receiving Super Stockist).
    private Long superStockistId;

    private BigDecimal amount;

    private String paymentMethod;

    private String paymentStatus;

    private String transactionId;

    private LocalDate paymentDate;

    private String remarks;
}