package com.spartan.dms.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * Records goods being sent back UP the chain. The sending party is never
 * taken from this request -- it's resolved server-side from the caller's
 * own login (see PurchaseReturnService), so a Distributor can't file a
 * return on another Distributor's behalf.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseReturnRequest {

    @NotNull(message = "Product is required")
    private Long productId;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;

    // NOTE: there is deliberately no unitPrice field. The credited rate is
    // resolved server-side from the product + the caller's own role/party
    // (PurchaseReturnService.resolveRolePrice), so a client can neither
    // supply nor override it.

    // One of DAMAGED / EXPIRED / WRONG_ITEM / OTHERS.
    @NotNull(message = "Reason is required")
    private com.spartan.dms.enums.ReturnReason reasonCode;

    // Required only when reasonCode is OTHERS.
    private String reasonNote;
}
