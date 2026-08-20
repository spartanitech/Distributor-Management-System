package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductLedgerResponse {

    private Long id;

    private LocalDateTime transactionDateTime;
    private String voucherNo;
    private String transactionType;

    private Long productId;
    private String productName;
    private String productCode;
    private String batchNo;

    private String ownerType;       // COMPANY / SUPER_STOCKIST / DISTRIBUTOR / SHOP
    private Long locationId;        // id of the super stockist / distributor / shop (null for COMPANY)
    private String locationName;    // display name for whichever of the above applies

    private BigDecimal inQuantity;
    private BigDecimal outQuantity;
    private BigDecimal balanceQuantity;

    private BigDecimal unitCost;
    private BigDecimal stockValue;

    private String performedBy;
    private String remarks;
}
