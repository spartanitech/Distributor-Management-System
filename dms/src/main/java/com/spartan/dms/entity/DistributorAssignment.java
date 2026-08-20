package com.spartan.dms.entity;

import com.spartan.dms.enums.AssignmentStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Admin assigns a Distributor a task: deliver this Product, in this
 * Quantity, to this Shop. The Distributor then responds — accepting as-is,
 * proposing a different quantity ("Modified"), or declining. This is
 * independent of the stock-request chain: it's Admin pushing work to a
 * distributor, not a distributor asking for stock.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "distributor_assignments")
public class DistributorAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distributor_id", nullable = false)
    private Distributor distributor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    // Set only when the distributor responds MODIFIED — their counter-proposal.
    @Column(name = "modified_quantity")
    private Integer modifiedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private AssignmentStatus status = AssignmentStatus.PENDING;

    @Column(name = "admin_remarks", length = 500)
    private String adminRemarks;

    @Column(name = "distributor_remarks", length = 500)
    private String distributorRemarks;

    @Column(name = "assigned_by", length = 50)
    private String assignedBy;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;
}
