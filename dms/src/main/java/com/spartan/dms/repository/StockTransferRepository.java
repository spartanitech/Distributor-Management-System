package com.spartan.dms.repository;

import com.spartan.dms.entity.StockTransfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    // Admin view: all Company -> Super Stockist transfers
    List<StockTransfer> findByFromSuperStockistIsNullOrderByTransferDateDesc();

    // Super Stockist view: everything they sent out to distributors
    List<StockTransfer> findByFromSuperStockistIdOrderByTransferDateDesc(Long superStockistId);

    // Super Stockist view: everything they received from the company
    List<StockTransfer> findByToSuperStockistIdOrderByTransferDateDesc(Long superStockistId);

    // Distributor view: everything they received
    List<StockTransfer> findByToDistributorIdOrderByTransferDateDesc(Long distributorId);

    List<StockTransfer> findByTransferDateBetweenOrderByTransferDateDesc(LocalDateTime start, LocalDateTime end);

    boolean existsByProductId(Long productId);

    boolean existsByToDistributorId(Long distributorId);

    boolean existsByFromSuperStockistId(Long superStockistId);

    boolean existsByToSuperStockistId(Long superStockistId);
}
