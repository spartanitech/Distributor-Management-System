package com.spartan.dms.repository;

import com.spartan.dms.entity.PurchaseReturn;
import com.spartan.dms.enums.ReturnLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PurchaseReturnRepository extends JpaRepository<PurchaseReturn, Long> {

    List<PurchaseReturn> findByOrderByReturnDateDescIdDesc();

    // "My purchase returns" for a Distributor -- goods they sent back up
    // to their Super Stockist.
    List<PurchaseReturn> findByDistributorIdOrderByReturnDateDescIdDesc(Long distributorId);

    // Two different views onto the same Super Stockist:
    //  - level DISTRIBUTOR_TO_SUPER_STOCKIST => sales returns they RECEIVED
    //  - level SUPER_STOCKIST_TO_COMPANY     => purchase returns they SENT
    List<PurchaseReturn> findBySuperStockistIdAndReturnLevelOrderByReturnDateDescIdDesc(
            Long superStockistId, ReturnLevel returnLevel);

    List<PurchaseReturn> findBySuperStockistIdOrderByReturnDateDescIdDesc(Long superStockistId);

    // Admin's "sales returns received" = everything Super Stockists sent up
    // to Company.
    List<PurchaseReturn> findByReturnLevelOrderByReturnDateDescIdDesc(ReturnLevel returnLevel);

    // Approval queues: pending returns awaiting a given approver.
    List<PurchaseReturn> findByReturnLevelAndStatusOrderByReturnDateDescIdDesc(
            ReturnLevel returnLevel, com.spartan.dms.enums.ReturnStatus status);

    List<PurchaseReturn> findBySuperStockistIdAndReturnLevelAndStatusOrderByReturnDateDescIdDesc(
            Long superStockistId, ReturnLevel returnLevel, com.spartan.dms.enums.ReturnStatus status);

    boolean existsByProductId(Long productId);

    boolean existsByDistributorId(Long distributorId);

    boolean existsBySuperStockistId(Long superStockistId);
}
