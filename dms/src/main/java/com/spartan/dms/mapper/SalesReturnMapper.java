package com.spartan.dms.mapper;

import com.spartan.dms.dto.SalesReturnResponse;
import com.spartan.dms.entity.SalesReturn;
import org.springframework.stereotype.Component;

@Component
public class SalesReturnMapper {

    public SalesReturnResponse toResponse(SalesReturn sr) {
        return SalesReturnResponse.builder()
                .id(sr.getId())
                .invoiceId(sr.getInvoice() != null ? sr.getInvoice().getId() : null)
                .invoiceNumber(sr.getInvoice() != null ? sr.getInvoice().getInvoiceNumber() : null)
                .shopId(sr.getShop() != null ? sr.getShop().getId() : null)
                .shopName(sr.getShop() != null ? sr.getShop().getShopName() : null)
                .distributorId(sr.getDistributor() != null ? sr.getDistributor().getId() : null)
                .distributorName(sr.getDistributor() != null ? sr.getDistributor().getDistributorName() : null)
                .productId(sr.getProduct() != null ? sr.getProduct().getId() : null)
                .productName(sr.getProduct() != null ? sr.getProduct().getProductName() : null)
                .quantity(sr.getQuantity())
                .unitPrice(sr.getUnitPrice())
                .returnAmount(sr.getReturnAmount())
                .returnDate(sr.getReturnDate())
                .reason(sr.getReason())
                .status(sr.getStatus())
                .build();
    }
}
