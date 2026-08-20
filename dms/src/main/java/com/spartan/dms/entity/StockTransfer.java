package com.spartan.dms.entity;

import com.spartan.dms.enums.OwnerType;
import com.spartan.dms.enums.TransferStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Complete history of every stock movement in the hierarchy:
 *   Company -> Super Stockist   (fromType=COMPANY,        toType=SUPER_STOCKIST)
 *   Super Stockist -> Distributor (fromType=SUPER_STOCKIST, toType=DISTRIBUTOR)
 *
 * Exactly one of {fromSuperStockist} is set when fromType=SUPER_STOCKIST
 * (null when fromType=COMPANY, since Company has no linked entity).
 * Exactly one of {toSuperStockist, toDistributor} is set depending on
 * toType. This row is never mutated after creation (CANCELLED transfers
 * get their own reversing row) so it doubles as an immutable audit trail.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "stock_transfers", indexes = {
        @Index(name = "idx_st_to_distributor", columnList = "to_distributor_id"),
        @Index(name = "idx_st_to_super_stockist", columnList = "to_super_stockist_id"),
        @Index(name = "idx_st_from_super_stockist", columnList = "from_super_stockist_id"),
        @Index(name = "idx_st_transfer_date", columnList = "transfer_date"),
})
public class StockTransfer extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "from_type", nullable = false, length = 20)
    private OwnerType fromType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_super_stockist_id")
    private SuperStockist fromSuperStockist;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_type", nullable = false, length = 20)
    private OwnerType toType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_super_stockist_id")
    private SuperStockist toSuperStockist;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_distributor_id")
    private Distributor toDistributor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "transfer_date", nullable = false)
    private LocalDateTime transferDate;

    // Username of whoever initiated the transfer (Admin for Company->SS,
    // the Super Stockist's own login for SS->Distributor).
    @Column(name = "transferred_by", length = 100)
    private String transferredBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private TransferStatus status = TransferStatus.COMPLETED;

    @Column(name = "remarks", length = 500)
    private String remarks;
}
