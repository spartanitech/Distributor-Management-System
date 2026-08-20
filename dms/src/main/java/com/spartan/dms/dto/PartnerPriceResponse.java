package com.spartan.dms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerPriceResponse {
    private Long productId;
    private String productName;
    private Long partnerId;      // superStockistId or distributorId
    private String partnerName;
    private BigDecimal price;    // effective price: override if set, else the product's default tier price
    private boolean overridden;  // true if this came from a custom override row, false if it's the default
}
