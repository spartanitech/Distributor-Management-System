package com.spartan.dms.mapper;

import com.spartan.dms.dto.ProductRequestResponse;
import com.spartan.dms.entity.ProductRequest;
import org.springframework.stereotype.Component;

@Component
public class ProductRequestMapper {

    public ProductRequestResponse toResponse(ProductRequest pr) {

        return ProductRequestResponse.builder()
                .id(pr.getId())
                .requestLevel(pr.getRequestLevel() != null ? pr.getRequestLevel().name() : null)
                .superStockistId(pr.getSuperStockist() != null ? pr.getSuperStockist().getId() : null)
                .superStockistName(pr.getSuperStockist() != null ? pr.getSuperStockist().getSuperStockistName() : null)
                .distributorId(pr.getDistributor() != null ? pr.getDistributor().getId() : null)
                .distributorName(pr.getDistributor() != null ? pr.getDistributor().getDistributorName() : null)
                .productId(pr.getProduct() != null ? pr.getProduct().getId() : null)
                .productName(pr.getProduct() != null ? pr.getProduct().getProductName() : null)
                .productCode(pr.getProduct() != null ? pr.getProduct().getProductCode() : null)
                .requestedQuantity(pr.getRequestedQuantity())
                .approvedQuantity(pr.getApprovedQuantity())
                .status(pr.getStatus())
                .remarks(pr.getRemarks())
                .adminRemarks(pr.getAdminRemarks())
                .actionedBy(pr.getActionedBy())
                .actionedAt(pr.getActionedAt())
                .createdAt(pr.getCreatedAt())
                .build();
    }
}
