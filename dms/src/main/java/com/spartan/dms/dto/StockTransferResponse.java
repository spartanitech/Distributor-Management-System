package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockTransferResponse {

    private Long id;

    private String fromType;
    private Long fromSuperStockistId;
    private String fromSuperStockistName;

    private String toType;
    private Long toSuperStockistId;
    private String toSuperStockistName;
    private Long toDistributorId;
    private String toDistributorName;

    private Long productId;
    private String productName;
    private String productCode;

    private Integer quantity;
    private LocalDateTime transferDate;
    private String transferredBy;
    private String status;
    private String remarks;
}
