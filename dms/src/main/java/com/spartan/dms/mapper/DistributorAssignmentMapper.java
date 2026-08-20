package com.spartan.dms.mapper;

import com.spartan.dms.dto.DistributorAssignmentResponse;
import com.spartan.dms.entity.DistributorAssignment;
import org.springframework.stereotype.Component;

@Component
public class DistributorAssignmentMapper {

    public DistributorAssignmentResponse toResponse(DistributorAssignment a) {
        return DistributorAssignmentResponse.builder()
                .id(a.getId())
                .distributorId(a.getDistributor() != null ? a.getDistributor().getId() : null)
                .distributorName(a.getDistributor() != null ? a.getDistributor().getDistributorName() : null)
                .shopId(a.getShop() != null ? a.getShop().getId() : null)
                .shopName(a.getShop() != null ? a.getShop().getShopName() : null)
                .productId(a.getProduct() != null ? a.getProduct().getId() : null)
                .productName(a.getProduct() != null ? a.getProduct().getProductName() : null)
                .productCode(a.getProduct() != null ? a.getProduct().getProductCode() : null)
                .quantity(a.getQuantity())
                .modifiedQuantity(a.getModifiedQuantity())
                .status(a.getStatus() != null ? a.getStatus().name() : null)
                .adminRemarks(a.getAdminRemarks())
                .distributorRemarks(a.getDistributorRemarks())
                .assignedBy(a.getAssignedBy())
                .respondedAt(a.getRespondedAt())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
