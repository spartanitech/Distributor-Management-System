package com.spartan.dms.mapper;

import com.spartan.dms.dto.PurchaseReturnResponse;
import com.spartan.dms.entity.PurchaseReturn;
import com.spartan.dms.enums.ReturnLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Hand-written rather than ModelMapper-based, because fromParty/toParty
 * aren't fields on the entity at all -- they're derived from returnLevel
 * (see ReturnLevel: one row is a purchase return to its sender and a sales
 * return to its receiver, so "from"/"to" depend on which leg it is).
 */
@Component
public class PurchaseReturnMapper {

    public PurchaseReturnResponse toResponse(PurchaseReturn pr) {
        if (pr == null) {
            return null;
        }

        String distributorName = pr.getDistributor() != null ? pr.getDistributor().getDistributorName() : null;
        String superStockistName = pr.getSuperStockist() != null ? pr.getSuperStockist().getSuperStockistName() : null;
        boolean toCompany = pr.getReturnLevel() == ReturnLevel.SUPER_STOCKIST_TO_COMPANY;

        return PurchaseReturnResponse.builder()
                .id(pr.getId())
                .returnNumber(pr.getReturnNumber())
                .returnLevel(pr.getReturnLevel() != null ? pr.getReturnLevel().name() : null)
                .distributorId(pr.getDistributor() != null ? pr.getDistributor().getId() : null)
                .distributorName(distributorName)
                .superStockistId(pr.getSuperStockist() != null ? pr.getSuperStockist().getId() : null)
                .superStockistName(superStockistName)
                .productId(pr.getProduct() != null ? pr.getProduct().getId() : null)
                .productName(pr.getProduct() != null ? pr.getProduct().getProductName() : null)
                .productCode(pr.getProduct() != null ? pr.getProduct().getProductCode() : null)
                .quantity(pr.getQuantity())
                .unitPrice(pr.getUnitPrice())
                .returnAmount(pr.getReturnAmount())
                .returnDate(pr.getReturnDate())
                .reason(pr.getReason())
                .reasonCode(pr.getReasonCode() != null ? pr.getReasonCode().name() : null)
                .reasonNote(pr.getReasonNote())
                .mrp(pr.getProduct() != null ? pr.getProduct().getMrp() : null)
                .createdBy(pr.getCreatedBy())
                .status(pr.getStatus() != null ? pr.getStatus().name() : null)
                .approvedBy(pr.getApprovedBy())
                .decidedAt(pr.getDecidedAt())
                .rejectionReason(pr.getRejectionReason())
                // Sender is the Super Stockist when returning up to Company,
                // otherwise the Distributor returning up to their Super Stockist.
                .fromParty(toCompany ? superStockistName : distributorName)
                .toParty(toCompany ? "Company (Admin)" : superStockistName)
                .build();
    }

    public List<PurchaseReturnResponse> toResponseList(List<PurchaseReturn> rows) {
        return rows.stream().map(this::toResponse).collect(Collectors.toList());
    }
}
