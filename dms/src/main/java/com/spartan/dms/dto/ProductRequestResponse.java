package com.spartan.dms.dto;

import com.spartan.dms.enums.ProductRequestStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductRequestResponse {

    private Long id;
    private String requestLevel;
    private Long superStockistId;
    private String superStockistName;
    private Long distributorId;
    private String distributorName;
    private Long productId;
    private String productName;
    private String productCode;
    private Integer requestedQuantity;
    private Integer approvedQuantity;
    private ProductRequestStatus status;
    private String remarks;
    private String adminRemarks;
    private String actionedBy;
    private LocalDateTime actionedAt;
    private LocalDateTime createdAt;
}
