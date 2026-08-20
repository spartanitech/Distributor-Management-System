package com.spartan.dms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One ledger line for a product's stock movement — either an INWARD
 * (purchase/stock-entry) or OUTWARD (sale) event. The Stock Summary report
 * is built entirely from this table: Opening = running balance before the
 * date range, Inward/Outward = sums within the range, Closing = the
 * product's current stock_quantity as of "today".
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "stock_movements", indexes = {
        @Index(name = "idx_stock_movement_product", columnList = "product_id"),
        @Index(name = "idx_stock_movement_date", columnList = "movement_date"),
        @Index(name = "idx_stock_movement_type", columnList = "movement_type"),
})
public class StockMovement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 20)
    private MovementType movementType;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "rate", nullable = false, precision = 12, scale = 2)
    private BigDecimal rate;

    @Column(name = "value", nullable = false, precision = 14, scale = 2)
    private BigDecimal value;

    @Column(name = "movement_date", nullable = false)
    private LocalDate movementDate;

    // e.g. "INVOICE", "STOCK_ENTRY" — what caused this movement
    @Column(name = "reference_type", length = 40)
    private String referenceType;

    @Column(name = "reference_id", length = 100)
    private String referenceId;

    @Column(name = "remarks", length = 255)
    private String remarks;

    public enum MovementType { INWARD, OUTWARD }
}
