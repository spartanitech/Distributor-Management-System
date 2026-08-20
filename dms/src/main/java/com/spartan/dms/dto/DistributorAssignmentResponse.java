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
public class DistributorAssignmentResponse {

    private Long id;

    private Long distributorId;
    private String distributorName;

    private Long shopId;
    private String shopName;

    private Long productId;
    private String productName;
    private String productCode;

    private Integer quantity;
    private Integer modifiedQuantity;

    private String status;

    private String adminRemarks;
    private String distributorRemarks;

    private String assignedBy;
    private LocalDateTime respondedAt;
    private LocalDateTime createdAt;
}
