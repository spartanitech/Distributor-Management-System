package com.spartan.dms.entity;

import com.spartan.dms.enums.ProductRequestStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "product_requests", indexes = {
        @Index(name = "idx_pr_distributor", columnList = "distributor_id"),
        @Index(name = "idx_pr_super_stockist", columnList = "super_stockist_id"),
        @Index(name = "idx_pr_status", columnList = "status"),
        @Index(name = "idx_pr_level", columnList = "request_level"),
})
public class ProductRequest extends BaseEntity {

    // Which leg of the chain this is — see RequestLevel. Defaults to the
    // original behavior (a Distributor requesting stock) so existing rows
    // are unaffected.
    @Enumerated(EnumType.STRING)
    @Column(name = "request_level", nullable = false, length = 40)
    @Builder.Default
    private com.spartan.dms.enums.RequestLevel requestLevel = com.spartan.dms.enums.RequestLevel.DISTRIBUTOR_TO_SUPER_STOCKIST;

    // The distributor raising the stock request. Null for a
    // SUPER_STOCKIST_TO_COMPANY request (the Super Stockist is asking for
    // themselves, not on behalf of a distributor).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distributor_id")
    private Distributor distributor;

    // The Super Stockist this request is routed to/raised by. Set for
    // BOTH levels: for DISTRIBUTOR_TO_SUPER_STOCKIST it's the distributor's
    // own Super Stockist (who approves/fulfills it); for
    // SUPER_STOCKIST_TO_COMPANY it's the Super Stockist raising the
    // request themselves.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "super_stockist_id")
    private SuperStockist superStockist;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "requested_quantity", nullable = false)
    private Integer requestedQuantity;

    @Column(name = "approved_quantity")
    private Integer approvedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ProductRequestStatus status = ProductRequestStatus.PENDING;

    @Column(name = "remarks", length = 500)
    private String remarks;

    // Populated by the admin when approving/rejecting/fulfilling.
    @Column(name = "admin_remarks", length = 500)
    private String adminRemarks;

    // Username of the admin who actioned this request.
    @Column(name = "actioned_by", length = 50)
    private String actionedBy;

    @Column(name = "actioned_at")
    private java.time.LocalDateTime actionedAt;
}
