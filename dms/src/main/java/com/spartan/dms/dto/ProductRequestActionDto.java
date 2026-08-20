package com.spartan.dms.dto;

import com.spartan.dms.enums.ProductRequestStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductRequestActionDto {

    private ProductRequestStatus status;

    // Only relevant when status == APPROVED / FULFILLED; defaults to the
    // originally requested quantity if left null.
    private Integer approvedQuantity;

    private String adminRemarks;
}
