package com.spartan.dms.repository;

import com.spartan.dms.dto.StockMovementAgg;
import com.spartan.dms.entity.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    // Per-product Inward/Outward qty+value within [fromDate, toDate] —
    // one grouped query for the whole Stock Summary table instead of
    // per-product round-trips. Also reused with toDate=today to roll a
    // product's current stock back to its Opening Balance at fromDate.
    @Query("SELECT new com.spartan.dms.dto.StockMovementAgg(" +
           "sm.product.id, sm.movementType, SUM(sm.quantity), SUM(sm.value)) " +
           "FROM StockMovement sm " +
           "WHERE sm.movementDate BETWEEN :fromDate AND :toDate " +
           "GROUP BY sm.product.id, sm.movementType")
    List<StockMovementAgg> findAggregatesBetween(@Param("fromDate") LocalDate fromDate,
                                                  @Param("toDate") LocalDate toDate);

    boolean existsByProductId(Long productId);

    List<StockMovement> findByReferenceTypeAndReferenceId(String referenceType, String referenceId);
}
