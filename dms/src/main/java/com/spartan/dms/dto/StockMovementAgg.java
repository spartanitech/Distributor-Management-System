package com.spartan.dms.dto;

import com.spartan.dms.entity.StockMovement;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class StockMovementAgg {

    private final Long productId;
    private final StockMovement.MovementType movementType;
    private final BigDecimal totalQuantity;
    private final BigDecimal totalValue;

    public StockMovementAgg(Long productId, StockMovement.MovementType movementType,
                             BigDecimal totalQuantity, BigDecimal totalValue) {
        this.productId = productId;
        this.movementType = movementType;
        this.totalQuantity = totalQuantity;
        this.totalValue = totalValue;
    }
}
