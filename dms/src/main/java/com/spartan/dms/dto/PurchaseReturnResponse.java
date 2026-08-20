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
public class PurchaseReturnResponse {

    private Long id;
    private String returnNumber;
    private String returnLevel;

    private Long distributorId;
    private String distributorName;

    private Long superStockistId;
    private String superStockistName;

    private Long productId;
    private String productName;
    private String productCode;

    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal returnAmount;
    private LocalDate returnDate;
    private String reason;
    private String reasonCode;
    private String reasonNote;
    private java.math.BigDecimal mrp;
    private String createdBy;

    /** PENDING / APPROVED / REJECTED. Stock only moves on APPROVED. */
    private String status;
    private String approvedBy;
    private java.time.LocalDateTime decidedAt;
    private String rejectionReason;

    /** Human-readable "Distributor X -> Super Stockist Y" for history views. */
    private String fromParty;
    private String toParty;
}
