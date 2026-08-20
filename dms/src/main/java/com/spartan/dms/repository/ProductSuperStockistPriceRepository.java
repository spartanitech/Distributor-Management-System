package com.spartan.dms.repository;

import com.spartan.dms.entity.ProductSuperStockistPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductSuperStockistPriceRepository extends JpaRepository<ProductSuperStockistPrice, Long> {

    Optional<ProductSuperStockistPrice> findByProductIdAndSuperStockistId(Long productId, Long superStockistId);

    List<ProductSuperStockistPrice> findByProductId(Long productId);

    boolean existsByProductId(Long productId);

    List<ProductSuperStockistPrice> findBySuperStockistId(Long superStockistId);

    boolean existsBySuperStockistId(Long superStockistId);

    void deleteByProductIdAndSuperStockistId(Long productId, Long superStockistId);
}
