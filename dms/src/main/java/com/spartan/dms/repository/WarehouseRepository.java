package com.spartan.dms.repository;

import com.spartan.dms.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    Optional<Warehouse> findBySuperStockistIdAndProductId(Long superStockistId, Long productId);

    Optional<Warehouse> findByDistributorIdAndProductId(Long distributorId, Long productId);

    List<Warehouse> findBySuperStockistId(Long superStockistId);

    List<Warehouse> findByDistributorId(Long distributorId);

    List<Warehouse> findBySuperStockistIdAndQuantityLessThanEqual(Long superStockistId, Integer threshold);

    List<Warehouse> findByDistributorIdAndQuantityLessThanEqual(Long distributorId, Integer threshold);

    boolean existsByProductId(Long productId);
}
