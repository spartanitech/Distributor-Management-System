package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistributorAssignmentRequest {

    private Long distributorId;
    private Long shopId;
    private Long productId;
    private Integer quantity;
    private String remarks;
}
